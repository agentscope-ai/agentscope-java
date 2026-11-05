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
