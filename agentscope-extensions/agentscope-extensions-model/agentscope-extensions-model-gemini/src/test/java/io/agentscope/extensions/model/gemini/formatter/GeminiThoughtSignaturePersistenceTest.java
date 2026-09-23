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
package io.agentscope.extensions.model.gemini.formatter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for thought signature handling in {@link GeminiMessageConverter}.
 *
 * <p>Gemini 3 requires thought signatures to be replayed with function calls. The parser stores
 * them as {@code byte[]} in tool use metadata, but a JSON persistence round-trip (session
 * save/load) restores them as a Base64 {@code String}, which the converter must decode back.
 */
@Tag("unit")
@DisplayName("GeminiMessageConverter Thought Signature Tests")
class GeminiThoughtSignaturePersistenceTest {

    private final GeminiMessageConverter converter = new GeminiMessageConverter();

    private static Msg assistantMsg(ToolUseBlock toolUse) {
        return Msg.builder()
                .name("assistant")
                .content(List.of(toolUse))
                .role(MsgRole.ASSISTANT)
                .build();
    }

    private static ToolUseBlock toolUseWithSignature(Object signature) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, signature);
        return ToolUseBlock.builder().id("call_1").name("search").metadata(metadata).build();
    }

    private static Msg roundTrip(Msg msg) {
        return JsonUtils.getJsonCodec().fromJson(JsonUtils.getJsonCodec().toJson(msg), Msg.class);
    }

    private Part firstPart(Msg msg) {
        List<Content> contents = converter.convertMessages(List.of(msg));
        return contents.get(0).parts().get().get(0);
    }

    @Test
    @DisplayName("Should attach byte[] thought signature on direct conversion")
    void testByteArraySignatureAttached() {
        byte[] signature = "signature-bytes".getBytes(StandardCharsets.UTF_8);

        Part part = firstPart(assistantMsg(toolUseWithSignature(signature)));

        assertTrue(part.thoughtSignature().isPresent());
        assertArrayEquals(signature, part.thoughtSignature().get());
    }

    @Test
    @DisplayName("Should restore thought signature after JSON persistence round-trip")
    void testSignatureRestoredAfterRoundTrip() {
        byte[] signature = "signature-bytes".getBytes(StandardCharsets.UTF_8);

        Part part = firstPart(roundTrip(assistantMsg(toolUseWithSignature(signature))));

        assertTrue(part.thoughtSignature().isPresent());
        assertArrayEquals(signature, part.thoughtSignature().get());
    }

    @Test
    @DisplayName("Should ignore non-Base64 signature instead of failing")
    void testNonBase64SignatureIgnored() {
        Part part = firstPart(assistantMsg(toolUseWithSignature("not valid base64!!!")));

        assertFalse(part.thoughtSignature().isPresent());
    }

    @Test
    @DisplayName("Should handle tool use without metadata")
    void testToolUseWithoutMetadata() {
        ToolUseBlock toolUse = ToolUseBlock.builder().id("call_1").name("search").build();

        Part part = firstPart(assistantMsg(toolUse));

        assertFalse(part.thoughtSignature().isPresent());
    }

    @Test
    @DisplayName("Should ignore non-string signature values")
    void testNonStringSignatureIgnored() {
        Part part = firstPart(assistantMsg(toolUseWithSignature(42)));

        assertFalse(part.thoughtSignature().isPresent());
    }

    @Test
    @DisplayName("Should ignore empty string signature")
    void testEmptySignatureIgnored() {
        Part part = firstPart(assistantMsg(toolUseWithSignature("")));

        assertFalse(part.thoughtSignature().isPresent());
    }
}
