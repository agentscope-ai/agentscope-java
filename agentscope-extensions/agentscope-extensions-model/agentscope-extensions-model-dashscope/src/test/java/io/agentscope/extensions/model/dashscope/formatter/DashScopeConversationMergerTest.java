/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.dashscope.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.extensions.model.dashscope.dto.DashScopeMessage;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Tests that the text conversation merger does not silently drop media blocks. */
class DashScopeConversationMergerTest {

    private final DashScopeConversationMerger merger = new DashScopeConversationMerger("");

    private static final Function<Msg, String> NAME_EXTRACTOR =
            msg -> msg.getName() != null ? msg.getName() : "Unknown";

    private static final Function<List<ContentBlock>, String> TOOL_RESULT_CONVERTER =
            blocks -> "[tool]";

    @Test
    void textMergePreservesAudioBlock() {
        Msg msg =
                Msg.builder()
                        .name("alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("listen").build(),
                                        AudioBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        "https://example.com/audio1.mp3")
                                                                .build())
                                                .build()))
                        .build();

        DashScopeMessage merged =
                merger.mergeToMessage(List.of(msg), NAME_EXTRACTOR, TOOL_RESULT_CONVERTER, "");

        assertNotNull(merged);
        String content = merged.getContentAsString();
        assertTrue(content.contains("alice: listen"), content);
        assertTrue(
                content.contains("[Audio:"),
                "an AudioBlock must not be silently dropped from the merged text history: "
                        + content);
        assertTrue(content.contains("audio1.mp3"), content);
    }

    @Test
    void textMergeKeepsPlainTextUnchanged() {
        Msg msg =
                Msg.builder()
                        .name("bob")
                        .content(TextBlock.builder().text("hello").build())
                        .build();

        DashScopeMessage merged =
                merger.mergeToMessage(List.of(msg), NAME_EXTRACTOR, TOOL_RESULT_CONVERTER, "");

        assertNotNull(merged);
        assertEquals("user", merged.getRole());
        assertEquals("<history>\nbob: hello\n</history>", merged.getContentAsString());
    }
}
