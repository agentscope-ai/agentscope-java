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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("AbstractBaseFormatter Unit Tests")
class AbstractBaseFormatterTest {

    private final TestFormatter formatter = new TestFormatter();

    @Test
    @DisplayName("Should detect media content from DataBlock audio and video")
    void testHasMediaContentWithDataBlocks() {
        Msg textOnly =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("hello").build()))
                        .build();

        Msg audioMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        DataBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        "https://example.com/audio.wav")
                                                                .build())
                                                .build()))
                        .build();

        Msg videoMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        DataBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        "https://example.com/video.mp4")
                                                                .build())
                                                .build()))
                        .build();

        Msg unknownMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(DataBlock.builder().source(new Source() {}).build()))
                        .build();

        assertFalse(formatter.hasMedia(textOnly));
        assertTrue(formatter.hasMedia(audioMsg));
        assertTrue(formatter.hasMedia(videoMsg));
        assertFalse(formatter.hasMedia(unknownMsg));
    }

    @Test
    @DisplayName("Should return text directly for a single tool result item")
    void testConvertToolResultToStringSingleText() {
        String result =
                formatter.renderToolResult(
                        List.of(TextBlock.builder().text("single output").build()));

        assertEquals("single output", result);
    }

    @Test
    @DisplayName("Should convert a single base64 audio result to a temp file reference")
    void testConvertToolResultToStringSingleBase64Audio() throws IOException {
        String base64Audio = Base64.getEncoder().encodeToString("audio-bytes".getBytes());
        DataBlock audioData =
                DataBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("audio/wav")
                                        .data(base64Audio)
                                        .build())
                        .build();

        String result = formatter.renderToolResult(List.of(audioData));

        assertTrue(result.startsWith("The returned audio can be found at: "));
        String path = result.substring("The returned audio can be found at: ".length());
        assertTrue(Files.exists(Path.of(path)));
        Files.deleteIfExists(Path.of(path));
    }

    @Test
    @DisplayName("Should format multiple mixed tool result items")
    void testConvertToolResultToStringMultipleItems() {
        String result =
                formatter.renderToolResult(
                        List.of(
                                TextBlock.builder().text("first").build(),
                                DataBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/audio.wav")
                                                        .build())
                                        .build(),
                                DataBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/video.mp4")
                                                        .build())
                                        .build(),
                                DataBlock.builder().source(new Source() {}).build()));

        assertEquals(
                "- first\n"
                        + "- The returned audio can be found at: https://example.com/audio.wav\n"
                        + "- The returned video can be found at: https://example.com/video.mp4",
                result);
    }

    @Test
    @DisplayName("Should ignore null and unknown tool result blocks")
    void testConvertToolResultToStringEmptyAndUnknown() {
        assertEquals("", formatter.renderToolResult(null));
        assertEquals("", formatter.renderToolResult(List.of()));
        assertEquals(
                "",
                formatter.renderToolResult(
                        List.of(DataBlock.builder().source(new Source() {}).build())));
    }

    @Test
    @DisplayName("Should extract text content and honor formatter helpers")
    void testExtractTextAndHelpers() {
        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("call_1")
                        .name("tool")
                        .output(List.of(TextBlock.builder().text("tool output").build()))
                        .build();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(MessageMetadataKeys.BYPASS_MULTIAGENT_HISTORY_MERGE, true);

        Msg textMsg =
                Msg.builder()
                        .content(
                                List.of(
                                        TextBlock.builder().text("hello").build(),
                                        new HintBlock(null, "hint"),
                                        ThinkingBlock.builder().thinking("skip me").build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        Msg toolMsg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .metadata(metadata)
                        .content(List.of(toolResult))
                        .build();

        assertEquals("hello\nhint", formatter.renderText(textMsg));
        assertEquals("tool output", formatter.renderText(toolMsg));
        assertEquals("Assistant", formatter.renderRoleLabel(MsgRole.ASSISTANT));
        assertTrue(formatter.shouldBypassHistory(toolMsg));
        assertFalse(
                formatter.shouldBypassHistory(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("x").build()))
                                .build()));
        assertEquals(
                0.7,
                formatter.optionOrDefault(
                        GenerateOptions.builder().temperature(0.7).build(),
                        GenerateOptions.builder().temperature(0.5).build(),
                        GenerateOptions::getTemperature),
                1e-6);
        assertEquals(
                0.5,
                formatter.optionOrDefault(
                        null,
                        GenerateOptions.builder().temperature(0.5).build(),
                        GenerateOptions::getTemperature),
                1e-6);
    }

    private static final class TestFormatter extends AbstractBaseFormatter<Object, Object, Object> {

        @Override
        protected List<Object> doFormat(List<Msg> msgs) {
            return List.of();
        }

        @Override
        public void applyTools(Object request, List<ToolSchema> tools) {}

        @Override
        public void applyOptions(
                Object request, GenerateOptions options, GenerateOptions defaultOptions) {}

        @Override
        public ChatResponse parseResponse(Object response, Instant receivedAt) {
            return null;
        }

        boolean hasMedia(Msg msg) {
            return hasMediaContent(msg);
        }

        String renderToolResult(List<ContentBlock> output) {
            return convertToolResultToString(output);
        }

        String renderText(Msg msg) {
            return extractTextContent(msg);
        }

        String renderRoleLabel(MsgRole role) {
            return formatRoleLabel(role);
        }

        @Override
        protected boolean shouldBypassHistory(Msg msg) {
            return super.shouldBypassHistory(msg);
        }

        <T> T optionOrDefault(
                GenerateOptions options,
                GenerateOptions defaultOptions,
                Function<GenerateOptions, T> getter) {
            return getOptionOrDefault(options, defaultOptions, getter);
        }
    }
}
