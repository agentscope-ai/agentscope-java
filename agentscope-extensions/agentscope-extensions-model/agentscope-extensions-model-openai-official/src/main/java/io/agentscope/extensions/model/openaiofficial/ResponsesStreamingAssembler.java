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
package io.agentscope.extensions.model.openaiofficial;

import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseErrorEvent;
import com.openai.models.responses.ResponseFailedEvent;
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseReasoningSummaryTextDeltaEvent;
import com.openai.models.responses.ResponseReasoningTextDeltaEvent;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Assembles a streaming Responses API event stream into a {@code Flux<ChatResponse>}.
 *
 * <p>Routes each {@link ResponseStreamEvent} to the appropriate handler: text deltas produce
 * {@link TextBlock} fragments, reasoning summary deltas produce {@link
 * ThinkingBlock} fragments, and function-call argument deltas produce fragment {@link
 * ToolUseBlock}s with placeholder id/name when the output-item-added event was not received.
 *
 * <p>Terminal events (completed/incomplete) trigger re-extraction of refusal, reasoning
 *  metadata, and usage from the final {@link Response}. Refusal is checked first and
 * short-circuits to a non-retryable exception. Failed/error events are translated to
 * {@link OpenAIOfficialModelException}.
 *
 * <p>The SDK {@link StreamResponse} is closed on any terminal signal (complete, error,
 * cancel) via {@code doFinally}.
 */
final class ResponsesStreamingAssembler {

    private ResponsesStreamingAssembler() {}

    private static final Logger log = LoggerFactory.getLogger(ResponsesStreamingAssembler.class);

    private static final String FRAGMENT_PLACEHOLDER = "__fragment__";

    /**
     * Converts a SDK streaming response into a {@code Flux<ChatResponse>}.
     *
     * @param streamResponse the SDK stream of {@link ResponseStreamEvent}s
     * @param modelName the model name for error context
     * @param startTime the start time for usage timing
     * @return a flux of chat responses, one per emitted block
     */
    static Flux<ChatResponse> assemble(
            StreamResponse<ResponseStreamEvent> streamResponse,
            String modelName,
            Instant startTime) {
        StreamingState state = new StreamingState(modelName, startTime);

        return Flux.fromStream(streamResponse.stream())
                .doFinally(signal -> closeQuietly(streamResponse))
                .<ChatResponse>handle(
                        (event, sink) -> {
                            try {
                                for (ChatResponse response : state.processEvent(event)) {
                                    sink.next(response);
                                }
                            } catch (OpenAIOfficialModelException e) {
                                sink.error(e);
                            } catch (RuntimeException e) {
                                sink.error(OpenAIErrorTranslator.translate(e, modelName));
                            }
                        })
                .onErrorMap(e -> OpenAIErrorTranslator.translate(e, modelName));
    }

    static void closeQuietly(StreamResponse<?> streamResponse) {
        try {
            streamResponse.close();
        } catch (Exception e) {
            log.debug("Failed to close SDK stream", e);
        }
    }

    // ── Streaming state ──────────────────────────────────────────────────

    private static final class StreamingState {
        private final String modelName;
        private final Instant startTime;
        private final Map<String, ToolCallInfo> itemRegistry = new HashMap<>();
        private final StringBuilder reasoningTextAccumulator = new StringBuilder();

        StreamingState(String modelName, Instant startTime) {
            this.modelName = modelName;
            this.startTime = startTime;
        }

        /**
         * Routes a single stream event to the appropriate handler.
         *
         * @return a list of 0 or 1 {@link ChatResponse} blocks; terminal events produce a
         *     single block with usage and metadata
         */
        List<ChatResponse> processEvent(ResponseStreamEvent event) {
            List<ChatResponse> results = new ArrayList<>();

            if (event.isOutputItemAdded()) {
                handleOutputItemAdded(event.asOutputItemAdded());
            } else if (event.isOutputTextDelta()) {
                ResponseTextDeltaEvent deltaEvent = event.asOutputTextDelta();
                if (!deltaEvent.delta().isEmpty()) {
                    results.add(handleTextDelta(deltaEvent));
                }
            } else if (event.isReasoningSummaryTextDelta()) {
                ResponseReasoningSummaryTextDeltaEvent deltaEvent =
                        event.asReasoningSummaryTextDelta();
                if (!deltaEvent.delta().isEmpty()) {
                    results.add(handleReasoningSummaryDelta(deltaEvent));
                }
            } else if (event.isReasoningTextDelta()) {
                handleReasoningTextDelta(event.asReasoningTextDelta());
            } else if (event.isFunctionCallArgumentsDelta()) {
                ResponseFunctionCallArgumentsDeltaEvent deltaEvent =
                        event.asFunctionCallArgumentsDelta();
                if (!deltaEvent.delta().isEmpty()) {
                    results.add(handleFunctionCallArgumentsDelta(deltaEvent));
                }
            } else if (event.isCompleted()) {
                results.add(handleTerminal(event.asCompleted().response()));
            } else if (event.isIncomplete()) {
                results.add(handleTerminal(event.asIncomplete().response()));
            } else if (event.isFailed()) {
                throw handleFailed(event.asFailed());
            } else if (event.isError()) {
                throw handleErrorEvent(event.asError());
            }
            // All other events (text.done, reasoning_summary.done, reasoning_text.done,
            // function_arguments.done, output_item.done, refusal.delta, refusal.done)
            // produce no ChatResponse blocks.

            return results;
        }

        // ── Event handlers ──

        private void handleOutputItemAdded(ResponseOutputItemAddedEvent event) {
            ResponseOutputItem item = event.item();
            if (item.isFunctionCall()) {
                ResponseFunctionToolCall call = item.asFunctionCall();
                Optional<String> itemIdOpt = call.id();
                if (itemIdOpt.isPresent()) {
                    itemRegistry.put(itemIdOpt.get(), new ToolCallInfo(call.callId(), call.name()));
                } else {
                    log.warn(
                            "Function call output item missing item ID; subsequent argument"
                                    + " deltas will use placeholder, callId={}, name={}",
                            call.callId(),
                            call.name());
                }
            }
        }

        private ChatResponse handleTextDelta(ResponseTextDeltaEvent event) {
            List<ContentBlock> content = new ArrayList<>();
            content.add(TextBlock.builder().text(event.delta()).build());
            return ChatResponse.builder().content(content).build();
        }

        private ChatResponse handleReasoningSummaryDelta(
                ResponseReasoningSummaryTextDeltaEvent event) {
            List<ContentBlock> content = new ArrayList<>();
            content.add(ThinkingBlock.builder().thinking(event.delta()).build());
            return ChatResponse.builder().content(content).build();
        }

        private void handleReasoningTextDelta(ResponseReasoningTextDeltaEvent event) {
            String delta = event.delta();
            if (!delta.isEmpty()) {
                reasoningTextAccumulator.append(delta);
            }
        }

        private ChatResponse handleFunctionCallArgumentsDelta(
                ResponseFunctionCallArgumentsDeltaEvent event) {
            ToolCallInfo info = itemRegistry.get(event.itemId());
            String callId = info != null ? info.callId() : "";
            String name = info != null ? info.name() : FRAGMENT_PLACEHOLDER;
            List<ContentBlock> content = new ArrayList<>();
            content.add(
                    ToolUseBlock.builder()
                            .id(callId)
                            .name(name)
                            .input(new HashMap<>())
                            .content(event.delta())
                            .build());
            return ChatResponse.builder().content(content).build();
        }

        private ChatResponse handleTerminal(Response response) {
            // Step 0: refusal gate
            String refusal = extractRefusal(response);
            if (!refusal.isEmpty()) {
                throw new OpenAIOfficialModelException(
                        "Model response was refused: " + refusal, null, modelName);
            }

            // Step 1: re-extraction from terminal response.output()
            String encryptedContent = null;
            StringBuilder summaryBuilder = new StringBuilder();
            for (ResponseOutputItem item : response.output()) {
                if (item.isReasoning()) {
                    ResponseReasoningItem reasoning = item.asReasoning();
                    for (ResponseReasoningItem.Summary summary : reasoning.summary()) {
                        summaryBuilder.append(summary.text());
                    }
                    if (encryptedContent == null) {
                        Optional<String> ec = reasoning.encryptedContent();
                        if (ec.isPresent() && !ec.get().isEmpty()) {
                            encryptedContent = ec.get();
                        }
                    }
                }
            }
            String summaryText = summaryBuilder.toString();
            String reasoningText = reasoningTextAccumulator.toString();

            // Build metadata
            String responseId = response.id();
            Map<String, Object> metadata = ResponsesHelper.extractResponseMetadata(response);
            String finishReason = (String) metadata.get(OpenAIOfficialConstants.MD_RESPONSE_STATUS);
            ChatUsage usage = ResponsesHelper.extractUsage(response, startTime, metadata);

            // Reasoning metadata is placed on a ThinkingBlock in the terminal
            // content for reasoning replay.
            List<ContentBlock> terminalContent = new ArrayList<>();
            if (encryptedContent != null || !summaryText.isEmpty() || !reasoningText.isEmpty()) {
                Map<String, Object> thinkingMetadata = new HashMap<>();
                if (encryptedContent != null) {
                    thinkingMetadata.put(
                            OpenAIOfficialConstants.MD_REASONING_ENCRYPTED_CONTENT,
                            encryptedContent);
                }
                if (!reasoningText.isEmpty()) {
                    thinkingMetadata.put(OpenAIOfficialConstants.MD_REASONING_TEXT, reasoningText);
                }
                terminalContent.add(ThinkingBlock.builder().metadata(thinkingMetadata).build());
            }

            return ChatResponse.builder()
                    .id(responseId)
                    .content(terminalContent)
                    .usage(usage)
                    .metadata(metadata)
                    .finishReason(finishReason)
                    .build();
        }

        private String extractRefusal(Response response) {
            StringBuilder refusalBuilder = new StringBuilder();
            for (ResponseOutputItem item : response.output()) {
                if (item.isMessage()) {
                    for (ResponseOutputMessage.Content content : item.asMessage().content()) {
                        if (content.isRefusal()) {
                            refusalBuilder.append(content.asRefusal().refusal());
                        }
                    }
                }
            }
            return refusalBuilder.toString();
        }

        private OpenAIOfficialModelException handleFailed(ResponseFailedEvent event) {
            Response response = event.response();
            Optional<ResponseError> errorOpt = response.error();
            if (errorOpt.isEmpty()) {
                return new OpenAIOfficialModelException(
                        "OpenAI API stream failed", null, modelName);
            }
            ResponseError error = errorOpt.get();
            String message = error.message();
            try {
                message = message + " (code: " + error.code().asString() + ")";
            } catch (RuntimeException ignored) {
                // code field may be missing — ResponseError.code() uses getRequired
            }
            return new OpenAIOfficialModelException(message, null, modelName);
        }

        private OpenAIOfficialModelException handleErrorEvent(ResponseErrorEvent event) {
            String message = event.message();
            Optional<String> code = event.code();
            if (code.isPresent()) {
                message = message + " (code: " + code.get() + ")";
            }
            return new OpenAIOfficialModelException(message, null, modelName);
        }
    }

    private record ToolCallInfo(String callId, String name) {}
}
