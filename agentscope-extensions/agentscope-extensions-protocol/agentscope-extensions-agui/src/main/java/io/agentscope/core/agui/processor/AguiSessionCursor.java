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
package io.agentscope.core.agui.processor;

import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Resolves the SessionLog replay position for an AG-UI attach request.
 *
 * <p>Accepted sources, in precedence order:
 *
 * <ol>
 *   <li>{@code Last-Event-ID} request header</li>
 *   <li>{@code forwardedProps.agentscope.afterSequence}</li>
 *   <li>{@code forwardedProps["agentscope.afterSequence"]}</li>
 *   <li>{@code forwardedProps.afterSequence}</li>
 * </ol>
 *
 * <p>This is an AgentScope extension, not part of the AG-UI protocol.
 */
final class AguiSessionCursor {

    static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";
    static final String AGENTSCOPE_PROP = "agentscope";
    static final String AFTER_SEQUENCE_PROP = "afterSequence";
    static final String AFTER_SEQUENCE_DOTTED_PROP = "agentscope.afterSequence";
    static final String RUN_ID_PROP = "runId";
    static final String EXECUTION_RUN_ID_PROP = "executionRunId";
    static final String RUN_ID_DOTTED_PROP = "agentscope.runId";

    private AguiSessionCursor() {}

    /**
     * Resolves the attach position.
     *
     * @param request the AG-UI runtime request
     * @param input the run input
     * @return the cursor, or {@code null} when the request is not an attach request
     * @throws IllegalArgumentException when a supplied cursor is not a non-negative integer
     */
    static Long resolve(AguiRuntimeContextRequest<?> request, RunAgentInput input) {
        String header = request != null ? request.firstHeader(LAST_EVENT_ID_HEADER) : null;
        if (header != null && !header.isBlank()) {
            return parse(header, LAST_EVENT_ID_HEADER);
        }
        Map<String, Object> props = input.getForwardedProps();
        Object agentscope = props.get(AGENTSCOPE_PROP);
        if (agentscope instanceof Map<?, ?> nested) {
            Object value = nested.get(AFTER_SEQUENCE_PROP);
            if (value != null) {
                return parse(value, "forwardedProps." + AFTER_SEQUENCE_DOTTED_PROP);
            }
        }
        Object dotted = props.get(AFTER_SEQUENCE_DOTTED_PROP);
        if (dotted != null) {
            return parse(dotted, "forwardedProps." + AFTER_SEQUENCE_DOTTED_PROP);
        }
        Object flat = props.get(AFTER_SEQUENCE_PROP);
        if (flat != null) {
            return parse(flat, "forwardedProps." + AFTER_SEQUENCE_PROP);
        }
        return null;
    }

    private static long parse(Object value, String source) {
        long seq;
        if (value instanceof Number number) {
            // longValue() silently truncates fractions and wraps out-of-range values, which would
            // resume from a wrong position; require an exact non-negative integer.
            try {
                seq = new BigDecimal(number.toString()).longValueExact();
            } catch (NumberFormatException | ArithmeticException error) {
                throw new IllegalArgumentException(
                        source + " must be a non-negative integer", error);
            }
        } else {
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                throw new IllegalArgumentException(source + " must be a non-negative integer");
            }
            try {
                seq = Long.parseLong(text);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        source + " must be a non-negative integer", error);
            }
        }
        if (seq < 0) {
            throw new IllegalArgumentException(source + " must be a non-negative integer");
        }
        return seq;
    }

    /**
     * Resolves the execution run id for a run-scoped attach.
     *
     * <p>Accepted sources, in precedence order:
     *
     * <ol>
     *   <li>{@code forwardedProps.agentscope.runId}
     *   <li>{@code forwardedProps.agentscope.executionRunId}
     *   <li>{@code forwardedProps["agentscope.runId"]}
     *   <li>{@code forwardedProps.runId}
     *   <li>{@code forwardedProps.executionRunId}
     * </ol>
     *
     * @param input the run input
     * @return the execution run id, or {@code null} when not supplied
     */
    static String resolveRunId(RunAgentInput input) {
        Map<String, Object> props = input.getForwardedProps();
        Object agentscope = props.get(AGENTSCOPE_PROP);
        if (agentscope instanceof Map<?, ?> nested) {
            Object value = nested.get(RUN_ID_PROP);
            if (value == null) {
                value = nested.get(EXECUTION_RUN_ID_PROP);
            }
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        Object dotted = props.get(RUN_ID_DOTTED_PROP);
        if (dotted != null && !String.valueOf(dotted).isBlank()) {
            return String.valueOf(dotted).trim();
        }
        Object runId = props.get(RUN_ID_PROP);
        if (runId != null && !String.valueOf(runId).isBlank()) {
            return String.valueOf(runId).trim();
        }
        Object executionRunId = props.get(EXECUTION_RUN_ID_PROP);
        if (executionRunId != null && !String.valueOf(executionRunId).isBlank()) {
            return String.valueOf(executionRunId).trim();
        }
        return null;
    }
}
