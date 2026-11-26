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
package io.agentscope.durable.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTurn;
import io.agentscope.durable.DurableTypes.Signal;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.ModelExecutions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recoverable model dispatch. A publisher acknowledges only after durable MQ acceptance,
 * and preserves the same callId on uncertain acceptance. Workers fence each replacement generation
 * with a unique begin ID. Reply consumers persist each signal on this Turn before acknowledging
 * their MQ delivery. Sequence numbers start at zero within each generation.
 */
public record QueuedModelRequest(
        ThreadKey thread,
        String turnId,
        String callId,
        String model,
        JsonNode messages,
        JsonNode tools,
        JsonNode options) {
    static QueuedModelRequest prepare(
            ThreadKey thread,
            String turnId,
            String callId,
            String model,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {
        var generation = DurableJson.value(options);
        if (generation instanceof ObjectNode fields) {
            fields.remove(
                    List.of(
                            "apiKey",
                            "additionalHeaders",
                            "additionalQueryParams",
                            "baseUrl",
                            "endpointPath",
                            "executionConfig"));
            if (options.getToolChoice() != null)
                fields.set("toolChoice", encodeChoice(options.getToolChoice()));
        }
        return new QueuedModelRequest(
                thread,
                turnId,
                callId,
                model,
                DurableJson.value(messages),
                DurableJson.value(tools),
                generation);
    }

    ModelExecutions.Key executionKey() {
        return new ModelExecutions.Key(thread, turnId, callId);
    }

    private static JsonNode encodeChoice(ToolChoice choice) {
        if (choice instanceof ToolChoice.Specific specific)
            return DurableJson.value(Map.of("mode", "specific", "name", specific.toolName()));
        String mode =
                choice instanceof ToolChoice.None
                        ? "none"
                        : choice instanceof ToolChoice.Required ? "required" : "auto";
        return DurableJson.value(Map.of("mode", mode));
    }

    /** Restore the prepared generation options with the Worker process's own connection settings. */
    public GenerateOptions decodeOptions() {
        if (options == null || options.isNull()) return GenerateOptions.builder().build();
        var fields = (ObjectNode) options.deepCopy();
        var choice = fields.remove("toolChoice");
        var decoded = JsonUtils.getJsonCodec().convertValue(fields, GenerateOptions.class);
        if (choice == null || choice.isNull()) return decoded;
        ToolChoice value =
                switch (choice.path("mode").asText()) {
                    case "auto" -> new ToolChoice.Auto();
                    case "none" -> new ToolChoice.None();
                    case "required" -> new ToolChoice.Required();
                    case "specific" -> new ToolChoice.Specific(choice.path("name").asText());
                    default -> throw new IllegalArgumentException("Unsupported queued tool choice");
                };
        return GenerateOptions.mergeOptions(
                GenerateOptions.builder().toolChoice(value).build(), decoded);
    }

    @FunctionalInterface
    public interface Publisher {
        void publish(QueuedModelRequest request);
    }

    /**
     * Persist a reply before acknowledging its MQ delivery. Returns false for a late reply
     * whose Turn has already completed or has accepted cancellation.
     */
    public static boolean accept(DurableTurn turn, Signal signal) {
        if (!signal.key().startsWith("x.model:"))
            throw new IllegalArgumentException("Expected a model reply signal");
        return !"ignored".equals(turn.signal(signal.id(), signal.key(), signal.value()).status());
    }

    /** A delivery uses one stable unique ID. A replacement delivery uses a fresh ID. */
    public static boolean begin(DurableTurn turn, String callId, String attempt) {
        DurableJson.text(attempt, "worker attempt");
        if ("0".equals(attempt))
            throw new IllegalArgumentException("Worker attempt 0 is reserved for inline replies");
        String id = "model-begin:" + callId + ":" + attempt;
        var receipt =
                turn.signal(
                        id,
                        key(callId, 0),
                        DurableJson.value(
                                java.util.Map.of(
                                        "modelCallId",
                                        callId,
                                        "workerAttempt",
                                        attempt,
                                        "modelOperation",
                                        "begin")));
        return !"ignored".equals(receipt.status());
    }

    static Signal seal(String callId, String attempt) {
        String id = "x.model:" + callId + ":seal";
        return new Signal(
                id,
                id,
                DurableJson.value(
                        java.util.Map.of(
                                "modelCallId",
                                callId,
                                "workerAttempt",
                                attempt,
                                "modelOperation",
                                "seal")));
    }

    static String key(String callId, String attempt, long sequence) {
        return "0".equals(attempt) ? key(callId, sequence) : key(callId, sequence) + ":" + attempt;
    }

    public static Signal reply(
            String callId, String attempt, long sequence, ChatResponse response, boolean last) {
        var original = reply(callId, sequence, response, last);
        var data = (ObjectNode) original.value().deepCopy();
        data.put("modelCallId", callId);
        data.put("workerAttempt", attempt);
        data.put("modelOperation", "reply");
        String key = key(callId, attempt, sequence);
        return new Signal(key, key, data);
    }

    public static Signal failure(
            String callId, String attempt, long sequence, String error, boolean retryable) {
        var data = (ObjectNode) failure(callId, sequence, error).value().deepCopy();
        data.put("modelCallId", callId);
        data.put("workerAttempt", attempt);
        data.put("modelOperation", "reply");
        data.put("retryable", retryable);
        String key = key(callId, attempt, sequence);
        return new Signal(key, key, data);
    }

    static String key(String callId, long sequence) {
        DurableJson.text(callId, "model call ID");
        if (sequence < 0) throw new IllegalArgumentException("Negative model reply sequence");
        return "x.model:" + callId + ":" + sequence;
    }

    public static Signal reply(String callId, long sequence, ChatResponse response, boolean last) {
        if (response == null && !last) throw new IllegalArgumentException("Missing model chunk");
        var data = new LinkedHashMap<String, Object>();
        data.put("response", response);
        data.put("last", last);
        String key = key(callId, sequence);
        return new Signal(key, key, DurableJson.value(data));
    }

    public static Signal failure(String callId, long sequence, String error) {
        if (error == null || error.isBlank())
            throw new IllegalArgumentException("Missing model error");
        String key = key(callId, sequence);
        return new Signal(key, key, DurableJson.value(java.util.Map.of("error", error)));
    }

    /** Provider-neutral classification. Workers may override classification with the boolean overload. */
    public static Signal failure(String callId, String attempt, long sequence, Throwable failure) {
        java.util.Objects.requireNonNull(failure, "failure");
        boolean retryable = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof io.agentscope.core.model.ModelHttpException http) {
                retryable = http.isRetryableHttpStatus();
                break;
            }
            if (cause instanceof java.io.IOException
                    || cause instanceof java.util.concurrent.TimeoutException) retryable = true;
        }
        return failure(callId, attempt, sequence, failure.getClass().getSimpleName(), retryable);
    }

    /** Check the broker consumer's wire payload before advancing to the next sequence. */
    static void validateReply(Signal signal) {
        var data = signal.value();
        var response = data.path("response");
        if (data.has("error")) {
            if (data.path("error").isTextual()
                    && !data.path("error").asText().isBlank()
                    && !data.has("response")
                    && !data.has("last")) return;
        } else if (data.path("last").isBoolean()) {
            if (data.path("last").booleanValue() && !data.hasNonNull("response")) return;
            boolean hasResponseField =
                    response.has("id")
                            || response.has("content")
                            || response.has("usage")
                            || response.has("metadata")
                            || response.has("finishReason");
            if (hasResponseField
                    && (!response.hasNonNull("content") || response.path("content").isArray()))
                return;
        }
        throw new IllegalArgumentException("Invalid model reply frame " + signal.key());
    }
}
