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

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.List;

/** Utilities for encoding AgentScope prompt-cache intent into Anthropic request blocks. */
final class AnthropicPromptCacheSupport {

    private static final CacheControlEphemeral EPHEMERAL = CacheControlEphemeral.builder().build();

    private AnthropicPromptCacheSupport() {}

    static CacheControlEphemeral ephemeral() {
        return EPHEMERAL;
    }

    static boolean isExplicitlyMarked(Msg msg) {
        return msg != null
                && msg.getMetadata() != null
                && Boolean.TRUE.equals(msg.getMetadata().get(MessageMetadataKeys.CACHE_CONTROL));
    }

    static boolean hasCacheControl(MessageParam message) {
        if (!message.content().isBlockParams()) {
            return false;
        }
        return message.content().asBlockParams().stream()
                .anyMatch(AnthropicPromptCacheSupport::hasCacheControl);
    }

    static MessageParam markLastCacheableBlock(MessageParam message) {
        if (!message.content().isBlockParams()) {
            throw new IllegalArgumentException(
                    "Anthropic prompt cache breakpoint requires block content");
        }

        List<ContentBlockParam> blocks = new ArrayList<>(message.content().asBlockParams());
        for (int i = blocks.size() - 1; i >= 0; i--) {
            ContentBlockParam marked = mark(blocks.get(i));
            if (marked != null) {
                blocks.set(i, marked);
                return message.toBuilder()
                        .content(MessageParam.Content.ofBlockParams(blocks))
                        .build();
            }
        }
        throw new IllegalArgumentException(
                "Anthropic prompt cache breakpoint has no cacheable content block");
    }

    private static boolean hasCacheControl(ContentBlockParam block) {
        if (block.isText()) {
            return block.asText().cacheControl().isPresent();
        }
        if (block.isImage()) {
            return block.asImage().cacheControl().isPresent();
        }
        if (block.isToolUse()) {
            return block.asToolUse().cacheControl().isPresent();
        }
        if (block.isToolResult()) {
            return block.asToolResult().cacheControl().isPresent();
        }
        return false;
    }

    private static ContentBlockParam mark(ContentBlockParam block) {
        if (block.isText()) {
            return ContentBlockParam.ofText(
                    block.asText().toBuilder().cacheControl(EPHEMERAL).build());
        }
        if (block.isImage()) {
            return ContentBlockParam.ofImage(
                    block.asImage().toBuilder().cacheControl(EPHEMERAL).build());
        }
        if (block.isToolUse()) {
            return ContentBlockParam.ofToolUse(
                    block.asToolUse().toBuilder().cacheControl(EPHEMERAL).build());
        }
        if (block.isToolResult()) {
            return ContentBlockParam.ofToolResult(
                    block.asToolResult().toBuilder().cacheControl(EPHEMERAL).build());
        }
        return null;
    }
}
