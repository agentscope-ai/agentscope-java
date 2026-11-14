/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.journal.InvocationRecorder;
import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.journal.ThreadJournal;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.context.ContextTokenEstimator;
import io.agentscope.harness.agent.filesystem.AtomicTextFile;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Journal consumer; only committed delivery advances the independent extraction position. */
final class JournalMemoryFlush {
    private static final Set<String> SOURCE_TYPES =
            Set.of("message/user", "message/assistant", "tool/result");
    private final WorkspaceManager workspace;
    private final MemoryFlushManager extractor;
    private final Model model;
    private final Duration timeout;

    JournalMemoryFlush(
            WorkspaceManager workspace,
            MemoryFlushManager extractor,
            Model model,
            Duration timeout) {
        this.workspace = workspace;
        this.extractor = extractor;
        this.model = model;
        this.timeout = timeout;
    }

    record Position(String anchor, long after, int offset) {
        Position {
            if (after < 0 || offset < 0)
                throw new IllegalArgumentException("Invalid memory position");
        }
    }

    private record Batch(Position next, String text) {}

    Mono<Void> flush(RuntimeContext rc, ThreadKey key, ThreadJournal journal, boolean enabled) {
        return Mono.defer(
                        () -> {
                            String identity = hash(key.storagePath());
                            AtomicTextFile progress =
                                    workspace.atomicMemoryFile(
                                            rc, "memory/.flush-state/" + identity + ".json");
                            String before = progress.read();
                            Position position =
                                    before == null
                                            ? new Position("", 0, 0)
                                            : JsonUtils.getJsonCodec()
                                                    .fromJson(before, Position.class);
                            if (position == null)
                                throw new IllegalStateException("Invalid memory progress: null");
                            String anchor =
                                    journal
                                            .readMetadataAfter(Math.max(0, position.after() - 1), 1)
                                            .stream()
                                            .findFirst()
                                            .map(JournalEvent::eventId)
                                            .orElse("");
                            if (before != null
                                            && (position.after() > 0
                                                    || !position.anchor().isEmpty())
                                            && !anchor.equals(position.anchor())
                                    || position.after() > journal.head().seq())
                                return Mono.error(
                                        new IllegalStateException(
                                                "Memory progress does not match this Thread"
                                                        + " journal"));
                            if (before == null) position = new Position(anchor, 0, 0);
                            long through = target(rc, journal, position, enabled);
                            Clock clock =
                                    rc.get(Clock.class) == null
                                            ? Clock.systemDefaultZone()
                                            : rc.get(Clock.class);
                            Instant now = clock.instant();
                            String day = LocalDate.now(clock).toString();
                            long startAfter = position.after();
                            int startOffset = position.offset();
                            var confirmed = new AtomicReference<>(position);
                            return consume(
                                            rc,
                                            journal,
                                            progress,
                                            before,
                                            position,
                                            through,
                                            identity,
                                            "memory/" + day + ".md",
                                            now,
                                            confirmed)
                                    .timeout(timeout)
                                    .onErrorMap(
                                            TimeoutException.class,
                                            error -> {
                                                Position last = confirmed.get();
                                                boolean advanced =
                                                        last.after() != startAfter
                                                                || last.offset() != startOffset;
                                                return new TimeoutException(
                                                        ("Memory extraction timed out %s"
                                                             + " (thread=%s, startAfter=%d,"
                                                             + " startOffset=%d, after=%d,"
                                                             + " offset=%d, through=%d); increase"
                                                             + " flushTimeout or reduce admission"
                                                             + " budget")
                                                                .formatted(
                                                                        advanced
                                                                                ? "after partial"
                                                                                      + " progress"
                                                                                : "without"
                                                                                      + " progress",
                                                                        key.storagePath(),
                                                                        startAfter,
                                                                        startOffset,
                                                                        last.after(),
                                                                        last.offset(),
                                                                        through));
                                            });
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private long target(
            RuntimeContext rc, ThreadJournal journal, Position position, boolean enabled) {
        long head = journal.head().seq();
        if (!Boolean.TRUE.equals(rc.get(MemoryFlushMiddleware.INLINE_CONTEXT_KEY)))
            return enabled ? prefixThrough(journal, position.after(), head) : position.after();
        InvocationRecorder recorder = InvocationRecorder.from(rc);
        if (recorder == null)
            throw new IllegalStateException("Inline extraction requires a recorder");
        String key = invocationKey(rc);
        // A finalization replay must use the original prefix, even after model events were
        // appended.
        for (JournalEvent event : journal.scanMetadata(position.after(), head)) {
            if (event.type().equals("memory/extraction") && key.equals(event.data().get("key")))
                return ((Number) event.data().get("through")).longValue();
        }
        long through = enabled ? prefixThrough(journal, position.after(), head) : position.after();
        recorder.append("memory/extraction", Map.of("key", key, "through", through), false);
        recorder.flushNow();
        return through;
    }

    private static long prefixThrough(ThreadJournal journal, long after, long head) {
        int messages = 0;
        for (JournalEvent event : journal.scanMetadata(after, head)) {
            if (SOURCE_TYPES.contains(event.type()) && ++messages == 256) return event.seq();
        }
        return head;
    }

    private Mono<Void> consume(
            RuntimeContext rc,
            ThreadJournal journal,
            AtomicTextFile progress,
            String before,
            Position position,
            long through,
            String identity,
            String daily,
            Instant now,
            AtomicReference<Position> confirmed) {
        return Mono.defer(
                () -> {
                    if (position.after() >= through) return Mono.empty();
                    AtomicTextFile ledger = workspace.atomicMemoryFile(rc, daily);
                    String memory = workspace.atomicMemoryFile(rc, "MEMORY.md").read();
                    String entries = ledger.read();
                    String marker = "<!-- memory:" + hash(identity + position) + " next:";
                    Position delivered = delivered(entries, marker);
                    int window = model.getContextWindowSize();
                    int output = window > 0 ? Math.min(1024, Math.max(1, window / 8)) : 1024;
                    // Match ContextModelCalls' default context policy, with an 8K input cap.
                    long limit =
                            window > 0
                                    ? Math.min(
                                            8192,
                                            window
                                                    - Math.min(4096, Math.max(1, window / 8))
                                                    - Math.max(1, window / 50))
                                    : 8192;
                    int contextChars = (int) Math.max(0, Math.min(4000, limit / 4));
                    String known = edges(memory, contextChars);
                    String recent = tail(entries, contextChars);
                    Batch batch =
                            delivered == null
                                    ? batch(journal, position, through, known, recent, limit)
                                    : new Batch(delivered, "");
                    Mono<Map<String, Object>> completed =
                            Mono.fromSupplier(
                                            () -> {
                                                Map<String, Object> decisions = new HashMap<>();
                                                for (JournalEvent event :
                                                        journal.scanMetadata(
                                                                position.after(),
                                                                journal.head().seq()))
                                                    if (event.type().equals("memory/admission"))
                                                        decisions.put(
                                                                (String) event.data().get("key"),
                                                                event.data().get("decision"));
                                                return decisions;
                                            })
                                    .cache();
                    Mono<String> extraction =
                            batch.text().isBlank()
                                    ? Mono.just("NO_REPLY")
                                    : extractor.extract(
                                            batch.text(),
                                            known,
                                            recent,
                                            GenerateOptions.builder().maxTokens(output).build());
                    return extraction
                            .publishOn(Schedulers.boundedElastic())
                            .flatMap(
                                    content ->
                                            delivered != null
                                                    ? Mono.just("")
                                                    : extractor.admit(
                                                            rc,
                                                            content,
                                                            batch.text(),
                                                            known,
                                                            recent,
                                                            input ->
                                                                    assess(
                                                                            rc, position, input,
                                                                            completed)))
                            .publishOn(Schedulers.boundedElastic())
                            .flatMap(
                                    content -> {
                                        Position landed =
                                                append(ledger, marker, batch.next(), content, now);
                                        String next = JsonUtils.getJsonCodec().toJson(landed);
                                        if (!progress.compareAndSet(before, next))
                                            return Mono.error(
                                                    new IllegalStateException(
                                                            "Concurrent memory extraction; retry on"
                                                                    + " the next trigger"));
                                        confirmed.set(landed);
                                        return consume(
                                                rc, journal, progress, next, landed, through,
                                                identity, daily, now, confirmed);
                                    });
                });
    }

    private Mono<MemoryAdmission.Decision> assess(
            RuntimeContext rc,
            Position position,
            MemoryAdmission.Input input,
            Mono<Map<String, Object>> completed) {
        if (!Boolean.TRUE.equals(rc.get(MemoryFlushMiddleware.INLINE_CONTEXT_KEY)))
            return Mono.defer(() -> extractor.assess(input))
                    .subscribeOn(Schedulers.boundedElastic());
        String key = hash(invocationKey(rc) + position + JsonUtils.getJsonCodec().toJson(input));
        return completed
                .flatMap(
                        decisions -> {
                            Object recorded = decisions.get(key);
                            if (recorded != null)
                                return Mono.just(
                                        JsonUtils.getJsonCodec()
                                                .convertValue(
                                                        recorded, MemoryAdmission.Decision.class));
                            return extractor
                                    .assess(input)
                                    .publishOn(Schedulers.boundedElastic())
                                    .flatMap(
                                            decision -> {
                                                InvocationRecorder recorder =
                                                        InvocationRecorder.from(rc);
                                                recorder.append(
                                                        "memory/admission",
                                                        Map.of("key", key, "decision", decision),
                                                        false);
                                                return recorder.flush().thenReturn(decision);
                                            })
                                    .doOnNext(decision -> decisions.put(key, decision));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static String invocationKey(RuntimeContext rc) {
        InvocationRecorder recorder = InvocationRecorder.from(rc);
        Clock clock = rc.get(Clock.class);
        return recorder.turnId() + ":" + (clock == null ? recorder.runId() : clock.instant());
    }

    private Batch batch(
            ThreadJournal journal,
            Position position,
            long through,
            String known,
            String recent,
            long limit) {
        StringBuilder text = new StringBuilder();
        Position next = position;
        for (JournalEvent event : journal.scanMetadata(position.after(), through)) {
            String rendered = "";
            if (SOURCE_TYPES.contains(event.type())) {
                Msg message =
                        JsonUtils.getJsonCodec()
                                .convertValue(event.data().get("message"), Msg.class);
                rendered = extractor.serializeMessages(List.of(message));
            }
            int offset = event.seq() == position.after() + 1 ? position.offset() : 0;
            if (offset > rendered.length())
                throw new IllegalStateException("Memory source changed");
            String remaining = rendered.substring(offset);
            int count = prefix(text.toString(), remaining, known, recent, limit);
            if (count == 0 && !remaining.isEmpty()) {
                if (!text.isEmpty()) break;
                throw new IllegalStateException(
                        "Memory prompt leaves no room for a source character");
            }
            text.append(remaining, 0, count);
            if (count < remaining.length()) {
                next = new Position(next.anchor(), event.seq() - 1, offset + count);
                break;
            }
            if (!remaining.isEmpty()) text.append('\n');
            next = new Position(event.eventId(), event.seq(), 0);
        }
        return new Batch(next, text.toString());
    }

    private int prefix(String accepted, String remaining, String known, String recent, long limit) {
        if (remaining.isEmpty() || fits(accepted + remaining, known, recent, limit))
            return remaining.length();
        int low = 0, high = remaining.codePointCount(0, remaining.length());
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            int end = remaining.offsetByCodePoints(0, mid);
            if (fits(accepted + remaining.substring(0, end), known, recent, limit)) low = mid;
            else high = mid - 1;
        }
        return remaining.offsetByCodePoints(0, low);
    }

    private boolean fits(String text, String known, String recent, long limit) {
        var input =
                new ModelCallInput(
                        extractor.input(text + "\n", known, recent), List.of(), null, model);
        return ContextTokenEstimator.approximate().estimate(input).tokens() <= limit;
    }

    private static Position delivered(String entries, String marker) {
        if (entries == null) return null;
        int start = entries.indexOf(marker);
        if (start < 0) return null;
        int end = entries.indexOf(" -->", start);
        if (end < 0) throw new IllegalStateException("Incomplete memory delivery marker");
        return JsonUtils.getJsonCodec()
                .fromJson(entries.substring(start + marker.length(), end), Position.class);
    }

    private static Position append(
            AtomicTextFile ledger, String marker, Position next, String content, Instant now) {
        String entry =
                content.isBlank()
                        ? ""
                        : "\n"
                                + marker
                                + JsonUtils.getJsonCodec().toJson(next)
                                + " -->\n## Memory Flush — "
                                + now
                                + "\n"
                                + content
                                + "\n";
        for (int attempt = 0; attempt < 20; attempt++) {
            String before = ledger.read();
            Position delivered = delivered(before, marker);
            if (delivered != null) return delivered;
            if (entry.isEmpty()
                    || ledger.compareAndSet(before, Objects.toString(before, "") + entry))
                return next;
        }
        throw new IllegalStateException("Repeated memory ledger conflict");
    }

    private static String tail(String value, int size) {
        if (value == null) return "";
        return value.substring(Math.max(0, value.length() - size));
    }

    private static String edges(String value, int size) {
        if (value == null) return "";
        if (value.length() <= size) return value;
        return value.substring(0, size / 2) + "\n…\n" + tail(value, size / 2);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
