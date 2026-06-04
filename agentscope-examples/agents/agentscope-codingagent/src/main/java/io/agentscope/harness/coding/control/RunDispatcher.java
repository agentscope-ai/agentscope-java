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
package io.agentscope.harness.coding.control;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.coding.gateway.Gateway;
import io.agentscope.harness.coding.gateway.MsgContext;
import io.agentscope.harness.coding.middleware.MessageQueueMiddleware;
import io.agentscope.harness.coding.observability.CodingAgentMetrics;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Dispatches agent runs, handling busy-thread queueing.
 *
 * <ul>
 *   <li>If the thread is idle, dispatch immediately via {@link Gateway#run}
 *   <li>If the thread is busy, enqueue the message in {@code SqliteBaseStore} for injection at the
 *       next reasoning step by {@link MessageQueueMiddleware}
 * </ul>
 */
public class RunDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RunDispatcher.class);

    private final Gateway gateway;
    private final BaseStore store;
    private final CodingAgentMetrics metrics;

    public RunDispatcher(Gateway gateway, BaseStore store) {
        this(gateway, store, null);
    }

    public RunDispatcher(Gateway gateway, BaseStore store, CodingAgentMetrics metrics) {
        this.gateway = gateway;
        this.store = store;
        this.metrics = metrics;
    }

    /** Backwards-compatible overload without {@code userId}. */
    public Mono<Void> dispatch(String threadId, String agentId, String prompt, String githubToken) {
        return dispatch(threadId, agentId, prompt, githubToken, null);
    }

    /**
     * Dispatches an agent run or enqueues the message if the thread is busy.
     *
     * @param threadId canonical thread ID (used as sessionId in context)
     * @param agentId agent to dispatch to (e.g. "coding" or "reviewer")
     * @param prompt user prompt / webhook event description
     * @param githubToken optional decrypted GitHub token for this session
     * @param userId optional authenticated user identity, propagated to {@link MsgContext} for
     *     HarnessAgent namespace isolation
     */
    public Mono<Void> dispatch(
            String threadId, String agentId, String prompt, String githubToken, String userId) {
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(prompt).build())
                        .build();

        MsgContext ctx =
                new MsgContext(
                        "webhook",
                        null,
                        null,
                        threadId,
                        null,
                        agentId != null ? Map.of("agentId", agentId) : Map.of(),
                        userId);
        if (githubToken != null && !githubToken.isBlank()) {
            ctx =
                    new MsgContext(
                            ctx.channel(),
                            ctx.group(),
                            ctx.room(),
                            ctx.threadId(),
                            ctx.threadTs(),
                            mergeMaps(ctx.extra(), Map.of("github_token", githubToken)),
                            ctx.userId());
        }
        final MsgContext finalCtx = ctx;

        if (metrics != null) {
            metrics.recordDispatch();
        }
        Timer.Sample sample = metrics != null ? Timer.start() : null;

        return Mono.fromCallable(
                        () -> {
                            MessageQueueMiddleware.CURRENT_THREAD_ID.set(threadId);
                            return threadId;
                        })
                .flatMap(
                        id -> {
                            try {
                                return gateway.run(finalCtx, List.of(userMsg)).then();
                            } finally {
                                MessageQueueMiddleware.CURRENT_THREAD_ID.remove();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(
                        s -> {
                            if (sample != null) {
                                sample.stop(metrics.getDispatchDuration());
                            }
                        })
                .onErrorResume(
                        e -> {
                            if (metrics != null) {
                                metrics.recordDispatchError();
                            }
                            log.error(
                                    "[dispatcher] Run failed for thread={} agent={}: {}",
                                    threadId,
                                    agentId,
                                    e.getMessage(),
                                    e);
                            return Mono.empty();
                        });
    }

    /**
     * Enqueues a message for injection into the next reasoning step.
     *
     * @param threadId thread to enqueue for
     * @param payload message payload text
     */
    public void enqueue(String threadId, String payload) {
        String key = String.valueOf(System.currentTimeMillis()) + "-" + UUID.randomUUID();
        store.put(List.of("queue", threadId), key, Map.of("payload", payload));
        log.debug("[dispatcher] Enqueued message for thread={}", threadId);
    }

    /**
     * Dispatches the reviewer agent on a PR URL. Used by {@link
     * io.agentscope.harness.coding.tools.RequestPrReviewTool} and the CLI {@code review} subcommand.
     *
     * @param prUrl full GitHub PR URL
     */
    public Mono<Void> dispatchReviewer(String prUrl) {
        String threadId =
                ThreadIdFactory.fromGitHubReviewer(
                        extractOwner(prUrl), extractRepo(prUrl), extractPrNumber(prUrl));
        String prompt =
                "Please review the following pull request and provide detailed feedback: " + prUrl;
        return dispatch(threadId, "reviewer", prompt, null);
    }

    // -----------------------------------------------------------------
    //  PR URL parsing helpers
    // -----------------------------------------------------------------

    static String extractOwner(String prUrl) {
        String[] parts = prUrl.replaceAll("https://github\\.com/", "").split("/");
        return parts.length > 0 ? parts[0] : "unknown";
    }

    static String extractRepo(String prUrl) {
        String[] parts = prUrl.replaceAll("https://github\\.com/", "").split("/");
        return parts.length > 1 ? parts[1] : "unknown";
    }

    static int extractPrNumber(String prUrl) {
        try {
            String[] parts = prUrl.split("/");
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, String> mergeMaps(
            Map<String, String> base, Map<String, String> extra) {
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>(base);
        merged.putAll(extra);
        return java.util.Collections.unmodifiableMap(merged);
    }
}
