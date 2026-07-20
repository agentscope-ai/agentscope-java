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

package io.agentscope.core.a2a.agent;

import io.agentscope.core.a2a.agent.hitl.A2aHandoff;
import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.agent.hitl.A2aPendingTool;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Internal conversion between a credential-free wire handoff and a client-local capability. */
final class HitlMessageCodec {

    private HitlMessageCodec() {}

    /**
     * Remove caller-local HITL capabilities before an input message enters hooks, tracing, memory,
     * or wire conversion. Credential-free messages keep their original identity.
     */
    static List<Msg> credentialFreeInputs(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return msgs;
        }
        List<Msg> sanitized = new ArrayList<>(msgs.size());
        boolean changed = false;
        for (Msg msg : msgs) {
            Msg safe = credentialFreeInput(msg);
            sanitized.add(safe);
            changed |= safe != msg;
        }
        return changed ? Collections.unmodifiableList(sanitized) : msgs;
    }

    static Msg credentialFreeInput(Msg msg) {
        if (msg == null || msg.getMetadata() == null || msg.getMetadata().isEmpty()) {
            return msg;
        }
        Map<String, Object> safeMetadata =
                MessageConvertUtil.stripSensitiveMetadata(msg.getMetadata());
        if (msg.getMetadata().equals(safeMetadata)) {
            return msg;
        }
        return Msg.builderForRole(msg.getRole())
                .id(msg.getId())
                .name(msg.getName())
                .content(msg.getContent())
                .metadata(safeMetadata)
                .timestamp(msg.getTimestamp())
                .usage(msg.getUsage())
                .build();
    }

    static Msg enhanceTerminal(Msg msg, String nextResumeToken) {
        if (msg == null
                || nextResumeToken == null
                || msg.getMetadata() == null
                || !"input-required"
                        .equals(
                                msg.getMetadata()
                                        .get(MessageConstants.A2A_TASK_STATE_METADATA_KEY))) {
            return msg;
        }
        try {
            Map<String, Object> metadata = new LinkedHashMap<>(msg.getMetadata());
            A2aHandoff handoff =
                    new A2aHandoff(
                            required(metadata, MessageConstants.A2A_TASK_ID_METADATA_KEY),
                            required(metadata, MessageConstants.A2A_CONTEXT_ID_METADATA_KEY),
                            required(metadata, MessageConstants.HANDOFF_ID_METADATA_KEY),
                            parseType(
                                    required(metadata, MessageConstants.HANDOFF_TYPE_METADATA_KEY)),
                            Instant.parse(
                                    required(
                                            metadata,
                                            MessageConstants.HANDOFF_EXPIRES_AT_METADATA_KEY)),
                            parsePendingTools(
                                    metadata.get(MessageConstants.PENDING_TOOLS_METADATA_KEY)),
                            nextResumeToken);
            metadata.put(MessageConstants.LOCAL_HANDOFF_METADATA_KEY, handoff);
            return Msg.builderForRole(msg.getRole())
                    .id(msg.getId())
                    .name(msg.getName())
                    .content(msg.getContent())
                    .metadata(metadata)
                    .timestamp(msg.getTimestamp())
                    .usage(msg.getUsage())
                    .build();
        } catch (RuntimeException ignored) {
            return msg;
        }
    }

    private static String required(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Missing handoff metadata: " + key);
        }
        return String.valueOf(value);
    }

    private static A2aHandoffType parseType(String value) {
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        return A2aHandoffType.valueOf(normalized);
    }

    private static List<A2aPendingTool> parsePendingTools(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Pending tool list is missing");
        }
        List<A2aPendingTool> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(JsonUtils.getJsonCodec().convertValue(item, A2aPendingTool.class));
        }
        return List.copyOf(result);
    }
}
