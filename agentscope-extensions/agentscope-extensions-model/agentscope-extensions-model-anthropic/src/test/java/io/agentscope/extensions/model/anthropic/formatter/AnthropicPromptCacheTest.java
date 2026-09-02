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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicPromptCacheTest {

    private final AnthropicChatFormatter formatter = new AnthropicChatFormatter();

    @Test
    void automaticCachingUsesTopLevelProviderField() {
        Msg user = message(MsgRole.USER, "hello", false);
        List<MessageParam> formatted = formatter.format(List.of(user));
        MessageCreateParams.Builder builder = baseBuilder();

        AnthropicBaseFormatter.PromptCachePlan plan =
                formatter.applyPromptCache(builder, List.of(user), formatted, true);
        plan.messages().forEach(builder::addMessage);
        MessageCreateParams params = builder.build();

        @SuppressWarnings("unchecked")
        Map<String, Object> cacheControl =
                params._additionalBodyProperties().get("cache_control").convert(Map.class);
        assertEquals(Map.of("type", "ephemeral"), cacheControl);
        assertFalse(AnthropicPromptCacheSupport.hasCacheControl(plan.messages().get(0)));
    }

    @Test
    void explicitMessageMarkerWorksWhenAutomaticCachingIsDisabled() {
        Msg user = message(MsgRole.USER, "stable context", true);
        List<MessageParam> formatted = formatter.format(List.of(user));
        MessageCreateParams.Builder builder = baseBuilder();

        AnthropicBaseFormatter.PromptCachePlan plan =
                formatter.applyPromptCache(builder, List.of(user), formatted, false);
        plan.messages().forEach(builder::addMessage);
        MessageCreateParams params = builder.build();

        assertTrue(AnthropicPromptCacheSupport.hasCacheControl(plan.messages().get(0)));
        assertFalse(params._additionalBodyProperties().containsKey("cache_control"));
    }

    @Test
    void explicitSystemMarkerUsesTypedSystemBlockWithoutDuplicatingMessage() {
        Msg system = message(MsgRole.SYSTEM, "You are helpful", true);
        Msg user = message(MsgRole.USER, "hello", false);
        List<MessageParam> formatted = formatter.format(List.of(user));
        MessageCreateParams.Builder builder = baseBuilder();

        AnthropicBaseFormatter.PromptCachePlan plan =
                formatter.applyPromptCache(builder, List.of(system, user), formatted, false);
        plan.messages().forEach(builder::addMessage);
        MessageCreateParams params = builder.build();

        assertEquals(1, params.messages().size());
        assertTrue(params.system().orElseThrow().isTextBlockParams());
        assertTrue(
                params.system()
                        .orElseThrow()
                        .asTextBlockParams()
                        .get(0)
                        .cacheControl()
                        .isPresent());
    }

    @Test
    void multiAgentFormatterPreservesExplicitBoundary() {
        AnthropicMultiAgentFormatter multiAgentFormatter = new AnthropicMultiAgentFormatter();
        List<MessageParam> formatted =
                multiAgentFormatter.format(
                        List.of(
                                message(MsgRole.USER, "first", false),
                                message(MsgRole.ASSISTANT, "stable", true),
                                message(MsgRole.USER, "last", false)));

        assertEquals(3, formatted.size());
        assertTrue(AnthropicPromptCacheSupport.hasCacheControl(formatted.get(1)));
    }

    @Test
    void rejectsMoreThanFourExplicitBreakpoints() {
        List<Msg> messages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            messages.add(message(MsgRole.USER, "context-" + i, true));
        }
        List<MessageParam> formatted = formatter.format(messages);

        assertThrows(
                IllegalArgumentException.class,
                () -> formatter.applyPromptCache(baseBuilder(), messages, formatted, false));
    }

    @Test
    void automaticCachingRejectsFourExplicitBreakpointsBecauseItUsesOneSlot() {
        List<Msg> messages = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            messages.add(message(MsgRole.USER, "context-" + i, true));
        }
        List<MessageParam> formatted = formatter.format(messages);

        assertThrows(
                IllegalArgumentException.class,
                () -> formatter.applyPromptCache(baseBuilder(), messages, formatted, true));
    }

    private static MessageCreateParams.Builder baseBuilder() {
        return MessageCreateParams.builder().model("claude-test").maxTokens(32);
    }

    private static Msg message(MsgRole role, String text, boolean cache) {
        Msg.Builder builder =
                Msg.builder()
                        .name(role.name())
                        .role(role)
                        .content(TextBlock.builder().text(text).build());
        if (cache) {
            builder.metadata(Map.of(MessageMetadataKeys.CACHE_CONTROL, true));
        }
        return builder.build();
    }
}
