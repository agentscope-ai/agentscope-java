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
package io.agentscope.extensions.model.anthropic.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for prompt caching (cache_control) support in the Anthropic formatter.
 */
class AnthropicCacheControlTest {

    private AnthropicChatFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new AnthropicChatFormatter();
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static boolean lastBlockHasCacheControl(MessageParam param) {
        List<ContentBlockParam> blocks = param.content().asBlockParams();
        ContentBlockParam last = blocks.get(blocks.size() - 1);
        if (last.isText()) {
            return last.asText().cacheControl().isPresent();
        }
        if (last.isToolResult()) {
            return last.asToolResult().cacheControl().isPresent();
        }
        if (last.isToolUse()) {
            return last.asToolUse().cacheControl().isPresent();
        }
        return false;
    }

    private static MessageCreateParams.Builder createBuilder() {
        return MessageCreateParams.builder()
                .model("claude-sonnet-4-5-20250929")
                .maxTokens(1024)
                .addMessage(
                        MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content("test")
                                .build());
    }

    @Nested
    @DisplayName("applyCacheControl - automatic strategy")
    class ApplyCacheControlTest {

        @Test
        @DisplayName("should add cache_control to the last message only")
        void lastMessageOnly() {
            List<MessageParam> formatted =
                    formatter.format(
                            List.of(
                                    userMsg("First"),
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .textContent("Answer")
                                            .build(),
                                    userMsg("Second")));

            List<MessageParam> result = formatter.applyCacheControl(formatted);

            assertEquals(3, result.size());
            assertFalse(lastBlockHasCacheControl(result.get(0)));
            assertFalse(lastBlockHasCacheControl(result.get(1)));
            assertTrue(lastBlockHasCacheControl(result.get(2)));
        }

        @Test
        @DisplayName("should handle string content by wrapping into a text block")
        void stringContent() {
            MessageParam stringParam =
                    MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content("plain string content")
                            .build();

            List<MessageParam> result = formatter.applyCacheControl(List.of(stringParam));

            MessageParam marked = result.get(0);
            assertTrue(marked.content().isBlockParams());
            assertTrue(lastBlockHasCacheControl(marked));
            assertEquals(
                    "plain string content",
                    marked.content().asBlockParams().get(0).asText().text());
        }

        @Test
        @DisplayName("should handle empty message list")
        void emptyList() {
            List<MessageParam> result = formatter.applyCacheControl(List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should mark image block when it is the last block")
        void imageBlockMarked() {
            MessageParam imageParam =
                    MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(
                                    MessageParam.Content.ofBlockParams(
                                            List.of(
                                                    ContentBlockParam.ofImage(
                                                            ImageBlockParam.builder()
                                                                    .urlSource(
                                                                            "https://example.com/a.png")
                                                                    .build()))))
                            .build();

            List<MessageParam> result = formatter.applyCacheControl(List.of(imageParam));

            ContentBlockParam block = result.get(0).content().asBlockParams().get(0);
            assertTrue(block.asImage().cacheControl().isPresent());
        }

        @Test
        @DisplayName("should mark tool_use block when it is the last block")
        void toolUseBlockMarked() {
            MessageParam toolUseParam =
                    MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .content(
                                    MessageParam.Content.ofBlockParams(
                                            List.of(
                                                    ContentBlockParam.ofToolUse(
                                                            ToolUseBlockParam.builder()
                                                                    .id("call_1")
                                                                    .name("search")
                                                                    .input(JsonValue.from(Map.of()))
                                                                    .build()))))
                            .build();

            List<MessageParam> result = formatter.applyCacheControl(List.of(toolUseParam));

            ContentBlockParam block = result.get(0).content().asBlockParams().get(0);
            assertTrue(block.asToolUse().cacheControl().isPresent());
        }

        @Test
        @DisplayName("should mark tool_result block when it is the last block")
        void toolResultBlockMarked() {
            Msg toolMsg =
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(
                                    List.of(
                                            ToolResultBlock.builder()
                                                    .id("call_1")
                                                    .name("search")
                                                    .output(
                                                            TextBlock.builder()
                                                                    .text("result data")
                                                                    .build())
                                                    .build()))
                            .build();

            List<MessageParam> result =
                    formatter.applyCacheControl(formatter.format(List.of(toolMsg)));

            ContentBlockParam block = result.get(0).content().asBlockParams().get(0);
            assertTrue(block.asToolResult().cacheControl().isPresent());
        }

        @Test
        @DisplayName("should skip trailing non-cacheable block and mark the previous one")
        void skipsTrailingNonCacheableBlock() {
            MessageParam param =
                    MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .content(
                                    MessageParam.Content.ofBlockParams(
                                            List.of(
                                                    ContentBlockParam.ofText(
                                                            TextBlockParam.builder()
                                                                    .text("Answer")
                                                                    .build()),
                                                    ContentBlockParam.ofThinking(
                                                            ThinkingBlockParam.builder()
                                                                    .thinking("reasoning...")
                                                                    .signature("sig")
                                                                    .build()))))
                            .build();

            List<MessageParam> result = formatter.applyCacheControl(List.of(param));

            List<ContentBlockParam> blocks = result.get(0).content().asBlockParams();
            // Thinking block (last) cannot carry cache_control; the text block before it is marked
            assertTrue(blocks.get(0).asText().cacheControl().isPresent());
            assertTrue(blocks.get(1).isThinking());
        }

        @Test
        @DisplayName("should leave message unchanged when no block supports cache_control")
        void noCacheableBlock() {
            MessageParam thinkingOnly =
                    MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .content(
                                    MessageParam.Content.ofBlockParams(
                                            List.of(
                                                    ContentBlockParam.ofThinking(
                                                            ThinkingBlockParam.builder()
                                                                    .thinking("reasoning...")
                                                                    .signature("sig")
                                                                    .build()))))
                            .build();

            List<MessageParam> messages = List.of(thinkingOnly);
            List<MessageParam> result = formatter.applyCacheControl(messages);

            // Same list returned; the thinking block stays unmarked
            assertTrue(
                    result.get(0)
                            .content()
                            .asBlockParams()
                            .get(0)
                            .asThinking()
                            .thinking()
                            .equals("reasoning..."));
            assertEquals(messages, result);
        }
    }

    @Nested
    @DisplayName("applySystemMessage with cache control")
    class SystemMessageTest {

        private static Msg systemMsg() {
            return Msg.builder().role(MsgRole.SYSTEM).textContent("You are helpful.").build();
        }

        @Test
        @DisplayName("should send system as cached text block when enabled")
        void systemCachedWhenEnabled() {
            MessageCreateParams.Builder builder = createBuilder();

            formatter.applySystemMessage(builder, List.of(systemMsg()), true);

            MessageCreateParams params = builder.build();
            assertTrue(params.system().isPresent());
            assertTrue(params.system().get().isTextBlockParams());
            List<TextBlockParam> blocks = params.system().get().asTextBlockParams();
            assertEquals(1, blocks.size());
            assertEquals("You are helpful.", blocks.get(0).text());
            assertTrue(blocks.get(0).cacheControl().isPresent());
        }

        @Test
        @DisplayName("should send system as plain string when disabled")
        void systemPlainWhenDisabled() {
            MessageCreateParams.Builder builder = createBuilder();

            formatter.applySystemMessage(builder, List.of(systemMsg()), false);

            MessageCreateParams params = builder.build();
            assertTrue(params.system().isPresent());
            assertTrue(params.system().get().isString());
            assertEquals("You are helpful.", params.system().get().asString());
        }
    }

    @Nested
    @DisplayName("applyTools with cache control")
    class ToolsCacheControlTest {

        private static ToolSchema toolSchema(String name) {
            return ToolSchema.builder()
                    .name(name)
                    .description("Tool " + name)
                    .parameters(Map.of("type", "object"))
                    .build();
        }

        @Test
        @DisplayName("should mark only the last tool definition when enabled")
        void lastToolMarkedWhenEnabled() {
            MessageCreateParams.Builder builder = createBuilder();
            GenerateOptions options = GenerateOptions.builder().cacheControl(true).build();

            AnthropicToolsHelper.applyTools(
                    builder, List.of(toolSchema("tool1"), toolSchema("tool2")), options);

            MessageCreateParams params = builder.build();
            assertTrue(params.tools().isPresent());
            var tools = params.tools().get();
            assertEquals(2, tools.size());
            assertFalse(tools.get(0).asTool().cacheControl().isPresent());
            assertTrue(tools.get(1).asTool().cacheControl().isPresent());
        }

        @Test
        @DisplayName("should not mark tools when disabled")
        void noToolMarkedWhenDisabled() {
            MessageCreateParams.Builder builder = createBuilder();
            GenerateOptions options = GenerateOptions.builder().build();

            AnthropicToolsHelper.applyTools(
                    builder, List.of(toolSchema("tool1"), toolSchema("tool2")), options);

            MessageCreateParams params = builder.build();
            assertTrue(params.tools().isPresent());
            var tools = params.tools().get();
            assertFalse(tools.get(0).asTool().cacheControl().isPresent());
            assertFalse(tools.get(1).asTool().cacheControl().isPresent());
        }
    }
}
