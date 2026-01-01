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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive unit tests for StreamUserInput class.
 *
 * <p>Test coverage includes:
 * <ul>
 *   <li>All constructor variants</li>
 *   <li>Text input handling</li>
 *   <li>Context message printing</li>
 *   <li>Structured input parsing</li>
 *   <li>Custom input/output streams</li>
 *   <li>Edge cases (null input, empty input, malformed structured data)</li>
 * </ul>
 */
@DisplayName("StreamUserInput Comprehensive Tests")
class StreamUserInputTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should initialize with default constructor")
        void testDefaultConstructor() {
            StreamUserInput input = StreamUserInput.builder().build();
            assertNotNull(input, "StreamUserInput should be created");
        }

        @Test
        @DisplayName("Should initialize with custom input hint")
        void testConstructorWithCustomHint() {
            StreamUserInput input = StreamUserInput.builder().inputHint("Enter: ").build();
            assertNotNull(input, "StreamUserInput should be created with custom hint");
        }

        @Test
        @DisplayName("Should initialize with custom streams")
        void testConstructorWithCustomStreams() {
            ByteArrayInputStream in = new ByteArrayInputStream("test input\n".getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Prompt: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();
            assertNotNull(input, "StreamUserInput should be created with custom streams");
        }
    }

    @Nested
    @DisplayName("Text Input Handling Tests")
    class TextInputTests {

        @Test
        @DisplayName("Should handle simple text input")
        void testSimpleTextInput() {
            String inputText = "Hello, this is user input\n";
            ByteArrayInputStream in = new ByteArrayInputStream(inputText.getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(result);
            assertNotNull(result.getBlocksInput());
            assertEquals(1, result.getBlocksInput().size());
            assertTrue(result.getBlocksInput().get(0) instanceof TextBlock);

            String receivedText = ((TextBlock) result.getBlocksInput().get(0)).getText();
            assertEquals("Hello, this is user input", receivedText);
        }

        @Test
        @DisplayName("Should handle empty text input")
        void testEmptyTextInput() {
            ByteArrayInputStream in = new ByteArrayInputStream("\n".getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(result);
            assertNotNull(result.getBlocksInput());
            assertEquals(1, result.getBlocksInput().size());

            String receivedText = ((TextBlock) result.getBlocksInput().get(0)).getText();
            assertEquals("", receivedText);
        }

        @Test
        @DisplayName("Should handle multiline-like text (single line with escaped chars)")
        void testTextWithSpecialCharacters() {
            String inputText = "Line 1\\nLine 2\\tTabbed\n";
            ByteArrayInputStream in = new ByteArrayInputStream(inputText.getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            String receivedText = ((TextBlock) result.getBlocksInput().get(0)).getText();
            assertTrue(receivedText.contains("\\n"));
            assertTrue(receivedText.contains("\\t"));
        }
    }

    @Nested
    @DisplayName("Context Message Printing Tests")
    class ContextMessageTests {

        @Test
        @DisplayName("Should print context message before prompting")
        void testPrintContextMessage() {
            ByteArrayInputStream in = new ByteArrayInputStream("user response\n".getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            Msg contextMsg =
                    Msg.builder()
                            .name("Assistant")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hello user").build())
                            .build();

            input.handleInput("agent-id", "TestAgent", List.of(contextMsg), null)
                    .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            String output = out.toString();
            assertTrue(output.contains("Assistant"));
            assertTrue(output.contains("ASSISTANT"));
            assertTrue(output.contains("Hello user"));
            assertTrue(output.contains("Input: "));
        }

        @Test
        @DisplayName("Should print multiple context messages")
        void testPrintMultipleContextMessages() {
            ByteArrayInputStream in = new ByteArrayInputStream("response\n".getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint(">>> ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            List<Msg> contextMessages =
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

            input.handleInput("agent-id", "TestAgent", contextMessages, null)
                    .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            String output = out.toString();
            assertTrue(output.contains("Agent1"));
            assertTrue(output.contains("Message 1"));
            assertTrue(output.contains("Agent2"));
            assertTrue(output.contains("Message 2"));
        }

        @Test
        @DisplayName("Should handle null context messages")
        void testNullContextMessages() {
            ByteArrayInputStream in = new ByteArrayInputStream("input\n".getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(result);
            String output = out.toString();
            // Should only contain prompt, no context messages
            assertTrue(output.contains("Input: "));
        }

        @Test
        @DisplayName("Should handle empty context messages list")
        void testEmptyContextMessages() {
            ByteArrayInputStream in = new ByteArrayInputStream("input\n".getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", List.of(), null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(result);
            String output = out.toString();
            assertTrue(output.contains("Input: "));
        }
    }

    @Nested
    @DisplayName("Structured Input Tests")
    class StructuredInputTests {

        @Test
        @DisplayName("Should parse structured input with key=value pairs")
        void testStructuredInputParsing() {
            String inputText = "simple text\nkey1=value1,key2=value2\n";
            ByteArrayInputStream in = new ByteArrayInputStream(inputText.getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, Map.class)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(result);
            assertNotNull(result.getStructuredInput());
            assertEquals("value1", result.getStructuredInput().get("key1"));
            assertEquals("value2", result.getStructuredInput().get("key2"));
        }

        @Test
        @DisplayName("Should handle empty structured input")
        void testEmptyStructuredInput() {
            String inputText = "text\n\n";
            ByteArrayInputStream in = new ByteArrayInputStream(inputText.getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, Map.class)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(result);
            assertNotNull(result.getStructuredInput());
            assertTrue(result.getStructuredInput().isEmpty());
        }

        @Test
        @DisplayName("Should handle malformed structured input gracefully")
        void testMalformedStructuredInput() {
            String inputText = "text\ninvalid-data-no-equals\n";
            ByteArrayInputStream in = new ByteArrayInputStream(inputText.getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, Map.class)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(result);
            // Should return empty map for malformed input
            assertTrue(
                    result.getStructuredInput() == null || result.getStructuredInput().isEmpty());
        }

        @Test
        @DisplayName("Should handle structured input with spaces")
        void testStructuredInputWithSpaces() {
            String inputText = "text\n  key1 = value1 , key2 = value2  \n";
            ByteArrayInputStream in = new ByteArrayInputStream(inputText.getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, Map.class)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(result);
            assertNotNull(result.getStructuredInput());
            // Spaces should be trimmed
            assertEquals("value1", result.getStructuredInput().get("key1"));
            assertEquals("value2", result.getStructuredInput().get("key2"));
        }

        @Test
        @DisplayName("Should not request structured input when model is null")
        void testNoStructuredInputWhenModelIsNull() {
            String inputText = "simple input\n";
            ByteArrayInputStream in = new ByteArrayInputStream(inputText.getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(result);
            assertTrue(
                    result.getStructuredInput() == null || result.getStructuredInput().isEmpty());

            String output = out.toString();
            // Should not contain structured input prompt
            assertFalse(output.contains("Structured input"));
        }

        @Test
        @DisplayName("Should print structured input prompt when model is provided")
        void testStructuredInputPrompt() {
            String inputText = "text\nkey=value\n";
            ByteArrayInputStream in = new ByteArrayInputStream(inputText.getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            input.handleInput("agent-id", "TestAgent", null, Map.class)
                    .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            String output = out.toString();
            assertTrue(output.contains("Structured input"));
            assertTrue(output.contains("key=value"));
        }
    }

    @Nested
    @DisplayName("Custom Stream Tests")
    class CustomStreamTests {

        @Test
        @DisplayName("Should use custom input stream")
        void testCustomInputStream() {
            String customInput = "Custom input from stream\n";
            ByteArrayInputStream in = new ByteArrayInputStream(customInput.getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint(">>> ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            String receivedText = ((TextBlock) result.getBlocksInput().get(0)).getText();
            assertEquals("Custom input from stream", receivedText);
        }

        @Test
        @DisplayName("Should use custom output stream")
        void testCustomOutputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream("input\n".getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("CustomPrompt> ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            Msg contextMsg =
                    Msg.builder()
                            .name("System")
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text("Context message").build())
                            .build();

            input.handleInput("agent-id", "TestAgent", List.of(contextMsg), null)
                    .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            String output = out.toString();
            assertTrue(output.contains("System"));
            assertTrue(output.contains("Context message"));
            assertTrue(output.contains("CustomPrompt> "));
        }

        @Test
        @DisplayName("Should handle output stream for error messages")
        void testErrorOutputToCustomStream() {
            // Simulate IOException by closing the stream prematurely
            String inputText = "text\nkey=value\n";
            ByteArrayInputStream in = new ByteArrayInputStream(inputText.getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            // This should complete without throwing exception
            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, Map.class)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle null input text")
        void testNullInputText() {
            // Simulate EOF by providing empty stream
            ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, null)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(result);
            // Should default to empty string
            String receivedText = ((TextBlock) result.getBlocksInput().get(0)).getText();
            assertEquals("", receivedText);
        }

        @Test
        @DisplayName("Should handle context message without name")
        void testContextMessageWithoutName() {
            ByteArrayInputStream in = new ByteArrayInputStream("response\n".getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            Msg contextMsg =
                    Msg.builder()
                            .name("")
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Message without name").build())
                            .build();

            input.handleInput("agent-id", "TestAgent", List.of(contextMsg), null)
                    .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            String output = out.toString();
            // Should still print the message text
            assertTrue(output.contains("Message without name"));
        }

        @Test
        @DisplayName("Should handle context message with null role")
        void testContextMessageWithNullRole() {
            ByteArrayInputStream in = new ByteArrayInputStream("response\n".getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            Msg contextMsg =
                    Msg.builder()
                            .name("Agent")
                            .role(null)
                            .content(TextBlock.builder().text("Message").build())
                            .build();

            input.handleInput("agent-id", "TestAgent", List.of(contextMsg), null)
                    .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            String output = out.toString();
            assertTrue(output.contains("Agent"));
            assertTrue(output.contains("Message"));
        }

        @Test
        @DisplayName("Should handle structured input with equals in value")
        void testStructuredInputWithEqualsInValue() {
            String inputText = "text\nurl=http://example.com?param=value\n";
            ByteArrayInputStream in = new ByteArrayInputStream(inputText.getBytes());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            StreamUserInput input =
                    StreamUserInput.builder()
                            .inputHint("Input: ")
                            .inputStream(in)
                            .outputStream(out)
                            .build();

            UserInputData result =
                    input.handleInput("agent-id", "TestAgent", null, Map.class)
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(result);
            assertNotNull(result.getStructuredInput());
            // Should preserve everything after first '='
            assertEquals("http://example.com?param=value", result.getStructuredInput().get("url"));
        }
    }
}
