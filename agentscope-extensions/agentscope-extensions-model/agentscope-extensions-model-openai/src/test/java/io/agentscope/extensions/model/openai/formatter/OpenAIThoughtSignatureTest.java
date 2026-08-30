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
package io.agentscope.extensions.model.openai.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIReasoningDetail;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for thought signature and reasoning detail handling in {@link
 * OpenAIMessageConverter}.
 *
 * <p>Signatures are produced as {@code byte[]} by some model parsers (e.g. Gemini via
 * OpenRouter) and as Base64 strings by others; after a JSON persistence round-trip a {@code
 * byte[]} signature is restored as a Base64 {@code String} and typed reasoning detail objects
 * are restored as {@code LinkedHashMap}. Both forms must be handled.
 */
@Tag("unit")
@DisplayName("OpenAIMessageConverter Thought Signature Tests")
class OpenAIThoughtSignatureTest {

    private OpenAIMessageConverter converter;

    private static final byte[] SIGNATURE = "signature-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE_BASE64 = Base64.getEncoder().encodeToString(SIGNATURE);

    @BeforeEach
    void setUp() {
        converter = new OpenAIMessageConverter(m -> "", b -> "");
    }

    private static Msg roundTrip(Msg msg) {
        return JsonUtils.getJsonCodec().fromJson(JsonUtils.getJsonCodec().toJson(msg), Msg.class);
    }

    private static OpenAIReasoningDetail reasoningDetail(String data) {
        OpenAIReasoningDetail detail = new OpenAIReasoningDetail();
        detail.setId("rs_1");
        detail.setType("signature");
        detail.setData(data);
        return detail;
    }

    @Test
    @DisplayName("Should attach typed signature and reasoning detail on direct conversion")
    void testTypedMetadataAttachedDirectly() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, SIGNATURE);
        metadata.put("reasoningDetail", reasoningDetail(SIGNATURE_BASE64));
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_1").name("search").metadata(metadata).build();
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolUse))
                        .role(MsgRole.ASSISTANT)
                        .build();

        OpenAIMessage result = converter.convertToMessage(msg, false);

        assertEquals(
                SIGNATURE_BASE64, result.getToolCalls().get(0).getFunction().getThoughtSignature());
        assertEquals(1, result.getReasoningDetails().size());
        assertEquals(SIGNATURE_BASE64, result.getReasoningDetails().get(0).getData());
    }

    @Test
    @DisplayName("Should ignore unusable signature values")
    void testUnusableSignatureIgnored() {
        for (Object signature : new Object[] {"", new byte[0], 42}) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, signature);
            ToolUseBlock toolUse =
                    ToolUseBlock.builder().id("call_1").name("search").metadata(metadata).build();
            Msg msg =
                    Msg.builder()
                            .name("assistant")
                            .content(List.of(toolUse))
                            .role(MsgRole.ASSISTANT)
                            .build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNull(result.getToolCalls().get(0).getFunction().getThoughtSignature());
        }
    }

    @Test
    @DisplayName("Should ignore reasoning detail values that cannot be restored")
    void testUnconvertibleReasoningDetailIgnored() {
        for (Object detail : new Object[] {Map.of("id", List.of("not-a-string")), 42}) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("reasoningDetail", detail);
            ToolUseBlock toolUse =
                    ToolUseBlock.builder().id("call_1").name("search").metadata(metadata).build();
            Msg msg =
                    Msg.builder()
                            .name("assistant")
                            .content(List.of(toolUse))
                            .role(MsgRole.ASSISTANT)
                            .build();

            OpenAIMessage result = converter.convertToMessage(msg, false);

            assertNull(result.getReasoningDetails());
        }
    }

    @Test
    @DisplayName("Should handle tool use without metadata")
    void testToolUseWithoutMetadata() {
        ToolUseBlock toolUse = ToolUseBlock.builder().id("call_1").name("search").build();
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolUse))
                        .role(MsgRole.ASSISTANT)
                        .build();

        OpenAIMessage result = converter.convertToMessage(msg, false);

        assertNull(result.getToolCalls().get(0).getFunction().getThoughtSignature());
        assertNull(result.getReasoningDetails());
    }

    @Test
    @DisplayName("Should convert byte[] thought signature to Base64 string")
    void testByteArraySignatureConverted() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, SIGNATURE);
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_1").name("search").metadata(metadata).build();
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolUse))
                        .role(MsgRole.ASSISTANT)
                        .build();

        OpenAIMessage result = converter.convertToMessage(msg, false);

        assertEquals(
                SIGNATURE_BASE64, result.getToolCalls().get(0).getFunction().getThoughtSignature());
    }

    @Test
    @DisplayName("Should keep tool signature and reasoning detail after JSON round-trip")
    void testToolMetadataRestoredAfterRoundTrip() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, SIGNATURE);
        metadata.put("reasoningDetail", reasoningDetail(SIGNATURE_BASE64));
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_1").name("search").metadata(metadata).build();
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolUse))
                        .role(MsgRole.ASSISTANT)
                        .build();

        OpenAIMessage result = converter.convertToMessage(roundTrip(msg), false);

        assertEquals(
                SIGNATURE_BASE64, result.getToolCalls().get(0).getFunction().getThoughtSignature());
        List<OpenAIReasoningDetail> details = result.getReasoningDetails();
        assertNotNull(details);
        assertEquals(1, details.size());
        assertEquals(SIGNATURE_BASE64, details.get(0).getData());
    }

    @Test
    @DisplayName("Should keep ThinkingBlock reasoning details after JSON round-trip")
    void testThinkingDetailsRestoredAfterRoundTrip() {
        Map<String, Object> thinkingMetadata = new HashMap<>();
        thinkingMetadata.put(
                ThinkingBlock.METADATA_REASONING_DETAILS,
                new ArrayList<>(List.of(reasoningDetail("thinking-detail"))));
        ThinkingBlock thinking =
                ThinkingBlock.builder().thinking("thinking...").metadata(thinkingMetadata).build();
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(thinking))
                        .role(MsgRole.ASSISTANT)
                        .build();

        OpenAIMessage result = converter.convertToMessage(roundTrip(msg), false);

        List<OpenAIReasoningDetail> details = result.getReasoningDetails();
        assertNotNull(details);
        assertEquals(1, details.size());
        assertEquals("thinking-detail", details.get(0).getData());
    }
}
