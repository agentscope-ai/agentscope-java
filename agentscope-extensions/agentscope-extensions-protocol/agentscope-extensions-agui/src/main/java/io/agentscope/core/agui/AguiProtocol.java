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
package io.agentscope.core.agui;

/**
 * AG-UI protocol version constants and negotiation helpers.
 *
 * <p>Consumers declare their version on {@code RunAgentInput.protocolVersion}; producers declare
 * their own on {@code RUN_STARTED.protocolVersion} (never an echo of the input). Absence means a
 * peer from before the protocol carried a version.
 *
 * @see <a href="https://docs.ag-ui.com/spec/1.0/basic/versioning">Versioning and Compatibility</a>
 */
public final class AguiProtocol {

    /**
     * Protocol version this producer speaks ({@code MAJOR.MINOR.PATCH}). Use this constant on
     * {@code RUN_STARTED}, never a hand-typed string.
     */
    public static final String PROTOCOL_VERSION = "1.0.0";

    private AguiProtocol() {}

    /**
     * Whether the consumer's declared version supports AG-UI 1.0 subagent attribution
     * ({@code SUBAGENT_*} lifecycle events and optional {@code subagentRunId} on content events).
     *
     * <p>Absent or blank means a pre-versioning peer and returns {@code false}, so producers keep
     * the AgentScope {@code CUSTOM subagent.*} downgrade path. Two-part forms such as {@code
     * "1.0"} compare as {@code "1.0.0"}.
     *
     * @param consumerProtocolVersion value from {@code RunAgentInput.protocolVersion}, may be null
     * @return true when the consumer speaks at least {@link #PROTOCOL_VERSION}
     */
    public static boolean supportsSubagentAttribution(String consumerProtocolVersion) {
        return compare(consumerProtocolVersion, PROTOCOL_VERSION) >= 0;
    }

    /**
     * Compare two {@code MAJOR.MINOR.PATCH} version identifiers numerically component by
     * component. Missing trailing components are treated as {@code 0} (so {@code "1.0"} equals
     * {@code "1.0.0"}).
     *
     * @param left left version, may be null/blank
     * @param right right version, may be null/blank
     * @return negative if left &lt; right, zero if equal, positive if left &gt; right; a null/blank
     *     left is treated as older than any concrete right
     */
    static int compare(String left, String right) {
        if (isBlank(left) && isBlank(right)) {
            return 0;
        }
        if (isBlank(left)) {
            return -1;
        }
        if (isBlank(right)) {
            return 1;
        }
        int[] leftParts = parse(left);
        int[] rightParts = parse(right);
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(leftParts[i], rightParts[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int[] parse(String version) {
        String[] segments = version.trim().split("\\.", 4);
        return new int[] {
            parseComponent(segments, 0), parseComponent(segments, 1), parseComponent(segments, 2)
        };
    }

    private static int parseComponent(String[] segments, int index) {
        if (index >= segments.length) {
            return 0;
        }
        String segment = segments[index];
        int end = 0;
        while (end < segment.length() && Character.isDigit(segment.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(segment.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
