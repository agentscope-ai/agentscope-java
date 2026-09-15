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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.core.http.Headers;
import com.openai.core.http.StreamResponse;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.SseException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.openai.errors.UnprocessableEntityException;
import com.openai.models.ErrorObject;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseErrorEvent;
import com.openai.models.responses.ResponseFailedEvent;
import com.openai.models.responses.ResponseFileSearchToolCall;
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent;
import com.openai.models.responses.ResponseFunctionCallArgumentsDoneEvent;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncompleteEvent;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputRefusal;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseReasoningSummaryTextDeltaEvent;
import com.openai.models.responses.ResponseReasoningTextDeltaEvent;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.ToolChoiceOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Shared test fixture factory for constructing SDK objects.
 *
 * <p>Provides helper methods to create the various SDK exception types needed by
 * {@link OpenAIErrorTranslatorTest} and later test classes, and helper methods to
 * build SDK {@link Response} objects for response parser tests. All SDK exception
 * builders require a {@link Headers} object; this factory supplies an empty one by
 * default.
 */
public final class TestSdkFixtures {

    public static final String MODEL_NAME = "gpt-4o";
    private static final String DEFAULT_RESPONSE_ID = "resp_test_123";
    private static final String DEFAULT_MSG_ID = "msg_test_001";
    private static final double DEFAULT_CREATED_AT = 1697000000.5;
    private static final double DEFAULT_COMPLETED_AT = 1697000001.5;

    private TestSdkFixtures() {}

    // ── Typed HTTP exceptions ──────────────────────────────────────────────

    public static BadRequestException badRequest(String message) {
        return BadRequestException.builder()
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static UnauthorizedException unauthorized(String message) {
        return UnauthorizedException.builder()
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static PermissionDeniedException permissionDenied(String message) {
        return PermissionDeniedException.builder()
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static NotFoundException notFound(String message) {
        return NotFoundException.builder()
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static UnprocessableEntityException unprocessableEntity(String message) {
        return UnprocessableEntityException.builder()
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static RateLimitException rateLimit(String message) {
        return RateLimitException.builder()
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    // ── Variable-status exceptions ─────────────────────────────────────────

    public static InternalServerException internalServer(int statusCode, String message) {
        return InternalServerException.builder()
                .statusCode(statusCode)
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static UnexpectedStatusCodeException unexpectedStatusCode(
            int statusCode, String message) {
        return UnexpectedStatusCodeException.builder()
                .statusCode(statusCode)
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static SseException sseException(int statusCode, String message) {
        return SseException.builder()
                .statusCode(statusCode)
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    // ── Non-HTTP exceptions ────────────────────────────────────────────────

    public static OpenAIIoException ioException(String message) {
        return new OpenAIIoException(message);
    }

    public static OpenAIRetryableException retryableException(String message) {
        return new OpenAIRetryableException(message);
    }

    public static OpenAIInvalidDataException invalidDataException(String message) {
        return new OpenAIInvalidDataException(message);
    }

    public static TimeoutException timeoutException(String message) {
        return new TimeoutException(message);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    public static Headers emptyHeaders() {
        return Headers.builder().build();
    }

    /**
     * Creates a Headers object with an X-Should-Retry header value.
     *
     * @param shouldRetry the X-Should-Retry value ("true" or "false")
     * @return headers containing the X-Should-Retry entry
     */
    public static Headers headersWithShouldRetry(String shouldRetry) {
        return Headers.builder().put("X-Should-Retry", shouldRetry).build();
    }

    /**
     * Creates an {@link ErrorObject} whose message field is set to the given string,
     * so that typed HTTP exceptions (e.g. {@link BadRequestException}) carry the
     * message in their own {@code getMessage()}.
     *
     * <p>All four required fields are populated to satisfy the builder's
     * {@code checkRequired} validation; code/param/type use placeholder values.
     *
     * @param message the error message to embed in the ErrorObject
     * @return a fully built ErrorObject
     */
    private static ErrorObject errorWithMessage(String message) {
        return ErrorObject.builder()
                .message(message)
                .code("test_error")
                .param("test_param")
                .type("invalid_request_error")
                .build();
    }

    // ── Response builders ──────────────────────────────────────────────────

    /**
     * Builds a Response with the given output items, status, and optional usage.
     *
     * @param output the output items
     * @param status the response status (e.g. {@link ResponseStatus#COMPLETED}), or null
     * @param usage the usage object, or null
     * @return a fully built Response
     */
    public static Response response(
            List<ResponseOutputItem> output, ResponseStatus status, ResponseUsage usage) {
        return response(output, status, usage, null, null, null, null);
    }

    public static Response response(
            List<ResponseOutputItem> output,
            ResponseStatus status,
            ResponseUsage usage,
            Double completedAt,
            Response.ServiceTier serviceTier,
            Response.IncompleteDetails incompleteDetails,
            ResponseError error) {
        Response.Builder builder =
                Response.builder()
                        .id(DEFAULT_RESPONSE_ID)
                        .createdAt(DEFAULT_CREATED_AT)
                        .output(output)
                        .error(Optional.empty())
                        .incompleteDetails(Optional.empty())
                        .instructions("test")
                        .metadata(Optional.empty())
                        .model("gpt-4o")
                        .parallelToolCalls(true)
                        .temperature(Optional.empty())
                        .toolChoice(ToolChoiceOptions.AUTO)
                        .tools(List.of())
                        .topP(Optional.empty());
        if (status != null) {
            builder.status(status);
        }
        if (usage != null) {
            builder.usage(usage);
        }
        if (completedAt != null) {
            builder.completedAt(completedAt);
        }
        if (serviceTier != null) {
            builder.serviceTier(serviceTier);
        }
        if (incompleteDetails != null) {
            builder.incompleteDetails(incompleteDetails);
        }
        if (error != null) {
            builder.error(error);
        }
        return builder.build();
    }

    /** Builds a basic completed Response with the given output items (no usage). */
    public static Response completedResponse(List<ResponseOutputItem> output) {
        return response(output, ResponseStatus.COMPLETED, null);
    }

    /** Builds a Response with a single message containing one OutputText part. */
    public static Response textResponse(String text) {
        return completedResponse(List.of(messageItem(text)));
    }

    /** Builds a Response with a single message containing a Refusal part. */
    public static Response refusalResponse(String refusal) {
        ResponseOutputMessage msg =
                ResponseOutputMessage.builder()
                        .id(DEFAULT_MSG_ID)
                        .addContent(ResponseOutputRefusal.builder().refusal(refusal).build())
                        .status(ResponseOutputMessage.Status.COMPLETED)
                        .build();
        return completedResponse(List.of(ResponseOutputItem.ofMessage(msg)));
    }

    /** Builds a Response with a single reasoning item. */
    public static Response reasoningResponse(
            String summaryText, String encryptedContent, String reasoningText) {
        return completedResponse(
                List.of(reasoningItem(summaryText, encryptedContent, reasoningText)));
    }

    /** Builds a Response with a single function call. */
    public static Response functionCallResponse(String callId, String name, String arguments) {
        return completedResponse(List.of(functionCallItem(callId, name, arguments)));
    }

    /** Builds a Response with interleaved output items. */
    public static Response interleavedResponse() {
        List<ResponseOutputItem> items = new ArrayList<>();
        items.add(messageItem("Hello"));
        items.add(reasoningItem("thinking", "encrypted123", "raw reasoning"));
        items.add(messageItem("World"));
        items.add(functionCallItem("call_1", "get_weather", "{\"city\":\"SF\"}"));
        return completedResponse(items);
    }

    /** Builds a Response with usage data. */
    public static Response usageResponse(long input, long output, long cached, long reasoning) {
        ResponseUsage usage = usage(input, output, cached, reasoning);
        return response(List.of(messageItem("test")), ResponseStatus.COMPLETED, usage);
    }

    /** Builds a completed Response with completedAt, serviceTier, incompleteDetails, and error set. */
    public static Response fullMetadataResponse() {
        return response(
                List.of(messageItem("hello")),
                ResponseStatus.INCOMPLETE,
                null,
                DEFAULT_COMPLETED_AT,
                Response.ServiceTier.PRIORITY,
                Response.IncompleteDetails.builder()
                        .reason(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS)
                        .build(),
                ResponseError.builder()
                        .code(ResponseError.Code.of("server_error"))
                        .message("Something went wrong")
                        .build());
    }

    // ── Output item builders ────────────────────────────────────────────────

    /** Builds a ResponseOutputMessage with a single OutputText content part. */
    public static ResponseOutputItem messageItem(String text) {
        ResponseOutputMessage msg =
                ResponseOutputMessage.builder()
                        .id(DEFAULT_MSG_ID)
                        .addContent(
                                ResponseOutputText.builder()
                                        .text(text)
                                        .annotations(List.of())
                                        .build())
                        .status(ResponseOutputMessage.Status.COMPLETED)
                        .build();
        return ResponseOutputItem.ofMessage(msg);
    }

    /** Builds a ResponseOutputMessage with multiple OutputText content parts. */
    public static ResponseOutputItem messageItemMultiText(String... texts) {
        ResponseOutputMessage.Builder builder =
                ResponseOutputMessage.builder()
                        .id(DEFAULT_MSG_ID)
                        .status(ResponseOutputMessage.Status.COMPLETED);
        for (String text : texts) {
            builder.addContent(
                    ResponseOutputText.builder().text(text).annotations(List.of()).build());
        }
        return ResponseOutputItem.ofMessage(builder.build());
    }

    /** Builds a ResponseReasoningItem with summary, encrypted content, and reasoning text. */
    public static ResponseOutputItem reasoningItem(
            String summaryText, String encryptedContent, String reasoningText) {
        ResponseReasoningItem.Builder builder = ResponseReasoningItem.builder().id("rs_test_001");
        if (summaryText != null) {
            builder.addSummary(ResponseReasoningItem.Summary.builder().text(summaryText).build());
        }
        if (encryptedContent != null) {
            builder.encryptedContent(encryptedContent);
        }
        if (reasoningText != null) {
            builder.content(
                    List.of(ResponseReasoningItem.Content.builder().text(reasoningText).build()));
        }
        return ResponseOutputItem.ofReasoning(builder.build());
    }

    /** Builds a ResponseFunctionToolCall wrapped in a ResponseOutputItem. */
    public static ResponseOutputItem functionCallItem(
            String callId, String name, String arguments) {
        return ResponseOutputItem.ofFunctionCall(
                ResponseFunctionToolCall.builder()
                        .arguments(arguments)
                        .callId(callId)
                        .name(name)
                        .build());
    }

    /** Builds a file_search_call output item — an unknown type the parser does not handle. */
    public static ResponseOutputItem fileSearchItem() {
        return ResponseOutputItem.ofFileSearchCall(
                ResponseFileSearchToolCall.builder()
                        .id("fs_test_001")
                        .queries(List.of("test query"))
                        .status(ResponseFileSearchToolCall.Status.COMPLETED)
                        .build());
    }

    // ── Usage builder ───────────────────────────────────────────────────────

    public static ResponseUsage usage(long input, long output, long cached, long reasoning) {
        return ResponseUsage.builder()
                .inputTokens(input)
                .inputTokensDetails(
                        ResponseUsage.InputTokensDetails.builder()
                                .cacheWriteTokens(0L)
                                .cachedTokens(cached)
                                .build())
                .outputTokens(output)
                .outputTokensDetails(
                        ResponseUsage.OutputTokensDetails.builder()
                                .reasoningTokens(reasoning)
                                .build())
                .totalTokens(input + output)
                .build();
    }

    // ── Streaming event fixtures ──────────────────────────────────────────

    public static StreamResponse<ResponseStreamEvent> streamOf(List<ResponseStreamEvent> events) {
        return new StreamResponse<ResponseStreamEvent>() {
            private final Stream<ResponseStreamEvent> stream = events.stream();

            @Override
            public Stream<ResponseStreamEvent> stream() {
                return stream;
            }

            @Override
            public void close() {
                stream.close();
            }
        };
    }

    public static ResponseStreamEvent textDeltaEvent(String delta, String itemId) {
        ResponseTextDeltaEvent textDelta =
                ResponseTextDeltaEvent.builder()
                        .delta(delta)
                        .itemId(itemId)
                        .contentIndex(0L)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .logprobs(List.of())
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isOutputTextDelta()).thenReturn(true);
        when(event.outputTextDelta()).thenReturn(Optional.of(textDelta));
        when(event.asOutputTextDelta()).thenReturn(textDelta);
        return event;
    }

    public static ResponseStreamEvent reasoningSummaryDeltaEvent(String delta, String itemId) {
        ResponseReasoningSummaryTextDeltaEvent evt =
                ResponseReasoningSummaryTextDeltaEvent.builder()
                        .delta(delta)
                        .itemId(itemId)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .summaryIndex(0L)
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isReasoningSummaryTextDelta()).thenReturn(true);
        when(event.reasoningSummaryTextDelta()).thenReturn(Optional.of(evt));
        when(event.asReasoningSummaryTextDelta()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent reasoningTextDeltaEvent(String delta, String itemId) {
        ResponseReasoningTextDeltaEvent evt =
                ResponseReasoningTextDeltaEvent.builder()
                        .delta(delta)
                        .itemId(itemId)
                        .contentIndex(0L)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isReasoningTextDelta()).thenReturn(true);
        when(event.reasoningTextDelta()).thenReturn(Optional.of(evt));
        when(event.asReasoningTextDelta()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent functionCallArgsDeltaEvent(String delta, String itemId) {
        ResponseFunctionCallArgumentsDeltaEvent evt =
                ResponseFunctionCallArgumentsDeltaEvent.builder()
                        .delta(delta)
                        .itemId(itemId)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isFunctionCallArgumentsDelta()).thenReturn(true);
        when(event.functionCallArgumentsDelta()).thenReturn(Optional.of(evt));
        when(event.asFunctionCallArgumentsDelta()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent functionCallArgsDoneEvent(
            String arguments, String itemId, String name) {
        ResponseFunctionCallArgumentsDoneEvent evt =
                ResponseFunctionCallArgumentsDoneEvent.builder()
                        .arguments(arguments)
                        .itemId(itemId)
                        .name(name)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isFunctionCallArgumentsDone()).thenReturn(true);
        when(event.functionCallArgumentsDone()).thenReturn(Optional.of(evt));
        when(event.asFunctionCallArgumentsDone()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent outputItemAddedEvent(ResponseOutputItem item) {
        ResponseOutputItemAddedEvent evt =
                ResponseOutputItemAddedEvent.builder()
                        .item(item)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isOutputItemAdded()).thenReturn(true);
        when(event.outputItemAdded()).thenReturn(Optional.of(evt));
        when(event.asOutputItemAdded()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent completedEvent(Response response) {
        ResponseCompletedEvent evt =
                ResponseCompletedEvent.builder().response(response).sequenceNumber(0L).build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isCompleted()).thenReturn(true);
        when(event.completed()).thenReturn(Optional.of(evt));
        when(event.asCompleted()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent incompleteEvent(Response response) {
        ResponseIncompleteEvent evt =
                ResponseIncompleteEvent.builder().response(response).sequenceNumber(0L).build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isIncomplete()).thenReturn(true);
        when(event.incomplete()).thenReturn(Optional.of(evt));
        when(event.asIncomplete()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent failedEvent(Response response) {
        ResponseFailedEvent evt =
                ResponseFailedEvent.builder().response(response).sequenceNumber(0L).build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isFailed()).thenReturn(true);
        when(event.failed()).thenReturn(Optional.of(evt));
        when(event.asFailed()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent errorEvent(String message, String code) {
        ResponseErrorEvent.Builder builder =
                ResponseErrorEvent.builder().message(message).sequenceNumber(0L);
        builder.param((String) null);
        if (code != null) {
            builder.code(code);
        }
        ResponseErrorEvent evt = builder.build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isError()).thenReturn(true);
        when(event.error()).thenReturn(Optional.of(evt));
        when(event.asError()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent noopEvent() {
        return mock(ResponseStreamEvent.class);
    }
}
