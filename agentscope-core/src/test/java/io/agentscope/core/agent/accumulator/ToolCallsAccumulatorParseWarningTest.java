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
package io.agentscope.core.agent.accumulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Regression tests for malformed-JSON warning behavior in ToolCallsAccumulator.
 *
 * <p>Streaming fragments produce incomplete JSON prefixes on every intermediate build(), which
 * must stay silent; only the finalization boundary ({@code buildAllToolCalls()}) may emit a
 * single sanitized warning, without raw arguments or a stack trace.
 */
@DisplayName("ToolCallsAccumulator malformed-JSON warning behavior")
class ToolCallsAccumulatorParseWarningTest {

    private final ToolCallsAccumulator accumulator = new ToolCallsAccumulator();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(ToolCallsAccumulator.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("Valid multi-fragment tool call emits no warning, including per-fragment builds")
    void validMultiFragmentEmitsNoWarning() {
        accumulator.add(
                ToolUseBlock.builder().id("call-1").name("get_weather").content("{\"ci").build());
        accumulator.add(
                ToolUseBlock.builder().name("__fragment__").content("ty\": \"Shanghai\"}").build());

        // Per-fragment build path (hook dispatch during streaming) sees an incomplete JSON
        // prefix and must not warn
        assertNotNull(accumulator.getAccumulatedToolCall("call-1"));

        ToolUseBlock result = accumulator.buildAllToolCalls().get(0);
        assertEquals("{\"city\": \"Shanghai\"}", result.getContent());
        assertTrue(warnings().isEmpty(), "expected no warnings, got: " + warnings());
    }

    @Test
    @DisplayName("Truncated arguments emit exactly one sanitized warning at finalization")
    void truncatedArgumentsEmitOneSanitizedWarning() {
        String secretValue = "s3cr3t-credential";
        accumulator.add(
                ToolUseBlock.builder()
                        .id("call-2")
                        .name("run_query")
                        .content("{\"token\": \"" + secretValue)
                        .build());
        accumulator.add(
                ToolUseBlock.builder().name("__fragment__").content("\", \"q\": 1").build());

        // Repeated per-fragment builds of the incomplete prefix must not spam warnings
        accumulator.getAccumulatedToolCall("call-2");
        accumulator.getAccumulatedToolCall("call-2");

        ToolUseBlock result = accumulator.buildAllToolCalls().get(0);
        // Malformed raw content is never exposed as content
        assertEquals("{}", result.getContent());

        List<ILoggingEvent> warns = warnings();
        assertEquals(1, warns.size(), "expected exactly one warning, got: " + warns);
        String message = warns.get(0).getFormattedMessage();
        assertTrue(message.contains("run_query"), "warning should name the tool: " + message);
        // Raw arguments (which may contain credentials/PII) must not be logged
        assertTrue(!message.contains(secretValue), "warning leaked raw arguments: " + message);
        assertTrue(!message.contains("token"), "warning leaked argument keys: " + message);
        // No stack trace
        assertNull(warns.get(0).getThrowableProxy());
    }
}
