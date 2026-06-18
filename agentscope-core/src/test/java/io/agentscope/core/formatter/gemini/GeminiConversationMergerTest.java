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
package io.agentscope.core.formatter.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.Content;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiConversationMergerTest {

    private static final String IMAGE_BASE64 = "iVBORw0KGgo=";
    private static final String AUDIO_BASE64 = "UklGRg==";
    private static final String VIDEO_BASE64 = "AAAA";

    private final GeminiConversationMerger merger =
            new GeminiConversationMerger("# Conversation History\n");

    @Test
    @DisplayName("Should merge text, tool results and media into a single history content")
    void testMergeWithTextToolResultAndMedia() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Hello").build(),
                                        new HintBlock(null, "Need a quick answer"),
                                        ToolResultBlock.builder()
                                                .name("search")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Result")
                                                                        .build()))
                                                .build(),
                                        DataBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data(IMAGE_BASE64)
                                                                .build())
                                                .build(),
                                        DataBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("audio/wav")
                                                                .data(AUDIO_BASE64)
                                                                .build())
                                                .build(),
                                        DataBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("video/mp4")
                                                                .data(VIDEO_BASE64)
                                                                .build())
                                                .build()))
                        .build();

        Content result =
                merger.mergeToContent(
                        List.of(msg),
                        value -> value.getName() != null ? value.getName() : "Unknown",
                        blocks -> "tool output",
                        "# Prompt\n");

        assertNotNull(result);
        assertEquals("user", result.role().get());
        assertTrue(result.parts().isPresent());
        assertTrue(result.parts().get().size() >= 4);
        assertTrue(result.parts().get().get(0).text().get().startsWith("# Prompt\n<history>"));
        assertTrue(result.parts().get().get(0).text().get().contains("Alice: Hello"));
        assertTrue(result.parts().get().get(0).text().get().contains("Alice: Need a quick answer"));
        assertTrue(
                result.parts()
                        .get()
                        .get(result.parts().get().size() - 1)
                        .text()
                        .get()
                        .endsWith("</history>"));
    }

    @Test
    @DisplayName("Should wrap history when media comes first")
    void testMergeWithMediaFirst() {
        Msg mediaMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        DataBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data(IMAGE_BASE64)
                                                                .build())
                                                .build()))
                        .build();

        Msg textMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Bob")
                        .content(List.of(TextBlock.builder().text("After the image").build()))
                        .build();

        Content result =
                merger.mergeToContent(
                        List.of(mediaMsg, textMsg),
                        value -> value.getName() != null ? value.getName() : "Unknown",
                        blocks -> "tool output",
                        "# Prompt\n");

        assertNotNull(result);
        assertEquals("user", result.role().get());
        assertTrue(result.parts().isPresent());
        assertTrue(result.parts().get().size() >= 3);
        assertTrue(result.parts().get().get(0).text().get().startsWith("# Prompt\n<history>"));
        assertTrue(
                result.parts()
                        .get()
                        .get(result.parts().get().size() - 1)
                        .text()
                        .get()
                        .endsWith("</history>"));
    }
}
