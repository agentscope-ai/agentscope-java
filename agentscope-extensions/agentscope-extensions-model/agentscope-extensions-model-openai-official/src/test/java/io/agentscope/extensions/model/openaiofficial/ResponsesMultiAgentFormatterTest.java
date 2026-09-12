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
package io.agentscope.extensions.model.openaiofficial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseReasoningItem;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResponsesMultiAgentFormatter}.
 *
 * <p>Verifies message grouping (SYSTEM, TOOL_SEQUENCE, AGENT_CONVERSATION, BYPASS),
 * conversation merging with history tags, media handling, thinking/hint block
 * inclusion, tool sequence passthrough with filtering, bypass dispatch, and custom prompt.
 */
@DisplayName("ResponsesMultiAgentFormatter Unit Tests")
class ResponsesMultiAgentFormatterTest {

    private ResponsesMultiAgentFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new ResponsesMultiAgentFormatter();
    }

    private static TextBlock text(String t) {
        return TextBlock.builder().text(t).build();
    }

    private static Msg user(String name, ContentBlock... blocks) {
        return Msg.builder().role(MsgRole.USER).name(name).content(List.of(blocks)).build();
    }

    private static Msg user(ContentBlock... blocks) {
        return Msg.builder().role(MsgRole.USER).content(List.of(blocks)).build();
    }

    private static Msg assistant(String name, ContentBlock... blocks) {
        return Msg.builder().role(MsgRole.ASSISTANT).name(name).content(List.of(blocks)).build();
    }

    private static Msg assistant(ContentBlock... blocks) {
        return Msg.builder().role(MsgRole.ASSISTANT).content(List.of(blocks)).build();
    }

    private static Msg system(String content) {
        return Msg.builder().role(MsgRole.SYSTEM).content(text(content)).build();
    }

    private static Msg tool(ToolResultBlock trb) {
        return Msg.builder().role(MsgRole.TOOL).content(trb).build();
    }

    private static Msg bypassUser(ContentBlock... blocks) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(List.of(blocks))
                .metadata(Map.of(MessageMetadataKeys.BYPASS_MULTIAGENT_HISTORY_MERGE, true))
                .build();
    }

    private static Msg bypassAssistant(ContentBlock... blocks) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(blocks))
                .metadata(Map.of(MessageMetadataKeys.BYPASS_MULTIAGENT_HISTORY_MERGE, true))
                .build();
    }

    private static ToolUseBlock toolUse(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).input(Map.of()).build();
    }

    private static ToolResultBlock toolResult(String id, String name, String result) {
        return ToolResultBlock.builder().id(id).name(name).output(text(result)).build();
    }

    private static String extractAllText(List<ResponseInputItem> items) {
        StringBuilder sb = new StringBuilder();
        for (ResponseInputItem item : items) {
            sb.append(extractText(item));
        }
        return sb.toString();
    }

    private static String extractText(ResponseInputItem item) {
        if (!item.isEasyInputMessage()) {
            return "";
        }
        EasyInputMessage msg = item.asEasyInputMessage();
        EasyInputMessage.Content content = msg.content();
        if (content.isResponseInputMessageContentList()) {
            StringBuilder sb = new StringBuilder();
            for (ResponseInputContent part : content.asResponseInputMessageContentList()) {
                if (part.isInputText()) {
                    sb.append(part.asInputText().text());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private static long countRole(List<ResponseInputItem> items, EasyInputMessage.Role role) {
        return items.stream()
                .filter(ResponseInputItem::isEasyInputMessage)
                .map(ResponseInputItem::asEasyInputMessage)
                .filter(m -> m.role() == role)
                .count();
    }

    private static long countImages(List<ResponseInputItem> items) {
        return items.stream()
                .filter(ResponseInputItem::isEasyInputMessage)
                .map(ResponseInputItem::asEasyInputMessage)
                .filter(m -> m.content().isResponseInputMessageContentList())
                .flatMap(m -> m.content().asResponseInputMessageContentList().stream())
                .filter(ResponseInputContent::isInputImage)
                .count();
    }

    @Nested
    @DisplayName("System message handling")
    class SystemMessages {

        @Test
        @DisplayName("Single system message extracted as separate item")
        void singleSystemMessage() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(List.of(system("You are helpful")));
            assertEquals(1, result.size());
            assertTrue(result.get(0).isEasyInputMessage());
            assertEquals(EasyInputMessage.Role.SYSTEM, result.get(0).asEasyInputMessage().role());
            assertTrue(extractText(result.get(0)).contains("You are helpful"));
        }

        @Test
        @DisplayName("Two consecutive system messages each start a new group")
        void twoSystemMessagesSeparateGroups() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(system("System prompt A"), system("System prompt B")));
            assertEquals(2, result.size());
            assertEquals(EasyInputMessage.Role.SYSTEM, result.get(0).asEasyInputMessage().role());
            assertEquals(EasyInputMessage.Role.SYSTEM, result.get(1).asEasyInputMessage().role());
            assertTrue(extractText(result.get(0)).contains("System prompt A"));
            assertTrue(extractText(result.get(1)).contains("System prompt B"));
        }

        @Test
        @DisplayName("System message followed by conversation")
        void systemThenConversation() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    system("You are a translator"),
                                    user("Alice", text("Hello")),
                                    assistant("Bob", text("Hi there"))));
            assertEquals(2, result.size());
            assertEquals(EasyInputMessage.Role.SYSTEM, result.get(0).asEasyInputMessage().role());
            assertEquals(EasyInputMessage.Role.USER, result.get(1).asEasyInputMessage().role());
            String convText = extractText(result.get(1));
            assertTrue(convText.contains("<history>"));
            assertTrue(convText.contains("Hello"));
            assertTrue(convText.contains("Hi there"));
        }
    }

    @Nested
    @DisplayName("Agent conversation merging")
    class AgentConversation {

        @Test
        @DisplayName("Two-agent conversation merged into single user message")
        void twoAgentConversationMerged() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    user("Alice", text("Hello, Bob")),
                                    assistant("Bob", text("Hi Alice!")),
                                    user("Alice", text("How are you?"))));
            assertEquals(1, result.size());
            assertEquals(EasyInputMessage.Role.USER, result.get(0).asEasyInputMessage().role());
            String content = extractText(result.get(0));
            assertTrue(content.contains("# Conversation History"));
            assertTrue(content.contains("<history>"));
            assertTrue(content.contains("</history>"));
            assertTrue(content.contains("Alice: Hello, Bob"));
            assertTrue(content.contains("Bob: Hi Alice!"));
            assertTrue(content.contains("Alice: How are you?"));
        }

        @Test
        @DisplayName("Single message in conversation: no name prefix")
        void singleMessageNoPrefix() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(List.of(user("Alice", text("Hello"))));
            assertEquals(1, result.size());
            String content = extractText(result.get(0));
            assertFalse(content.contains("Alice:"));
            assertTrue(content.contains("Hello"));
            assertTrue(content.contains("<history>"));
        }

        @Test
        @DisplayName("Multiple agent conversation groups separated by tool sequence")
        void multipleConversationGroups() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    user("Alice", text("What's the weather?")),
                                    assistant("Bob", toolUse("call_1", "get_weather")),
                                    tool(toolResult("call_1", "get_weather", "Sunny")),
                                    assistant("Bob", text("It's sunny!")),
                                    user("Alice", text("Great, thanks!"))));
            assertNotNull(result);
            assertTrue(result.size() >= 2);
            String allText = extractAllText(result);
            assertTrue(allText.contains("<history>"));
            assertTrue(allText.contains("# Conversation History"));
        }

        @Test
        @DisplayName("Second agent conversation group omits history prompt")
        void secondGroupOmitsPrompt() {
            List<Msg> messages = new ArrayList<>();
            messages.add(user("Alice", text("Hello")));
            messages.add(assistant("Bob", text("Hi")));
            messages.add(assistant("Bob", toolUse("call_1", "search")));
            messages.add(tool(toolResult("call_1", "search", "result")));
            messages.add(assistant("Bob", text("Found it")));
            messages.add(user("Alice", text("Nice")));
            List<ResponseInputItem> result = formatter.formatHistory(messages);
            String allText = extractAllText(result);
            assertEquals(1, countOccurrences(allText, "# Conversation History"));
            assertTrue(countOccurrences(allText, "<history>") >= 2);
        }
    }

    @Nested
    @DisplayName("Tool sequence passthrough")
    class ToolSequence {

        @Test
        @DisplayName("Assistant tool use passed through as function call")
        void assistantToolUsePassedThrough() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    assistant(
                                            "Bob",
                                            text("Let me check"),
                                            toolUse("call_1", "get_weather"))));
            assertTrue(result.size() >= 1);
            assertTrue(result.stream().anyMatch(ResponseInputItem::isFunctionCall));
        }

        @Test
        @DisplayName("Tool result passed through as function call output")
        void toolResultPassedThrough() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(tool(toolResult("call_1", "get_weather", "Sunny, 25C"))));
            assertTrue(result.size() >= 1);
            assertTrue(result.stream().anyMatch(ResponseInputItem::isFunctionCallOutput));
        }

        @Test
        @DisplayName("Full tool sequence: assistant call + tool result + assistant response")
        void fullToolSequence() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    assistant("Bob", toolUse("call_1", "get_weather")),
                                    tool(toolResult("call_1", "get_weather", "Sunny")),
                                    assistant("Bob", text("The weather is sunny"))));
            assertNotNull(result);
            assertTrue(result.size() >= 2);
        }
    }

    @Nested
    @DisplayName("Bypass message handling")
    class Bypass {

        @Test
        @DisplayName("Bypass USER message mapped via mapMessage")
        void bypassUserMessage() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(List.of(bypassUser(text("Direct message"))));
            assertEquals(1, result.size());
            assertTrue(result.get(0).isEasyInputMessage());
            assertEquals(EasyInputMessage.Role.USER, result.get(0).asEasyInputMessage().role());
            assertTrue(extractText(result.get(0)).contains("Direct message"));
        }

        @Test
        @DisplayName("Bypass ASSISTANT message dispatched correctly via mapMessage")
        void bypassAssistantMessage() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(List.of(bypassAssistant(text("Assistant reply"))));
            assertEquals(1, result.size());
            assertTrue(result.get(0).isEasyInputMessage());
            assertEquals(
                    EasyInputMessage.Role.ASSISTANT, result.get(0).asEasyInputMessage().role());
            assertTrue(extractText(result.get(0)).contains("Assistant reply"));
        }

        @Test
        @DisplayName("Bypass message between conversation groups is not merged")
        void bypassBetweenConversations() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    user("Alice", text("Hello")),
                                    bypassUser(text("Important context")),
                                    assistant("Bob", text("Hi"))));
            assertNotNull(result);
            assertTrue(result.size() >= 2);
            assertTrue(extractAllText(result).contains("Important context"));
        }
    }

    @Nested
    @DisplayName("Media handling in merged conversation")
    class MediaHandling {

        @Test
        @DisplayName("URL image in conversation produces image part")
        void urlImageInConversation() {
            String imageUrl = "https://example.com/image.png";
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    user(
                                            "Alice",
                                            text("Look at this"),
                                            ImageBlock.builder()
                                                    .source(
                                                            URLSource.builder()
                                                                    .url(imageUrl)
                                                                    .build())
                                                    .build()),
                                    assistant("Bob", text("Nice image!"))));
            assertEquals(1, result.size());
            assertEquals(1, countImages(result));
            String content = extractText(result.get(0));
            assertTrue(content.contains("Look at this"));
            assertTrue(content.contains("Nice image!"));
        }

        @Test
        @DisplayName("DataBlock image URL produces image part")
        void dataBlockImageUrl() {
            String imageUrl = "https://example.com/data.png";
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    user(
                                            "Alice",
                                            text("Check this data"),
                                            DataBlock.builder()
                                                    .source(
                                                            URLSource.builder()
                                                                    .url(imageUrl)
                                                                    .build())
                                                    .build())));
            assertEquals(1, result.size());
            assertEquals(1, countImages(result));
        }

        @Test
        @DisplayName("Non-image DataBlock produces error text fallback")
        void nonImageDataBlockFallback() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    user(
                                            "Alice",
                                            text("Before"),
                                            DataBlock.builder()
                                                    .source(
                                                            Base64Source.builder()
                                                                    .mediaType("audio/wav")
                                                                    .data("dGVzdA==")
                                                                    .build())
                                                    .build(),
                                            text("After"))));
            assertEquals(1, result.size());
            String content = extractText(result.get(0));
            assertTrue(content.contains("Before"));
            assertTrue(content.contains("After"));
            assertTrue(content.contains("[Data - processing failed]"));
        }

        @Test
        @DisplayName("Multiple images flush text buffer correctly")
        void multipleImagesFlushText() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    user(
                                            "Alice",
                                            text("Two images:"),
                                            ImageBlock.builder()
                                                    .source(
                                                            URLSource.builder()
                                                                    .url(
                                                                            "https://example.com/img1.png")
                                                                    .build())
                                                    .build(),
                                            text("and"),
                                            ImageBlock.builder()
                                                    .source(
                                                            URLSource.builder()
                                                                    .url(
                                                                            "https://example.com/img2.png")
                                                                    .build())
                                                    .build())));
            assertEquals(1, result.size());
            assertEquals(2, countImages(result));
            String content = extractText(result.get(0));
            assertTrue(content.contains("Two images:"));
            assertTrue(content.contains("and"));
        }
    }

    @Nested
    @DisplayName("Thinking and hint blocks in merged conversation")
    class ThinkingAndHint {

        @Test
        @DisplayName("ThinkingBlock text included in merged history")
        void thinkingBlockIncluded() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    user("Alice", text("What is 2+2?")),
                                    assistant(
                                            "Bob",
                                            ThinkingBlock.builder()
                                                    .thinking("Let me calculate")
                                                    .build(),
                                            text("The answer is 4"))));
            assertEquals(1, result.size());
            String content = extractText(result.get(0));
            assertTrue(content.contains("[Thinking]: Let me calculate"));
            assertTrue(content.contains("The answer is 4"));
        }

        @Test
        @DisplayName("Empty thinking block produces no output")
        void emptyThinkingBlock() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    user("Alice", text("Hello")),
                                    assistant(
                                            "Bob",
                                            ThinkingBlock.builder().thinking("").build(),
                                            text("Hi"))));
            assertEquals(1, result.size());
            String content = extractText(result.get(0));
            assertFalse(content.contains("[Thinking]"));
            assertTrue(content.contains("Hi"));
        }

        @Test
        @DisplayName("HintBlock text included in merged history")
        void hintBlockIncluded() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    user("Alice", text("Hello")),
                                    assistant(
                                            "Bob",
                                            new HintBlock("hint1", "Greeting received"),
                                            text("Hi there"))));
            assertEquals(1, result.size());
            String content = extractText(result.get(0));
            assertTrue(content.contains("Greeting received"));
            assertTrue(content.contains("Hi there"));
        }
    }

    @Nested
    @DisplayName("Custom conversation history prompt")
    class CustomPrompt {

        @Test
        @DisplayName("Custom prompt used in first conversation group")
        void customPromptUsed() {
            ResponsesMultiAgentFormatter customFormatter =
                    new ResponsesMultiAgentFormatter("## Chat History\n");
            List<ResponseInputItem> result =
                    customFormatter.formatHistory(
                            List.of(user("Alice", text("Hello")), assistant("Bob", text("Hi"))));
            assertEquals(1, result.size());
            String content = extractText(result.get(0));
            assertTrue(content.contains("## Chat History"));
            assertFalse(content.contains("# Conversation History"));
        }

        @Test
        @DisplayName("Custom prompt appears only once across multiple groups")
        void customPromptOncePerFormat() {
            ResponsesMultiAgentFormatter customFormatter =
                    new ResponsesMultiAgentFormatter("[History]\n");
            List<Msg> messages = new ArrayList<>();
            messages.add(user("Alice", text("Hello")));
            messages.add(assistant("Bob", text("Hi")));
            messages.add(assistant("Bob", toolUse("call_1", "search")));
            messages.add(tool(toolResult("call_1", "search", "found")));
            messages.add(assistant("Bob", text("Done")));
            messages.add(user("Alice", text("Thanks")));
            List<ResponseInputItem> result = customFormatter.formatHistory(messages);
            assertEquals(1, countOccurrences(extractAllText(result), "[History]"));
        }
    }

    @Nested
    @DisplayName("Mixed message scenarios")
    class MixedScenarios {

        @Test
        @DisplayName("System + conversation + tools + conversation")
        void fullMixedScenario() {
            List<Msg> messages = new ArrayList<>();
            messages.add(system("You are a helpful assistant"));
            messages.add(user("Alice", text("What's the weather?")));
            messages.add(assistant("Bob", toolUse("call_1", "get_weather")));
            messages.add(tool(toolResult("call_1", "get_weather", "Sunny")));
            messages.add(assistant("Bob", text("It's sunny today")));
            messages.add(user("Alice", text("Great! What about tomorrow?")));
            List<ResponseInputItem> result = formatter.formatHistory(messages);
            assertNotNull(result);
            assertTrue(result.size() >= 3);
            assertTrue(result.get(0).isEasyInputMessage());
            assertEquals(EasyInputMessage.Role.SYSTEM, result.get(0).asEasyInputMessage().role());
            assertTrue(result.stream().anyMatch(ResponseInputItem::isFunctionCall));
            assertTrue(result.stream().anyMatch(ResponseInputItem::isFunctionCallOutput));
            assertEquals(2, countRole(result, EasyInputMessage.Role.USER));
            String allText = extractAllText(result);
            assertTrue(allText.contains("What's the weather?"));
            assertTrue(allText.contains("It's sunny today"));
            assertTrue(allText.contains("Great! What about tomorrow?"));
        }

        @Test
        @DisplayName("Conversation before and after tool sequence")
        void conversationBeforeAndAfterTools() {
            List<Msg> messages = new ArrayList<>();
            messages.add(user("Alice", text("Search for cats")));
            messages.add(assistant("Bob", toolUse("call_1", "search")));
            messages.add(tool(toolResult("call_1", "search", "Found cats")));
            messages.add(assistant("Bob", text("I found some cats!")));
            messages.add(user("Alice", text("Show me dogs too")));
            List<ResponseInputItem> result = formatter.formatHistory(messages);
            assertNotNull(result);
            assertTrue(result.size() >= 2);
            assertTrue(result.stream().anyMatch(ResponseInputItem::isFunctionCall));
            assertTrue(result.stream().anyMatch(ResponseInputItem::isFunctionCallOutput));
            String allText = extractAllText(result);
            assertTrue(allText.contains("Search for cats"));
            assertTrue(allText.contains("I found some cats!"));
            assertTrue(allText.contains("Show me dogs too"));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty message list produces empty result")
        void emptyMessageList() {
            List<ResponseInputItem> result = formatter.formatHistory(List.of());
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Single assistant text message produces merged history")
        void singleAssistantMessage() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(List.of(assistant("Bob", text("Hello world"))));
            assertEquals(1, result.size());
            assertTrue(result.get(0).isEasyInputMessage());
            assertEquals(EasyInputMessage.Role.USER, result.get(0).asEasyInputMessage().role());
            String content = extractText(result.get(0));
            assertTrue(content.contains("Hello world"));
            assertTrue(content.contains("<history>"));
        }

        @Test
        @DisplayName("Messages without names use no prefix in multi-turn")
        void messagesWithoutNames() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .content(text("Question"))
                                            .build(),
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .content(text("Answer"))
                                            .build()));
            assertEquals(1, result.size());
            String content = extractText(result.get(0));
            assertTrue(content.contains("Question"));
            assertTrue(content.contains("Answer"));
        }

        @Test
        @DisplayName("Tool role messages always classified as TOOL_SEQUENCE")
        void toolRoleAlwaysToolSequence() {
            List<ResponseInputItem> result =
                    formatter.formatHistory(
                            List.of(
                                    assistant("Bob", text("Let me search")),
                                    tool(toolResult("call_1", "search", "result")),
                                    tool(toolResult("call_2", "search", "result2"))));
            assertEquals(
                    2, result.stream().filter(ResponseInputItem::isFunctionCallOutput).count());
        }
    }

    @Nested
    @DisplayName("Encrypted reasoning replay")
    class EncryptedReasoningReplay {

        @Test
        @DisplayName(
                "Assistant with encrypted reasoning is passed through, not merged into history")
        void encryptedReasoningNotMergedIntoHistory() {
            Msg user1 = user("Alice", text("What is 2+2?"));
            Msg assistant1 =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .name("Bob")
                            .content(
                                    List.of(
                                            ThinkingBlock.builder()
                                                    .thinking("Calculating...")
                                                    .metadata(
                                                            Map.of(
                                                                    OpenAIOfficialConstants
                                                                            .MD_REASONING_ENCRYPTED_CONTENT,
                                                                    "enc123"))
                                                    .build(),
                                            text("4")))
                            .build();
            Msg user2 = user("Alice", text("And 3+3?"));

            List<ResponseInputItem> result =
                    formatter.formatHistory(List.of(user1, assistant1, user2));

            // A reasoning item must be present with the encrypted content
            ResponseReasoningItem reasoning = null;
            for (ResponseInputItem item : result) {
                if (item.isReasoning()) {
                    reasoning = item.asReasoning();
                }
            }
            assertNotNull(reasoning, "Expected a reasoning replay item");
            assertEquals("enc123", reasoning.encryptedContent().orElseThrow());

            // The assistant text must NOT be inside a merged user history message
            long userMsgCount = countRole(result, EasyInputMessage.Role.USER);
            long assistantMsgCount = countRole(result, EasyInputMessage.Role.ASSISTANT);
            assertTrue(assistantMsgCount >= 1, "Assistant message should be preserved as-is");
            // The assistant text "4" must be in an assistant message, not inside a
            // merged user <history> block
            boolean assistantHasText4 = false;
            for (ResponseInputItem item : result) {
                if (item.isEasyInputMessage()) {
                    EasyInputMessage m = item.asEasyInputMessage();
                    if (m.role() == EasyInputMessage.Role.ASSISTANT
                            && extractText(item).contains("4")) {
                        assistantHasText4 = true;
                    }
                }
            }
            assertTrue(assistantHasText4, "Assistant text '4' should be in an assistant message");
        }

        @Test
        @DisplayName("Assistant without encrypted reasoning is merged into history as before")
        void plainAssistantStillMerged() {
            Msg user1 = user("Alice", text("Hi"));
            Msg assistant1 = assistant("Bob", text("Hello!"));
            Msg user2 = user("Alice", text("Bye"));

            List<ResponseInputItem> result =
                    formatter.formatHistory(List.of(user1, assistant1, user2));

            // No reasoning item should be emitted
            boolean hasReasoning = result.stream().anyMatch(ResponseInputItem::isReasoning);
            assertFalse(hasReasoning, "No reasoning item for plain assistant message");

            // Messages should be merged into a single user history message
            String allText = extractAllText(result);
            assertTrue(allText.contains("<history>"));
            assertTrue(allText.contains("Hello!"));
        }
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
