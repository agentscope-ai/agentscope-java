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
import io.agentscope.core.model.ToolSchema;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTurn;
import io.agentscope.durable.DurableTypes.Signal;
import io.agentscope.durable.DurableTypes.ThreadKey;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A recoverable model dispatch. A publisher acknowledges only after durable MQ acceptance,
 * and deduplicates redelivery of the same callId. Reply consumers persist each signal on this Turn
 * before acknowledging their MQ delivery. Sequence numbers start at zero.
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
        if (generation instanceof ObjectNode fields)
            fields.remove(
                    List.of(
                            "apiKey",
                            "additionalHeaders",
                            "additionalQueryParams",
                            "baseUrl",
                            "endpointPath",
                            "executionConfig"));
        return new QueuedModelRequest(
                thread,
                turnId,
                callId,
                model,
                DurableJson.value(messages),
                DurableJson.value(tools),
                generation);
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
        validateReply(signal);
        var status = turn.status();
        if (status.status().terminal() || status.cancellationRequested()) return false;
        try {
            turn.signal(signal.id(), signal.key(), signal.value());
            return true;
        } catch (DurableException error) {
            if (error.code() != DurableException.Code.CONFLICT) throw error;
            status = turn.status();
            if (status.status().terminal() || status.cancellationRequested()) return false;
            throw error;
        }
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
