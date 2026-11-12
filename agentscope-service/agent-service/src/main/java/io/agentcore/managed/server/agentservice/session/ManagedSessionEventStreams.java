/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.agentservice.session;

import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.PublicEvent;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** One live durable reader per local Session, with bounded pages and independent replay cursors. */
@Service
public class ManagedSessionEventStreams {
    private final ManagedJournalStore journal;
    private final SessionEventNotifier notifications;
    private final Duration recovery;
    private final Duration slowConsumer;
    private final long maxReplay;
    private final ConcurrentHashMap<ManagedScope, Feed> feeds = new ConcurrentHashMap<>();

    public ManagedSessionEventStreams(
            ManagedJournalStore journal,
            SessionEventNotifier notifications,
            @Value("${managed-agents.agent-service.sse-recovery-ms:15000}") long recoveryMillis,
            @Value("${managed-agents.agent-service.max-sse-replay-events:50000}") long maxReplay,
            @Value("${managed-agents.agent-service.sse-slow-consumer-seconds:30}")
                    long slowConsumerSeconds) {
        if (recoveryMillis < 100 || maxReplay < 1 || slowConsumerSeconds < 1)
            throw new IllegalArgumentException("SSE reader limits must be positive");
        this.journal = journal;
        this.notifications = notifications;
        this.recovery = Duration.ofMillis(recoveryMillis);
        this.slowConsumer = Duration.ofSeconds(slowConsumerSeconds);
        this.maxReplay = maxReplay;
    }

    public Flux<PublicEvent> stream(ManagedScope scope, long after) {
        return Flux.defer(
                () -> {
                    var feed =
                            feeds.compute(
                                    scope,
                                    (key, prior) -> {
                                        var selected = prior == null ? new Feed(scope) : prior;
                                        selected.references++;
                                        return selected;
                                    });
                    var cursor = new AtomicLong(after);
                    return feed.pages
                            .onBackpressureLatest()
                            .doOnSubscribe(ignored -> notifications.refresh(scope.messageGroup()))
                            .concatMap(page -> deliver(scope, cursor, page), 1)
                            .doFinally(
                                    ignored ->
                                            feeds.computeIfPresent(
                                                    scope,
                                                    (key, current) -> {
                                                        if (current != feed) return current;
                                                        return --current.references == 0
                                                                ? null
                                                                : current;
                                                    }));
                });
    }

    private Flux<PublicEvent> deliver(ManagedScope scope, AtomicLong cursor, Page page) {
        return Flux.defer(
                () -> {
                    if (page.watermark() - cursor.get() > maxReplay)
                        return Flux.error(
                                new ManagedStateException(
                                        "event_replay_limit",
                                        "SSE replay exceeds the configured limit; read paginated"
                                                + " events first"));
                    // A slow observer may skip shared pages. Fill only its missing prefix.
                    var missing =
                            cursor.get() < page.after()
                                    ? pages(scope, cursor, page.after())
                                            .concatMapIterable(Page::events, 1)
                                    : Flux.<PublicEvent>empty();
                    return missing.concatWith(
                                    Flux.fromIterable(page.events())
                                            .filter(event -> event.sequence() > cursor.get())
                                            .doOnNext(event -> cursor.set(event.sequence())))
                            .timeout(slowConsumer);
                });
    }

    private Flux<Page> refresh(ManagedScope scope, AtomicLong cursor) {
        return Mono.fromCallable(() -> journal.publicHead(scope))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        through -> {
                            if (cursor.get() < 0) {
                                cursor.set(through);
                                return Flux.just(new Page(through, through, List.of()));
                            }
                            return pages(scope, cursor, through);
                        });
    }

    private record Page(long after, long watermark, List<PublicEvent> events) {}

    private Flux<Page> pages(ManagedScope scope, AtomicLong cursor, long through) {
        return Flux.<Page>generate(
                        sink -> {
                            long after = cursor.get();
                            if (after >= through) {
                                sink.complete();
                                return;
                            }
                            var events =
                                    journal.publicEvents(
                                            scope, after, (int) Math.min(256, through - after));
                            long expected = after;
                            for (var event : events) {
                                if (event.sequence() != ++expected)
                                    throw new IllegalStateException(
                                            "Public event prefix has a gap");
                            }
                            if (events.isEmpty())
                                throw new IllegalStateException("Public event prefix has a gap");
                            cursor.set(expected);
                            sink.next(new Page(after, through, List.copyOf(events)));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private final class Feed {
        private final Flux<Page> pages;
        private int references;

        private Feed(ManagedScope scope) {
            pages =
                    Flux.defer(
                                    () -> {
                                        var cursor = new AtomicLong(-1);
                                        return Flux.merge(
                                                        notifications.wakeups(scope.messageGroup()),
                                                        Flux.interval(recovery)
                                                                .onBackpressureDrop()
                                                                .map(tick -> ""))
                                                .onBackpressureLatest()
                                                .concatMap(ignored -> refresh(scope, cursor), 1);
                                    })
                            .replay(1)
                            .refCount(1);
        }
    }
}
