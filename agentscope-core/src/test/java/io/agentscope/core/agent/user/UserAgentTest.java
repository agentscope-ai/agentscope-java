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
package io.agentscope.core.agent.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Comprehensive unit tests for UserAgent class.
 *
 * <p>Test coverage includes:
 * <ul>
 *   <li>All constructor variants</li>
 *   <li>All call() method variations (with/without messages, structured output)</li>
 *   <li>Input method management (instance and class-level overrides)</li>
 *   <li>Message creation (text blocks, thinking blocks, empty content)</li>
 *   <li>Structured data handling</li>
 *   <li>Hook integration</li>
 *   <li>Observe pattern (no-op behavior)</li>
 *   <li>Interrupt handling</li>
 *   <li>Null parameter handling</li>
 * </ul>
 */
@DisplayName("UserAgent Comprehensive Tests")
class UserAgentTest {

    private Agent agent;
    private MockUserInput mockInput;
    private static UserInputBase originalDefaultInputMethod;

    /**
     * Mock implementation of UserInputBase for testing.
     * Supports configurable responses for text and structured input.
     */
    static class MockUserInput implements UserInputBase {
        private String responseText = "Mock user input";
        private Map<String, Object> structuredData = null;
        private List<Msg> receivedContextMessages = new ArrayList<>();

        public void setResponseText(String text) {
            this.responseText = text;
        }

        public void setStructuredData(Map<String, Object> data) {
            this.structuredData = data;
        }

        public List<Msg> getReceivedContextMessages() {
            return receivedContextMessages;
        }

        public void clearReceivedMessages() {
            receivedContextMessages.clear();
        }

        @Override
        public Mono<UserInputData> handleInput(
                String agentId,
                String agentName,
                List<Msg> contextMessages,
                Class<?> structuredModel) {
            // Record context messages for verification
            if (contextMessages != null) {
                receivedContextMessages.addAll(contextMessages);
            }

            UserInputData data =
                    new UserInputData(
                            List.of(TextBlock.builder().text(responseText).build()),
                            structuredModel != null ? structuredData : null);
            return Mono.just(data);
        }
    }

    @BeforeEach
    void setUp() {
        mockInput = new MockUserInput();
        agent =
                UserAgent.builder()
                        .name(TestConstants.TEST_USER_AGENT_NAME)
                        .inputMethod(mockInput)
                        .build();
    }

    @AfterEach
    void tearDown() {
        // Reset class-level default if it was changed
        if (originalDefaultInputMethod != null) {
            UserAgent.overrideClassInputMethod(originalDefaultInputMethod);
            originalDefaultInputMethod = null;
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should initialize with name only")
        void testConstructorWithNameOnly() {
            Agent simpleAgent = UserAgent.builder().name("SimpleAgent").build();

            assertNotNull(simpleAgent.getAgentId(), "Agent ID should not be null");
            assertEquals("SimpleAgent", simpleAgent.getName(), "Agent name should match");
            assertNotNull(
                    ((UserAgent) simpleAgent).getInputMethod(),
                    "Input method should be set to default");
        }

        @Test
        @DisplayName("Should initialize with name and hooks")
        void testConstructorWithNameAndHooks() {
            AtomicInteger preCallCount = new AtomicInteger(0);
            Hook testHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PreCallEvent) {
                                preCallCount.incrementAndGet();
                            }
                            return Mono.just(event);
                        }
                    };

            Agent agentWithHooks =
                    UserAgent.builder()
                            .name("AgentWithHooks")
                            .inputMethod(mockInput)
                            .hooks(List.of(testHook))
                            .build();

            assertNotNull(agentWithHooks.getAgentId());
            assertEquals("AgentWithHooks", agentWithHooks.getName());
            assertNotNull(((UserAgent) agentWithHooks).getInputMethod());

            // Verify hook is registered by calling agent
            agentWithHooks.call().block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
            assertEquals(1, preCallCount.get(), "Hook should be called once");
        }

        @Test
        @DisplayName("Should initialize with name and custom input method")
        void testConstructorWithCustomInputMethod() {
            MockUserInput customInput = new MockUserInput();
            customInput.setResponseText("Custom response");

            UserAgent customAgent =
                    UserAgent.builder().name("CustomAgent").inputMethod(customInput).build();

            assertEquals(customInput, customAgent.getInputMethod());

            Msg response =
                    ((Agent) customAgent)
                            .call()
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
            String text = TestUtils.extractTextContent(response);
            assertEquals("Custom response", text);
        }

        @Test
        @DisplayName("Should handle null input method by using default")
        void testConstructorWithNullInputMethod() {
            UserAgent agentWithNull = UserAgent.builder().name("Agent").inputMethod(null).build();

            assertNotNull(
                    agentWithNull.getInputMethod(),
                    "Should use default input method when null provided");
        }
    }

    @Nested
    @DisplayName("Call Method Tests")
    class CallMethodTests {

        @Test
        @DisplayName("Should handle call() with no arguments")
        void testCallNoArgs() {
            mockInput.setResponseText("No args input");

            Msg response =
                    agent.call().block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response);
            assertEquals(TestConstants.TEST_USER_AGENT_NAME, response.getName());
            assertEquals(MsgRole.USER, response.getRole());
            assertEquals("No args input", TestUtils.extractTextContent(response));
        }

        @Test
        @DisplayName("Should handle call(Msg msg) with message")
        void testCallWithSingleMessage() {
            mockInput.setResponseText("Response to message");

            Msg inputMsg =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hello user").build())
                            .build();

            Msg response =
                    agent.call(inputMsg)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response);
            assertEquals("Response to message", TestUtils.extractTextContent(response));

            // Verify context message was passed to input method
            List<Msg> receivedMessages = mockInput.getReceivedContextMessages();
            assertEquals(1, receivedMessages.size());
            assertEquals("Assistant", receivedMessages.get(0).getName());
        }

        @Test
        @DisplayName("Should handle call(Msg msg) with null message")
        void testCallWithNullMessage() {
            mockInput.setResponseText("Response to null");

            Msg response =
                    agent.call((Msg) null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response);
            assertEquals("Response to null", TestUtils.extractTextContent(response));

            // Should not pass any context messages
            List<Msg> receivedMessages = mockInput.getReceivedContextMessages();
            assertTrue(
                    receivedMessages.isEmpty(),
                    "Should not receive context messages when input is null");
        }

        @Test
        @DisplayName("Should handle call(List<Msg> msgs) with multiple messages")
        void testCallWithMultipleMessages() {
            mockInput.setResponseText("Response to multiple");

            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .name("Agent1")
                                    .role(MsgRole.ASSISTANT)
                                    .content(TextBlock.builder().text("Message 1").build())
                                    .build(),
                            Msg.builder()
                                    .name("Agent2")
                                    .role(MsgRole.ASSISTANT)
                                    .content(TextBlock.builder().text("Message 2").build())
                                    .build());

            Msg response =
                    agent.call(messages)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response);
            assertEquals("Response to multiple", TestUtils.extractTextContent(response));

            // Verify all context messages were passed
            List<Msg> receivedMessages = mockInput.getReceivedContextMessages();
            assertEquals(2, receivedMessages.size());
        }

        @Test
        @DisplayName("Should handle call(Class<?>) with structured model")
        void testCallWithStructuredModel() {
            mockInput.setResponseText("Structured input");
            Map<String, Object> structuredData = new HashMap<>();
            structuredData.put("field1", "value1");
            structuredData.put("field2", 42);
            mockInput.setStructuredData(structuredData);

            Msg response =
                    agent.call(TaskPlan.class)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response);
            assertTrue(response.hasStructuredData(), "Response should have structured data");
            assertEquals("value1", response.getMetadata().get("field1"));
            assertEquals(42, response.getMetadata().get("field2"));
        }

        @Test
        @DisplayName("Should handle call(Msg, Class<?>) with message and structured model")
        void testCallWithMessageAndStructuredModel() {
            mockInput.setResponseText("Structured response");
            Map<String, Object> structuredData = Map.of("answer", "yes");
            mockInput.setStructuredData(structuredData);

            Msg inputMsg =
                    Msg.builder()
                            .name("System")
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text("Question?").build())
                            .build();

            Msg response =
                    agent.call(inputMsg, TaskPlan.class)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response);
            assertTrue(response.hasStructuredData());
            assertEquals("yes", response.getMetadata().get("answer"));
        }

        @Test
        @DisplayName("Should handle call(List<Msg>, Class<?>) with messages and structured model")
        void testCallWithMessagesAndStructuredModel() {
            mockInput.setResponseText("Multi-message structured");
            Map<String, Object> structuredData = Map.of("status", "completed");
            mockInput.setStructuredData(structuredData);

            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .name("A")
                                    .role(MsgRole.ASSISTANT)
                                    .content(TextBlock.builder().text("Msg 1").build())
                                    .build());

            Msg response =
                    agent.call(messages, TaskPlan.class)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response);
            assertTrue(response.hasStructuredData());
            assertEquals("completed", response.getMetadata().get("status"));
        }
    }

    @Nested
    @DisplayName("Input Method Management Tests")
    class InputMethodTests {

        @Test
        @DisplayName("Should override instance input method")
        void testOverrideInstanceInputMethod() {
            MockUserInput newInput = new MockUserInput();
            newInput.setResponseText("New input method");

            ((UserAgent) agent).overrideInstanceInputMethod(newInput);

            assertEquals(newInput, ((UserAgent) agent).getInputMethod());

            Msg response =
                    agent.call().block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
            assertEquals("New input method", TestUtils.extractTextContent(response));
        }

        @Test
        @DisplayName("Should throw exception when overriding with null instance input")
        void testOverrideInstanceInputMethodWithNull() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ((UserAgent) agent).overrideInstanceInputMethod(null),
                    "Should throw exception when input method is null");
        }

        @Test
        @DisplayName("Should override class-level default input method")
        void testOverrideClassInputMethod() {
            // Save original for cleanup
            UserAgent tempAgent = UserAgent.builder().name("Temp").build();
            originalDefaultInputMethod = tempAgent.getInputMethod();

            MockUserInput newDefaultInput = new MockUserInput();
            newDefaultInput.setResponseText("New default");

            UserAgent.overrideClassInputMethod(newDefaultInput);

            // New agents should use the new default
            Agent newAgent = UserAgent.builder().name("NewAgent").build();
            Msg response =
                    newAgent.call().block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
            assertEquals("New default", TestUtils.extractTextContent(response));
        }

        @Test
        @DisplayName("Should throw exception when overriding with null class input")
        void testOverrideClassInputMethodWithNull() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> UserAgent.overrideClassInputMethod(null),
                    "Should throw exception when input method is null");
        }
    }

    @Nested
    @DisplayName("Message Creation Tests")
    class MessageCreationTests {

        @Test
        @DisplayName("Should create message with TextBlock content")
        void testMessageWithTextBlock() {
            mockInput.setResponseText("Text content");

            Msg response =
                    agent.call().block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response.getContent());
            assertEquals(1, response.getContent().size());
            assertTrue(response.getContent().get(0) instanceof TextBlock);
            assertEquals("Text content", ((TextBlock) response.getContent().get(0)).getText());
        }

        @Test
        @DisplayName("Should handle empty text content")
        void testMessageWithEmptyContent() {
            mockInput.setResponseText("");

            Msg response =
                    agent.call().block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response.getContent());
            assertFalse(response.getContent().isEmpty(), "Should have at least one block");
        }

        @Test
        @DisplayName("Should extract text from ThinkingBlock in context messages")
        void testContextMessageWithThinkingBlock() {
            mockInput.setResponseText("Response");

            Msg contextMsg =
                    Msg.builder()
                            .name("Thinker")
                            .role(MsgRole.ASSISTANT)
                            .content(ThinkingBlock.builder().thinking("Thinking content").build())
                            .build();

            agent.call(contextMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            // Verify context message was received (extraction happens in UserAgent.printMessage)
            List<Msg> receivedMessages = mockInput.getReceivedContextMessages();
            assertEquals(1, receivedMessages.size());
        }

        @Test
        @DisplayName("Should create message with metadata for structured input")
        void testMessageWithMetadata() {
            mockInput.setResponseText("Text");
            Map<String, Object> data = Map.of("key", "value");
            mockInput.setStructuredData(data);

            Msg response =
                    agent.call(TaskPlan.class)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response.getMetadata());
            assertEquals("value", response.getMetadata().get("key"));
        }

        @Test
        @DisplayName("Should create message without metadata when no structured input")
        void testMessageWithoutMetadata() {
            mockInput.setResponseText("Text only");

            Msg response =
                    agent.call().block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            // Metadata might be null or empty
            assertFalse(response.hasStructuredData());
        }
    }

    @Nested
    @DisplayName("Hook Integration Tests")
    class HookIntegrationTests {

        @Test
        @DisplayName("Should execute postCall hook after processing")
        void testPostCallHook() {
            AtomicInteger postCallCount = new AtomicInteger(0);

            Hook testHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PostCallEvent) {
                                postCallCount.incrementAndGet();
                            }
                            return Mono.just(event);
                        }
                    };

            Agent agentWithHook =
                    UserAgent.builder()
                            .name(TestConstants.TEST_USER_AGENT_NAME)
                            .inputMethod(mockInput)
                            .hooks(List.of(testHook))
                            .build();

            agentWithHook.call().block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertEquals(1, postCallCount.get(), "postCall should execute once");
        }

        @Test
        @DisplayName("Should allow postCall hook to modify message")
        void testPostCallHookModification() {
            Hook modifyingHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PostCallEvent) {
                                PostCallEvent e = (PostCallEvent) event;
                                Msg msg = e.getFinalMessage();
                                // Add metadata to the message
                                Map<String, Object> newMetadata = new HashMap<>();
                                if (msg.getMetadata() != null) {
                                    newMetadata.putAll(msg.getMetadata());
                                }
                                newMetadata.put("modified", true);
                                Msg modifiedMsg =
                                        Msg.builder()
                                                .name(msg.getName())
                                                .role(msg.getRole())
                                                .content(msg.getContent())
                                                .metadata(newMetadata)
                                                .build();
                                e.setFinalMessage(modifiedMsg);
                            }
                            return Mono.just(event);
                        }
                    };

            Agent agentWithHook =
                    UserAgent.builder()
                            .name(TestConstants.TEST_USER_AGENT_NAME)
                            .inputMethod(mockInput)
                            .hooks(List.of(modifyingHook))
                            .build();

            Msg response =
                    agentWithHook
                            .call()
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response.getMetadata());
            assertEquals(true, response.getMetadata().get("modified"));
        }
    }

    @Nested
    @DisplayName("Observe Pattern Tests")
    class ObservePatternTests {

        @Test
        @DisplayName("Should complete observe(Msg) without error (no-op)")
        void testObserveSingleMessage() {
            Msg observeMsg =
                    Msg.builder()
                            .name("Other")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Observe this").build())
                            .build();

            // Should complete successfully without doing anything
            agent.observe(observeMsg)
                    .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            // No exception means success
        }

        @Test
        @DisplayName("Should complete observe(List<Msg>) without error (no-op)")
        void testObserveMultipleMessages() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .name("A")
                                    .role(MsgRole.ASSISTANT)
                                    .content(TextBlock.builder().text("Msg 1").build())
                                    .build(),
                            Msg.builder()
                                    .name("B")
                                    .role(MsgRole.ASSISTANT)
                                    .content(TextBlock.builder().text("Msg 2").build())
                                    .build());

            agent.observe(messages).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            // No exception means success
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle getUserInput with null context messages")
        void testGetUserInputWithNullContext() {
            mockInput.setResponseText("Response");

            Msg response =
                    ((UserAgent) agent)
                            .getUserInput(null, null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response);
            assertEquals("Response", TestUtils.extractTextContent(response));
        }

        @Test
        @DisplayName("Should handle getUserInput with empty context messages")
        void testGetUserInputWithEmptyContext() {
            mockInput.setResponseText("Response");

            Msg response =
                    ((UserAgent) agent)
                            .getUserInput(List.of(), null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response);
            assertEquals("Response", TestUtils.extractTextContent(response));
        }

        @Test
        @DisplayName("Should handle null structured model parameter")
        void testCallWithNullStructuredModel() {
            mockInput.setResponseText("No structure");

            Msg response =
                    agent.call((Class<?>) null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response);
            assertFalse(response.hasStructuredData());
        }
    }

    /**
     * Dummy class for structured output testing.
     */
    static class TaskPlan {
        private String task;
        private int priority;

        public String getTask() {
            return task;
        }

        public void setTask(String task) {
            this.task = task;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }
    }
}
