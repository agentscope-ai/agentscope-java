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
package io.agentscope.extensions.a2ui.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** (De)serialization helpers for {@link A2uiEnvelope}. */
public final class A2uiEnvelopeUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private A2uiEnvelopeUtils() {}

    public static String toJson(A2uiEnvelope envelope) {
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize A2UI envelope", e);
        }
    }

    /**
     * Extracts the envelope from a message's last tool-result text, if any. Lenient: any parse
     * failure or missing protocol fields yield {@link Optional#empty()} (never throws).
     */
    public static Optional<A2uiEnvelope> extractEnvelope(Msg msg) {
        if (msg == null) {
            return Optional.empty();
        }
        List<ToolResultBlock> results = msg.getContentBlocks(ToolResultBlock.class);
        return results.isEmpty()
                ? Optional.empty()
                : extractEnvelope(results.get(results.size() - 1));
    }

    /** Extracts the envelope from a single tool result; same lenient contract as {@link
     * #extractEnvelope(Msg)}. */
    public static Optional<A2uiEnvelope> extractEnvelope(ToolResultBlock result) {
        if (result == null || result.getOutput() == null) {
            return Optional.empty();
        }
        String text =
                result.getOutput().stream()
                        .filter(block -> block instanceof TextBlock)
                        .map(block -> ((TextBlock) block).getText())
                        .collect(Collectors.joining());
        if (text == null || !text.strip().startsWith("{")) {
            return Optional.empty();
        }
        try {
            A2uiEnvelope envelope = MAPPER.readValue(text, A2uiEnvelope.class);
            // Other A2UI producers write the same version as "v1.0"; accept both spellings on read.
            if (envelope.protocolVersion() == null
                    || !A2uiConstants.PROTOCOL_VERSION_ALIASES.contains(envelope.protocolVersion())
                    || envelope.messageType() == null) {
                return Optional.empty();
            }
            return Optional.of(envelope);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
