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
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
                    OffloadState offloadState = new OffloadState();

                    Disposable sub =
                            next.apply(input)
                                    .subscribe(
                                            event -> {
                                                synchronized (offloadState) {
                                                    if (!offloadState.timedOut) {
                                                        recordPreTimeoutEvent(
                                                                offloadState.preTimeoutStates,
                                                                event);
                                                        sink.next(event);
                                                    } else {
                                                        offloadState.backgroundBuffer.add(event);
                                                    }
                                                }
                                            },
                                            error -> {
                                                List<ToolUseBlock> offloadedSnapshot = null;
                                                boolean forwardError = false;
                                                synchronized (offloadState) {
                                                    if (!offloadState.timedOut) {
                                                        offloadState.completed = true;
                                                        forwardError = true;
                                                    } else if (!offloadState.offloadPublished) {
                                                        offloadState.pendingBackgroundError = error;
                                                    } else {
                                                        offloadedSnapshot = offloadState.offloaded;
                                                    }
                                                }
                                                if (forwardError) {
                                                    sink.error(error);
                                                } else if (offloadedSnapshot != null) {
                                                    failOffloadedTools(error, offloadedSnapshot);
                                                }
                                            },
                                            () -> {
                                                boolean completeSink = false;
                                                List<AgentEvent> bgEvents = null;
                                                Map<String, String> preTimeoutText = null;
                                                List<ToolUseBlock> offloadedSnapshot = null;
                                                synchronized (offloadState) {
                                                    if (offloadState.completed) {
                                                        return;
                                                    }
                                                    offloadState.completed = true;
                                                    if (!offloadState.timedOut) {
                                                        completeSink = true;
                                                    } else if (!offloadState.offloadPublished) {
                                                        offloadState.pendingBackgroundComplete =
                                                                true;
                                                    } else {
                                                        bgEvents =
                                                                List.copyOf(
                                                                        offloadState
                                                                                .backgroundBuffer);
                                                        offloadedSnapshot = offloadState.offloaded;
                                                        preTimeoutText =
                                                                snapshotPreTimeoutText(
                                                                        offloadState
                                                                                .preTimeoutStates,
                                                                        offloadedSnapshot);
                                                    }
                                                }
                                                if (completeSink) {
                                                    sink.complete();
                                                } else if (bgEvents != null) {
                                                    deliverBackgroundResult(
                                                            bgEvents,
                                                            preTimeoutText,
                                                            offloadedSnapshot,
                                                            ctx);
                                                }
                                            });

                    Disposable timer =
                            Schedulers.parallel()
                                    .schedule(
                                            () -> {
                                                List<ToolUseBlock> offloaded;
                                                Map<String, ToolResultBlock> completedResults;
                                                synchronized (offloadState) {
                                                    if (offloadState.completed
                                                            || offloadState.timedOut
                                                            || offloadState.disposed) {
                                                        return;
                                                    }
                                                    offloaded =
                                                            input.toolCalls().stream()
                                                                    .filter(
                                                                            tc ->
                                                                                    !isCompletedBeforeTimeout(
                                                                                            offloadState
                                                                                                    .preTimeoutStates,
                                                                                            tc
                                                                                                    .getId()))
                                                                    .toList();
                                                    if (offloaded.isEmpty()) {
                                                        return;
                                                    }
                                                    offloadState.timedOut = true;
                                                    offloadState.offloaded = offloaded;
                                                    completedResults =
                                                            snapshotCompletedResults(
                                                                    input.toolCalls(),
                                                                    offloadState.preTimeoutStates);
                                                }

                                                log.info(
                                                        "Tool execution timed out after {}s,"
                                                                + " offloading to background:"
                                                                + " session={}",
                                                        offloadTimeout.getSeconds(),
                                                        ctx != null ? ctx.getSessionId() : "null");
                                                emitPlaceholderAndComplete(
                                                        sink,
                                                        input.toolCalls(),
                                                        completedResults,
                                                        agent,
                                                        ctx);

                                                List<AgentEvent> bgEvents = null;
                                                Map<String, String> preTimeoutText = null;
                                                Throwable pendingError = null;
                                                synchronized (offloadState) {
                                                    offloadState.offloadPublished = true;
                                                    if (offloadState.pendingBackgroundComplete) {
                                                        offloadState.pendingBackgroundComplete =
                                                                false;
                                                        bgEvents =
                                                                List.copyOf(
                                                                        offloadState
                                                                                .backgroundBuffer);
                                                        preTimeoutText =
                                                                snapshotPreTimeoutText(
                                                                        offloadState
                                                                                .preTimeoutStates,
                                                                        offloaded);
                                                    } else if (offloadState.pendingBackgroundError
                                                            != null) {
                                                        pendingError =
                                                                offloadState.pendingBackgroundError;
                                                        offloadState.pendingBackgroundError = null;
                                                    }
                                                }
                                                if (bgEvents != null) {
                                                    deliverBackgroundResult(
                                                            bgEvents,
                                                            preTimeoutText,
                                                            offloaded,
                                                            ctx);
                                                } else if (pendingError != null) {
                                                    failOffloadedTools(pendingError, offloaded);
                                                }
                                            },
                                            offloadTimeout.toMillis(),
                                            TimeUnit.MILLISECONDS);

                    sink.onDispose(
                            () -> {
                                timer.dispose();
                                boolean cancelSub;
                                synchronized (offloadState) {
                                    offloadState.disposed = true;
                                    cancelSub = !offloadState.timedOut;
                                }
                                if (cancelSub) {
                                    sub.dispose();
                                }
                            });
                },
                FluxSink.OverflowStrategy.BUFFER);
    }

    private static void recordPreTimeoutEvent(
            Map<String, PreTimeoutToolState> states, AgentEvent event) {
        if (event instanceof ToolResultTextDeltaEvent delta && delta.getToolCallId() != null) {
            states.computeIfAbsent(delta.getToolCallId(), k -> new PreTimeoutToolState())
                    .appendText(delta.getDelta(), delta.getMetadata());
        } else if (event instanceof ToolResultDataDeltaEvent delta
                && delta.getToolCallId() != null) {
            states.computeIfAbsent(delta.getToolCallId(), k -> new PreTimeoutToolState())
                    .appendData(delta.getData(), delta.getMetadata());
        } else if (event instanceof ToolResultEndEvent end && end.getToolCallId() != null) {
            states.computeIfAbsent(end.getToolCallId(), k -> new PreTimeoutToolState())
                    .markEnd(end.getState(), end.getMetadata());
        }
    }

    private static boolean isCompletedBeforeTimeout(
            Map<String, PreTimeoutToolState> states, String toolCallId) {
        if (toolCallId == null) {
            return false;
        }
        PreTimeoutToolState state = states.get(toolCallId);
        return state != null && state.isCompleted();
    }

    private static Map<String, ToolResultBlock> snapshotCompletedResults(
            List<ToolUseBlock> toolCalls, Map<String, PreTimeoutToolState> states) {
        Map<String, ToolResultBlock> completed = new HashMap<>();
        for (ToolUseBlock tc : toolCalls) {
            if (tc.getId() == null) {
                continue;
            }
            PreTimeoutToolState state = states.get(tc.getId());
            if (state != null && state.isCompleted()) {
                completed.put(tc.getId(), state.toToolResultBlock(tc));
            }
        }
        return completed;
    }

    private static Map<String, String> snapshotPreTimeoutText(
            Map<String, PreTimeoutToolState> states, List<ToolUseBlock> offloadedTools) {
        Map<String, String> textById = new HashMap<>();
        for (ToolUseBlock tc : offloadedTools) {
            if (tc.getId() == null) {
                continue;
            }
            PreTimeoutToolState state = states.get(tc.getId());
            if (state != null && state.textBuffer.length() > 0) {
                textById.put(tc.getId(), state.textBuffer.toString());
            }
        }
        return textById;
    }

    private void failOffloadedTools(Throwable error, List<ToolUseBlock> offloadedTools) {
        log.warn("Background tool execution failed after timeout", error);
        if (asyncToolRegistry != null) {
            String errMsg =
                    error.getMessage() != null
                            ? error.getMessage()
                            : error.getClass().getSimpleName();
            for (ToolUseBlock tc : offloadedTools) {
                asyncToolRegistry.fail(tc.getId(), errMsg).subscribe();
            }
        }
    }

    private void emitPlaceholderAndComplete(
            FluxSink<AgentEvent> sink,
            List<ToolUseBlock> toolCalls,
            Map<String, ToolResultBlock> completedResults,
            Agent agent,
            RuntimeContext ctx) {
        String replyId = UUID.randomUUID().toString().replace("-", "");
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);

        String sessionId = ctx != null ? ctx.getSessionId() : null;
        for (ToolUseBlock toolCall : toolCalls) {
            ToolResultBlock completedResult = completedResults.get(toolCall.getId());
            if (completedResult != null) {
                // Fast tool already emitted its real events before timeout; preserve its real
                // result in context order and skip placeholder events and registry tracking.
                if (state != null) {
                    Msg resultMsg =
                            ToolResultMessageBuilder.buildToolResultMsg(
                                    completedResult, toolCall, agent.getName());
                    state.contextMutable().add(resultMsg);
                }
                continue;
            }

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
            List<AgentEvent> backgroundBuffer,
            Map<String, String> preTimeoutTextByToolId,
            List<ToolUseBlock> offloadedTools,
            RuntimeContext ctx) {
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        if (sessionId == null) {
            log.warn("Cannot deliver background tool result: no sessionId in RuntimeContext");
            return;
        }

        Set<String> offloadedIds = new HashSet<>();
        for (ToolUseBlock tc : offloadedTools) {
            if (tc.getId() != null) {
                offloadedIds.add(tc.getId());
            }
        }

        StringBuilder resultText = new StringBuilder();
        for (ToolUseBlock tc : offloadedTools) {
            String preText = preTimeoutTextByToolId.get(tc.getId());
            if (preText != null) {
                resultText.append(preText);
            }
        }
        for (AgentEvent event : backgroundBuffer) {
            if (event instanceof ToolResultTextDeltaEvent delta
                    && delta.getDelta() != null
                    && (delta.getToolCallId() == null
                            || offloadedIds.contains(delta.getToolCallId()))) {
                resultText.append(delta.getDelta());
            }
        }

        String toolNames =
                offloadedTools.stream()
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
            for (ToolUseBlock tc : offloadedTools) {
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

    private static final class OffloadState {
        private boolean completed;
        private boolean timedOut;
        private boolean disposed;
        private boolean offloadPublished;
        private boolean pendingBackgroundComplete;
        private Throwable pendingBackgroundError;
        private final Map<String, PreTimeoutToolState> preTimeoutStates = new HashMap<>();
        private final List<AgentEvent> backgroundBuffer = new ArrayList<>();
        private List<ToolUseBlock> offloaded = List.of();
    }

    private static final class PreTimeoutToolState {
        private final List<ContentBlock> blocks = new ArrayList<>();
        private final StringBuilder textBuffer = new StringBuilder();
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private ToolResultState finalState = ToolResultState.SUCCESS;
        private boolean completed;

        private void appendText(String delta, Map<String, Object> deltaMetadata) {
            mergeMetadata(deltaMetadata);
            if (delta != null && !delta.isEmpty()) {
                blocks.add(TextBlock.builder().text(delta).build());
                textBuffer.append(delta);
            }
        }

        private void appendData(ContentBlock data, Map<String, Object> deltaMetadata) {
            mergeMetadata(deltaMetadata);
            if (data != null) {
                blocks.add(data);
            }
        }

        private void markEnd(ToolResultState state, Map<String, Object> endMetadata) {
            mergeMetadata(endMetadata);
            if (state != ToolResultState.RUNNING) {
                this.finalState = state != null ? state : ToolResultState.SUCCESS;
                this.completed = true;
            }
        }

        private boolean isCompleted() {
            return completed;
        }

        private void mergeMetadata(Map<String, Object> extra) {
            if (extra != null && !extra.isEmpty()) {
                metadata.putAll(extra);
            }
        }

        private ToolResultBlock toToolResultBlock(ToolUseBlock toolCall) {
            return ToolResultBlock.builder()
                    .id(toolCall.getId())
                    .name(toolCall.getName())
                    .output(List.copyOf(blocks))
                    .metadata(metadata.isEmpty() ? null : Map.copyOf(metadata))
                    .state(finalState)
                    .build();
        }
    }
}
