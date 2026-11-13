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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AguiSessionCursorTest {

    @Test
    void lastEventIdHeaderTakesPrecedence() {
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .forwardedProps(Map.of("afterSequence", 3))
                        .build();
        AguiRuntimeContextRequest<?> request =
                AguiRuntimeContextRequest.builder()
                        .input(input)
                        .headers(Map.of("Last-Event-ID", List.of("7")))
                        .build();

        assertEquals(7L, AguiSessionCursor.resolve(request, input));
    }

    @Test
    void resolvesNestedAgentscopeForwardedProp() {
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .forwardedProps(Map.of("agentscope", Map.of("afterSequence", "9")))
                        .build();

        assertEquals(
                9L,
                AguiSessionCursor.resolve(
                        AguiRuntimeContextRequest.builder().input(input).build(), input));
    }

    @Test
    void resolvesDottedAndFlatForwardedProps() {
        RunAgentInput dotted =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .forwardedProps(Map.of("agentscope.afterSequence", 11))
                        .build();
        assertEquals(
                11L,
                AguiSessionCursor.resolve(
                        AguiRuntimeContextRequest.builder().input(dotted).build(), dotted));

        RunAgentInput flat =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .forwardedProps(Map.of("afterSequence", 12L))
                        .build();
        assertEquals(
                12L,
                AguiSessionCursor.resolve(
                        AguiRuntimeContextRequest.builder().input(flat).build(), flat));
    }

    @Test
    void returnsNullWhenNoCursorIsSupplied() {
        RunAgentInput input = RunAgentInput.builder().threadId("thread-1").runId("run-1").build();
        assertNull(
                AguiSessionCursor.resolve(
                        AguiRuntimeContextRequest.builder().input(input).build(), input));
    }

    @Test
    void resolvesExecutionRunIdFromForwardedProps() {
        RunAgentInput nested =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .forwardedProps(Map.of("agentscope", Map.of("runId", "exec-1")))
                        .build();
        assertEquals("exec-1", AguiSessionCursor.resolveRunId(nested));

        RunAgentInput nestedExecution =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .forwardedProps(Map.of("agentscope", Map.of("executionRunId", "exec-2")))
                        .build();
        assertEquals("exec-2", AguiSessionCursor.resolveRunId(nestedExecution));

        RunAgentInput dotted =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .forwardedProps(Map.of("agentscope.runId", "exec-3"))
                        .build();
        assertEquals("exec-3", AguiSessionCursor.resolveRunId(dotted));

        RunAgentInput flat =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .forwardedProps(Map.of("executionRunId", "exec-4"))
                        .build();
        assertEquals("exec-4", AguiSessionCursor.resolveRunId(flat));

        RunAgentInput missing = RunAgentInput.builder().threadId("thread-1").runId("run-1").build();
        assertNull(AguiSessionCursor.resolveRunId(missing));
    }

    @Test
    void rejectsFractionalAndOutOfRangeNumericCursors() {
        RunAgentInput fractional =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .forwardedProps(Map.of("afterSequence", 2.5d))
                        .build();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AguiSessionCursor.resolve(
                                AguiRuntimeContextRequest.builder().input(fractional).build(),
                                fractional));

        RunAgentInput outOfRange =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .forwardedProps(
                                Map.of(
                                        "afterSequence",
                                        new java.math.BigInteger("9223372036854775808")))
                        .build();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AguiSessionCursor.resolve(
                                AguiRuntimeContextRequest.builder().input(outOfRange).build(),
                                outOfRange));
    }

    @Test
    void acceptsIntegralNumericCursors() {
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .forwardedProps(Map.of("afterSequence", 4.0d))
                        .build();

        assertEquals(
                4L,
                AguiSessionCursor.resolve(
                        AguiRuntimeContextRequest.builder().input(input).build(), input));
    }

    @Test
    void rejectsNegativeAndMalformedCursors() {
        RunAgentInput input = RunAgentInput.builder().threadId("thread-1").runId("run-1").build();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AguiSessionCursor.resolve(
                                AguiRuntimeContextRequest.builder()
                                        .input(input)
                                        .headers(Map.of("Last-Event-ID", List.of("-1")))
                                        .build(),
                                input));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AguiSessionCursor.resolve(
                                AguiRuntimeContextRequest.builder()
                                        .input(input)
                                        .headers(Map.of("Last-Event-ID", List.of("abc")))
                                        .build(),
                                input));
    }
}
