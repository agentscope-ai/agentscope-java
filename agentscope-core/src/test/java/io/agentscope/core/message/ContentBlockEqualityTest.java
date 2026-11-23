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
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for value semantics on the {@link ContentBlock} hierarchy.
 *
 * <p>The state stores fingerprint list elements to choose between an incremental append and a full
 * rewrite. A block that falls back to identity equality makes an unchanged message look modified,
 * so every block type has to compare by content.
 */
class ContentBlockEqualityTest {

    @Test
    void everyBlockTypeSurvivesAJsonRoundTripWithAnEqualHash() {
        for (ContentBlock block : samples()) {
            String json = JsonUtils.getJsonCodec().toJson(block);
            ContentBlock restored = JsonUtils.getJsonCodec().fromJson(json, ContentBlock.class);

            assertEquals(block, restored, block.getClass().getSimpleName());
            assertEquals(block.hashCode(), restored.hashCode(), block.getClass().getSimpleName());
        }
    }

    @Test
    void aChangedTextFieldIsAVisibleDifference() {
        ContentBlock original = TextBlock.builder().text("hello").build();
        ContentBlock changed = TextBlock.builder().text("hello!").build();

        assertNotEquals(original, changed);
    }

    @Test
    void aChangedSizeHintIsAVisibleDifference() {
        Source source = Base64Source.builder().mediaType("image/png").data("AAA").build();
        ContentBlock original = ImageBlock.builder().source(source).minPixels(128).build();
        ContentBlock changed = ImageBlock.builder().source(source).minPixels(256).build();

        assertNotEquals(original, changed);
    }

    private static List<ContentBlock> samples() {
        Source image = Base64Source.builder().mediaType("image/png").data("AAA").build();
        Source audio = URLSource.builder().url("https://example.com/a.mp3").build();
        return List.of(
                TextBlock.builder().text("hello").build(),
                ThinkingBlock.builder()
                        .thinking("plan the call")
                        .metadata(Map.of("traceId", "t-1"))
                        .build(),
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("search")
                        .input(Map.of("query", "weather"))
                        .build(),
                ToolUseBlock.builder()
                        .id("call-2")
                        .name("search")
                        .input(Map.of("query", "weather"))
                        .metadata(
                                Map.of(
                                        ToolUseBlock.METADATA_THOUGHT_SIGNATURE,
                                        new byte[] {1, 2, 3}))
                        .build(),
                ToolResultBlock.builder()
                        .id("call-1")
                        .name("search")
                        .output(List.of(TextBlock.builder().text("ok").build()))
                        .build(),
                ImageBlock.builder().source(image).maxPixels(1024).build(),
                AudioBlock.builder().source(audio).build(),
                VideoBlock.builder()
                        .source(URLSource.builder().url("https://example.com/v.mp4").build())
                        .fps(24.0f)
                        .build(),
                new HintBlock("h-1", "subagent finished", "system"),
                DataBlock.builder().id("d-1").name("report.txt").source(image).build());
    }
}
