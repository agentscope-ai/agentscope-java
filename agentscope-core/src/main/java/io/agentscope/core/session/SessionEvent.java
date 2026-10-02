package io.agentscope.core.session;

import io.agentscope.core.util.JsonUtils;
import java.util.Map;
import java.util.Objects;

/** An immutable native fact. Payload is JSON captured at acceptance, never a mutable Msg reference. */
public record SessionEvent(
        int schemaVersion,
        String eventId,
        long seq,
        long occurredAt,
        String type,
        String executionRunId,
        String turnId,
        boolean required,
        String payloadJson) {
    public SessionEvent {
        if (schemaVersion != 1 || seq < 1)
            throw new IllegalArgumentException("Unsupported event envelope");
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(payloadJson);
        JsonUtils.getJsonCodec().fromJson(payloadJson, Object.class);
    }

    public <T> T payload(Class<T> type) {
        return JsonUtils.getJsonCodec().fromJson(payloadJson, type);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> data() {
        return payload(Map.class);
    }
}
