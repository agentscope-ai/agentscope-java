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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Tests for streaming error recovery in ReActAgent.
 *
 * <p>Verifies that when the model provider returns an error during streaming (e.g. the model
 * hallucinates a non-existent tool name), the agent can recover if tool calls were already
 * accumulated before the error, allowing the acting phase to return "Tool not found" results
 * and giving the model a chance to self-correct.
 *
 * @see <a href="https://github.com/agentscope-ai/agentscope-java/issues/3102">Issue #3102</a>
 */
@DisplayName("ReActAgent Stream Error Recovery Tests")
class ReActAgentStreamErrorRecoveryTest {

    /** Timeout for blocking operations in tests. */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    // ==================== Test Model implementations ====================

    /**
     * A model that emits a tool call chunk and then throws a {@link ModelHttpException}
     * simulating a provider-level "tool not found" error during streaming.
     */
    private static class ToolCallThenErrorModel implements Model {
        private final String toolName;
        private final String toolCallId;
        private final String errorMessage;
        private final int statusCode;
        private final AtomicInteger callCount = new AtomicInteger(0);

        ToolCallThenErrorModel(String toolName, String errorMessage, int statusCode) {
            this.toolName = toolName;
            this.toolCallId = "call_" + UUID.randomUUID().toString().substring(0, 8);
            this.errorMessage = errorMessage;
            this.statusCode = statusCode;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int call = callCount.getAndIncrement();
            if (call == 0) {
                // First call: emit a tool call chunk, then error
                ChatResponse toolCallChunk =
                        ChatResponse.builder()
                                .id("msg_tool_error")
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .name(toolName)
                                                        .id(toolCallId)
                                                        .input(Map.of())
                                                        .content("{}")
                                                        .build()))
                                .usage(new ChatUsage(10, 5, 15))
                                .build();
                return Flux.just(toolCallChunk)
                        .concatWith(
                                Flux.error(new SimpleModelHttpException(errorMessage, statusCode)));
            } else {
                // Subsequent calls: return a normal text response (model self-corrected)
                return Flux.just(
                        ChatResponse.builder()
                                .id("msg_recovery_" + call)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("Recovered successfully")
                                                        .build()))
                                .usage(new ChatUsage(10, 5, 15))
                                .build());
            }
        }

        @Override
        public String getModelName() {
            return "test-model-with-error";
        }

        int getCallCount() {
            return callCount.get();
        }
    }

    /**
     * A model that always throws a non-tool-related {@link ModelHttpException} during
     * streaming.
     */
    private static class NonRecoverableErrorModel implements Model {
        private final String errorMessage;
        private final int statusCode;

        NonRecoverableErrorModel(String errorMessage, int statusCode) {
            this.errorMessage = errorMessage;
            this.statusCode = statusCode;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new SimpleModelHttpException(errorMessage, statusCode));
        }

        @Override
        public String getModelName() {
            return "test-model-non-recoverable";
        }
    }

    /**
     * A model that throws a plain {@link RuntimeException} (not a {@link ModelHttpException}).
     */
    private static class RuntimeErrorModel implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new RuntimeException("Some runtime error"));
        }

        @Override
        public String getModelName() {
            return "test-model-runtime-error";
        }
    }

    /**
     * A model that ALWAYS emits a tool call chunk and then throws a recoverable tool-related
     * error — used to drive the consecutive-recovery budget to exhaustion.
     */
    private static class AlwaysToolCallThenErrorModel implements Model {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final AtomicInteger idSeq = new AtomicInteger(0);

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            callCount.incrementAndGet();
            return Flux.just(toolCallChunk("badTool", "call_" + idSeq.incrementAndGet()))
                    .concatWith(
                            Flux.error(
                                    new SimpleModelHttpException(
                                            "OpenAI API error in streaming response: "
                                                    + "Invalid tool name: badTool",
                                            400)));
        }

        @Override
        public String getModelName() {
            return "test-model-always-tool-error";
        }

        int getCallCount() {
            return callCount.get();
        }
    }

    /**
     * A scripted model used to verify that the consecutive-recovery budget RESETS after a
     * model stream completes without recovery:
     *
     * <ol>
     *   <li>calls 0-2: tool call + tool error (recovered, budget reaches the default cap of 3)
     *   <li>call 3: valid registered tool call, stream completes normally (budget resets to 0)
     *   <li>call 4: tool call + tool error — recovered ONLY because the budget was reset;
     *       without the reset the exhausted budget would propagate the error here
     *   <li>call 5: final text response
     * </ol>
     */
    private static class ScriptedRecoverySequenceModel implements Model {
        private final String validToolName;
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final AtomicInteger idSeq = new AtomicInteger(0);

        ScriptedRecoverySequenceModel(String validToolName) {
            this.validToolName = validToolName;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int call = callCount.getAndIncrement();
            return switch (call) {
                case 0, 1, 2 -> toolCallThenError("badTool");
                case 3 ->
                        Flux.just(toolCallChunk(validToolName, "call_" + idSeq.incrementAndGet()));
                case 4 -> toolCallThenError("badTool2");
                default -> Flux.just(textChunk("Done"));
            };
        }

        private Flux<ChatResponse> toolCallThenError(String toolName) {
            return Flux.just(toolCallChunk(toolName, "call_" + idSeq.incrementAndGet()))
                    .concatWith(
                            Flux.error(
                                    new SimpleModelHttpException(
                                            "OpenAI API error in streaming response: "
                                                    + "Invalid tool name: "
                                                    + toolName,
                                            400)));
        }

        @Override
        public String getModelName() {
            return "test-model-scripted-recovery";
        }

        int getCallCount() {
            return callCount.get();
        }
    }

    /**
     * Simple {@link ModelHttpException} implementation for testing.
     */
    private static class SimpleModelHttpException extends RuntimeException
            implements ModelHttpException {
        private final int statusCode;

        SimpleModelHttpException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        @Override
        public Integer getStatusCode() {
            return statusCode;
        }
    }

    // ==================== Helper methods ====================

    /**
     * Create a user message for testing.
     */
    private static Msg createUserMessage(String text) {
        return Msg.builder()
                .name("User")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    /** Build a tool-call chunk for the given tool name. */
    private static ChatResponse toolCallChunk(String toolName, String toolCallId) {
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID())
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(toolCallId)
                                        .input(Map.of())
                                        .content("{}")
                                        .build()))
                .usage(new ChatUsage(10, 5, 15))
                .build();
    }

    /** Build a plain text chunk. */
    private static ChatResponse textChunk(String text) {
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID())
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(10, 5, 15))
                .build();
    }

    // ==================== Test cases ====================

    @Nested
    @DisplayName("Recoverable streaming errors")
    class RecoverableErrorTests {

        @Test
        @DisplayName("Should recover when model emits tool call then throws tool-related error")
        void shouldRecoverFromToolErrorWithAccumulatedToolCalls() {
            // Model emits a tool call for "nonExistentTool", then throws a 400 error
            // with a message containing "tool"
            ToolCallThenErrorModel model =
                    new ToolCallThenErrorModel(
                            "nonExistentTool",
                            "OpenAI API error in streaming response: "
                                    + "Invalid tool name: nonExistentTool",
                            400);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent")
                            .sysPrompt("You are a helpful assistant.")
                            .model(model)
                            .build();

            // The agent should NOT throw. The acting phase will execute the
            // hallucinated tool, get "Tool not found", and loop back. The second
            // model call returns "Recovered successfully".
            Msg response =
                    agent.call(createUserMessage("Use the nonExistentTool")).block(TEST_TIMEOUT);

            assertNotNull(response, "Response should not be null after recovery");
            // The model was called at least twice: once that errored, once that recovered
            assertTrue(
                    model.getCallCount() >= 2,
                    "Model should have been called at least twice, was: " + model.getCallCount());
        }

        @Test
        @DisplayName("Should recover when error message contains 'function' keyword")
        void shouldRecoverWhenErrorContainsFunctionKeyword() {
            // Some providers use "function" instead of "tool" in error messages
            ToolCallThenErrorModel model =
                    new ToolCallThenErrorModel(
                            "fakeFunction",
                            "Invalid function name: fakeFunction is not defined",
                            400);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent-func")
                            .sysPrompt("You are a helpful assistant.")
                            .model(model)
                            .build();

            Msg response = agent.call(createUserMessage("Call fakeFunction")).block(TEST_TIMEOUT);

            assertNotNull(response, "Response should not be null after recovery");
            assertTrue(model.getCallCount() >= 2, "Model should have been called again");
        }
    }

    @Nested
    @DisplayName("Non-recoverable streaming errors")
    class NonRecoverableErrorTests {

        @Test
        @DisplayName("Should propagate error when exception is not ModelHttpException")
        void shouldPropagateNonModelHttpException() {
            RuntimeErrorModel model = new RuntimeErrorModel();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent-runtime")
                            .sysPrompt("You are a helpful assistant.")
                            .model(model)
                            .build();

            // RuntimeException is not a ModelHttpException, so it should propagate
            assertThrows(
                    RuntimeException.class,
                    () -> agent.call(createUserMessage("Hello")).block(TEST_TIMEOUT));
        }

        @Test
        @DisplayName("Should propagate error when ModelHttpException has retryable status (500)")
        void shouldPropagateRetryableHttpError() {
            // 500 is retryable, so even with a "tool" keyword in the message,
            // the error should propagate
            NonRecoverableErrorModel model =
                    new NonRecoverableErrorModel("Internal server tool error", 500);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent-500")
                            .sysPrompt("You are a helpful assistant.")
                            .model(model)
                            .build();

            assertThrows(
                    RuntimeException.class,
                    () -> agent.call(createUserMessage("Hello")).block(TEST_TIMEOUT));
        }

        @Test
        @DisplayName("Should propagate error when ModelHttpException has retryable status (429)")
        void shouldPropagateRateLimitError() {
            // 429 is rate limit (retryable), should propagate
            NonRecoverableErrorModel model =
                    new NonRecoverableErrorModel("Rate limit exceeded for tool call", 429);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent-429")
                            .sysPrompt("You are a helpful assistant.")
                            .model(model)
                            .build();

            assertThrows(
                    RuntimeException.class,
                    () -> agent.call(createUserMessage("Hello")).block(TEST_TIMEOUT));
        }

        @Test
        @DisplayName("Should propagate error when no tool calls accumulated before error")
        void shouldPropagateWhenNoToolCallsAccumulated() {
            // Error message contains "tool" but no tool calls were accumulated
            // before the error
            NonRecoverableErrorModel model =
                    new NonRecoverableErrorModel("Tool configuration error", 400);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent-no-tools")
                            .sysPrompt("You are a helpful assistant.")
                            .model(model)
                            .build();

            assertThrows(
                    RuntimeException.class,
                    () -> agent.call(createUserMessage("Hello")).block(TEST_TIMEOUT));
        }

        @Test
        @DisplayName("Should propagate error when ModelHttpException message has no tool keywords")
        void shouldPropagateNonToolRelatedError() {
            // 400 status (non-retryable) but message has no tool-related keywords
            NonRecoverableErrorModel model =
                    new NonRecoverableErrorModel("Bad request: invalid JSON", 400);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent-bad-request")
                            .sysPrompt("You are a helpful assistant.")
                            .model(model)
                            .build();

            assertThrows(
                    RuntimeException.class,
                    () -> agent.call(createUserMessage("Hello")).block(TEST_TIMEOUT));
        }
    }

    @Nested
    @DisplayName("Consecutive recovery budget (issue #3102 cap)")
    class RecoveryBudgetTests {

        @Test
        @DisplayName("Should propagate error after the consecutive recovery budget is exhausted")
        void shouldPropagateAfterRecoveryBudgetExhausted() {
            // The model always emits a tool call then a recoverable tool error: the
            // first 3 errors are recovered (default cap = 3), the 4th propagates.
            AlwaysToolCallThenErrorModel model = new AlwaysToolCallThenErrorModel();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent-cap")
                            .sysPrompt("You are a helpful assistant.")
                            .model(model)
                            .build();

            assertThrows(
                    RuntimeException.class,
                    () -> agent.call(createUserMessage("Hello")).block(TEST_TIMEOUT));
            // 3 recovered iterations + 1 failing iteration = 4 model calls
            assertEquals(4, model.getCallCount());
        }

        @Test
        @DisplayName("Should reset the recovery budget after a stream completes without recovery")
        void shouldResetRecoveryBudgetAfterSuccessfulStream() {
            // err, err, err, valid-tool-call, err, text. The second batch of errors is
            // only recoverable because the successful stream in between reset the
            // budget; without the reset the exhausted budget would propagate there.
            ScriptedRecoverySequenceModel model =
                    new ScriptedRecoverySequenceModel(TestConstants.TEST_TOOL_NAME);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent-budget-reset")
                            .sysPrompt("You are a helpful assistant.")
                            .model(model)
                            .toolkit(new MockToolkit())
                            .build();

            Msg response = agent.call(createUserMessage("Hello")).block(TEST_TIMEOUT);

            assertNotNull(response, "Response should not be null after budget reset");
            assertEquals(6, model.getCallCount(), "All 6 scripted model calls should happen");
            String text =
                    response.getContent().stream()
                            .filter(b -> b instanceof TextBlock)
                            .map(b -> ((TextBlock) b).getText())
                            .findFirst()
                            .orElse("");
            assertEquals("Done", text, "Turn should finish with the scripted final text");
        }

        @Test
        @DisplayName("Should honor a custom recovery budget configured via the builder")
        void shouldHonorCustomRecoveryBudget() {
            // With a cap of 1, the 2nd consecutive tool error must propagate.
            AlwaysToolCallThenErrorModel model = new AlwaysToolCallThenErrorModel();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("test-agent-cap-1")
                            .sysPrompt("You are a helpful assistant.")
                            .model(model)
                            .maxToolErrorRecoveries(1)
                            .build();

            assertThrows(
                    RuntimeException.class,
                    () -> agent.call(createUserMessage("Hello")).block(TEST_TIMEOUT));
            // 1 recovered iteration + 1 failing iteration = 2 model calls
            assertEquals(2, model.getCallCount());
        }
    }
}
