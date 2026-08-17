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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

/**
 * Middleware that offloads long-running tool calls to background execution.
 *
 * <p>When the acting phase exceeds the configured timeout:
 * <ol>
 *   <li>The underlying tool execution continues in the background (never cancelled)</li>
 *   <li>A placeholder {@link ToolResultBlock} is written to agent context so the LLM can
 *       continue reasoning</li>
 *   <li>When the background execution completes, the real result is pushed to the session's
 *       inbox as a {@code HintBlock} and a wakeup is enqueued</li>
 * </ol>
 *
 * <p>The {@link InboxMiddleware} drains the inbox on the next reasoning step, making the
 * real result available to the LLM.
 */
public class AsyncToolMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(AsyncToolMiddleware.class);

    private static final String PLACEHOLDER_TEMPLATE =
            "<system-reminder>Tool '%s' is running in background (id=%s) for over %ds. "
                    + "You will be notified automatically when it finishes, so DO NOT poll, "
                    + "query, or wait for the result yourself. You have two options:\n"
                    + "1. Continue with other independent tasks;\n"
                    + "2. If nothing else to do, give a text reply without calling any tool."
                    + "</system-reminder>";

    private final MessageBus messageBus;
    private final Duration offloadTimeout;
    private final AsyncToolRegistry asyncToolRegistry;

    public AsyncToolMiddleware(MessageBus messageBus, Duration offloadTimeout) {
        this(messageBus, offloadTimeout, null);
    }

    public AsyncToolMiddleware(
            MessageBus messageBus, Duration offloadTimeout, AsyncToolRegistry asyncToolRegistry) {
        this.messageBus = messageBus;
        this.offloadTimeout = offloadTimeout;
        this.asyncToolRegistry = asyncToolRegistry;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {

        return Flux.create(
                sink -> {
                    AtomicBoolean completed = new AtomicBoolean(false);
                    AtomicBoolean timedOut = new AtomicBoolean(false);
                    List<AgentEvent> backgroundBuffer = new CopyOnWriteArrayList<>();

                    Disposable sub =
                            next.apply(input)
                                    .subscribe(
                                            event -> {
                                                if (!timedOut.get()) {
                                                    sink.next(event);
                                                } else {
                                                    backgroundBuffer.add(event);
                                                }
                                            },
                                            error -> {
                                                if (!timedOut.get()) {
                                                    sink.error(error);
                                                } else {
                                                    log.warn(
                                                            "Background tool execution failed"
                                                                    + " after timeout",
                                                            error);
                                                    if (asyncToolRegistry != null) {
                                                        String errMsg =
                                                                error.getMessage() != null
                                                                        ? error.getMessage()
                                                                        : error.getClass()
                                                                                .getSimpleName();
                                                        for (ToolUseBlock tc : input.toolCalls()) {
                                                            asyncToolRegistry
                                                                    .fail(tc.getId(), errMsg)
                                                                    .subscribe();
                                                        }
                                                    }
                                                }
                                            },
                                            () -> {
                                                if (completed.compareAndSet(false, true)) {
                                                    if (!timedOut.get()) {
                                                        sink.complete();
                                                    } else {
                                                        deliverBackgroundResult(
                                                                backgroundBuffer,
                                                                input.toolCalls(),
                                                                ctx);
                                                    }
                                                }
                                            });

                    Disposable timer =
                            Schedulers.parallel()
                                    .schedule(
                                            () -> {
                                                if (!completed.get()
                                                        && timedOut.compareAndSet(false, true)) {
                                                    log.info(
                                                            "Tool execution timed out after {}s,"
                                                                    + " offloading to background:"
                                                                    + " session={}",
                                                            offloadTimeout.getSeconds(),
                                                            ctx != null
                                                                    ? ctx.getSessionId()
                                                                    : "null");
                                                    emitPlaceholderAndComplete(
                                                            sink, input.toolCalls(), agent, ctx);
                                                }
                                            },
                                            offloadTimeout.toMillis(),
                                            TimeUnit.MILLISECONDS);

                    sink.onDispose(
                            () -> {
                                timer.dispose();
                                if (!timedOut.get()) {
                                    sub.dispose();
                                }
                            });
                },
                FluxSink.OverflowStrategy.BUFFER);
    }

    private void emitPlaceholderAndComplete(
            FluxSink<AgentEvent> sink,
            List<ToolUseBlock> toolCalls,
            Agent agent,
            RuntimeContext ctx) {
        String replyId = UUID.randomUUID().toString().replace("-", "");
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);

        String sessionId = ctx != null ? ctx.getSessionId() : null;
        for (ToolUseBlock toolCall : toolCalls) {
            if (asyncToolRegistry != null && sessionId != null) {
                asyncToolRegistry
                        .register(
                                new AsyncToolRecord(
                                        toolCall.getId(),
                                        sessionId,
                                        toolCall.getName(),
                                        toolCall.getId(),
                                        AsyncToolRecord.RUNNING,
                                        Instant.now()))
                        .subscribe();
            }

            String placeholderText =
                    String.format(
                            PLACEHOLDER_TEMPLATE,
                            toolCall.getName(),
                            toolCall.getId(),
                            offloadTimeout.getSeconds());

            ToolResultBlock placeholder =
                    ToolResultBlock.text(placeholderText)
                            .withIdAndName(toolCall.getId(), toolCall.getName());

            if (state != null) {
                Msg resultMsg =
                        ToolResultMessageBuilder.buildToolResultMsg(
                                placeholder, toolCall, agent.getName());
                state.contextMutable().add(resultMsg);
            }

            sink.next(new ToolResultStartEvent(replyId, toolCall.getId(), toolCall.getName()));
            sink.next(
                    new ToolResultTextDeltaEvent(
                            replyId, toolCall.getId(), toolCall.getName(), placeholderText));
            sink.next(
                    new ToolResultEndEvent(
                            replyId,
                            toolCall.getId(),
                            toolCall.getName(),
                            ToolResultState.SUCCESS));
        }
        sink.complete();
    }

    private void deliverBackgroundResult(
            List<AgentEvent> backgroundBuffer, List<ToolUseBlock> toolCalls, RuntimeContext ctx) {
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        if (sessionId == null) {
            log.warn("Cannot deliver background tool result: no sessionId in RuntimeContext");
            return;
        }

        StringBuilder resultText = new StringBuilder();
        for (AgentEvent event : backgroundBuffer) {
            if (event instanceof ToolResultTextDeltaEvent delta) {
                resultText.append(delta.getDelta());
            }
        }

        String toolNames =
                toolCalls.stream()
                        .map(ToolUseBlock::getName)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("unknown");

        String hintContent =
                String.format(
                        "<system-notification>Tool '%s' running in background has completed.\n\n"
                                + "Result:\n\n%s</system-notification>",
                        toolNames, resultText.length() > 0 ? resultText.toString() : "(no output)");

        String hintId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> hintPayload =
                Map.of("type", "hint", "id", hintId, "hint", hintContent, "source", "tool_output");

        if (asyncToolRegistry != null) {
            String resultStr = resultText.length() > 0 ? resultText.toString() : "(no output)";
            for (ToolUseBlock tc : toolCalls) {
                asyncToolRegistry.complete(tc.getId(), resultStr).subscribe();
            }
        }

        messageBus.inboxPush(sessionId, hintPayload).subscribe();

        String agentId = ctx.get("agentId");
        String userId = ctx.getUserId();
        messageBus
                .enqueueWakeup(
                        userId != null ? userId : "", sessionId, agentId != null ? agentId : "")
                .subscribe(
                        unused -> {},
                        error ->
                                log.warn(
                                        "Failed to enqueue wakeup for session {}: {}",
                                        sessionId,
                                        error.getMessage()));

        log.info(
                "Background tool '{}' completed, pushed result to inbox: session={}",
                toolNames,
                sessionId);
    }
}
