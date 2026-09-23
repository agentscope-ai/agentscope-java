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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlockMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Wire-format assertions for thought signature replay: verifies the exact JSON shape of the
 * Gemini request Content after a persistence round trip, not just the in-memory Part accessors.
 *
 * <p>Gemini 3 requires thinking parts to be replayed with {@code thought=true} and their
 * thought signatures intact; a corrupted signature must degrade to a missing field instead of
 * failing the request.
 */
class GeminiThinkingReplayWireFormatTest {

    private final GeminiMessageConverter converter = new GeminiMessageConverter();
    private final GeminiResponseParser parser = new GeminiResponseParser();

    @Test
    void shouldReplayStoredThinkingPartWithThoughtFlagAndSignature() {
        byte[] thinkingSignature = "thinking-signature".getBytes(StandardCharsets.UTF_8);
        Msg message =
                AssistantMessage.builder()
                        .content(
                                ThinkingBlock.builder()
                                        .thinking("Reasoning")
                                        .metadata(
                                                Map.of(
                                                        ContentBlockMetadataKeys.THOUGHT_SIGNATURE,
                                                        thinkingSignature))
                                        .build())
                        .build();

        // Simulate persistence: byte[] signatures survive JSON round trips as Base64 strings.
        Msg restored = roundTrip(message);
        String requestJson = toJson(restored);
        Map<String, Object> part = firstPart(requestJson);

        assertEquals(Boolean.TRUE, part.get("thought"));
        assertEquals(
                Base64.getEncoder().encodeToString(thinkingSignature),
                part.get("thoughtSignature"));
        assertEquals("Reasoning", part.get("text"));
    }

    @Test
    void shouldPreserveSignatureOnlyEmptyTextPartAcrossRoundTrip() {
        // Gemini returns parts that carry only a signature with empty text; they must survive
        // parse, persist, and replay so the signature stays attached to its original position.
        byte[] signature = "signature-only".getBytes(StandardCharsets.UTF_8);
        Part signatureOnlyPart = Part.builder().text("").thoughtSignature(signature).build();
        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-signature-only")
                        .candidates(
                                List.of(
                                        Candidate.builder()
                                                .content(
                                                        Content.builder()
                                                                .role("model")
                                                                .parts(
                                                                        List.of(
                                                                                signatureOnlyPart,
                                                                                Part.builder()
                                                                                        .text(
                                                                                                "Answer")
                                                                                        .build()))
                                                                .build())
                                                .build()))
                        .build();

        ChatResponse parsed = parser.parseResponse(response, Instant.now());
        Msg message = AssistantMessage.builder().content(parsed.getContent()).build();
        Msg restored = roundTrip(message);
        String requestJson = toJson(restored);

        Map<String, Object> parsedJson = parseJson(requestJson);
        List<Map<String, Object>> parts = parts(parsedJson);
        assertEquals(2, parts.size());
        assertEquals("", parts.get(0).get("text"));
        assertEquals(
                Base64.getEncoder().encodeToString(signature),
                parts.get(0).get("thoughtSignature"));
        assertEquals("Answer", parts.get(1).get("text"));
        assertFalse(parts.get(1).containsKey("thoughtSignature"));
    }

    @Test
    void shouldDropCorruptSignatureOnReplayWithoutFailing() {
        Msg message =
                AssistantMessage.builder()
                        .content(
                                TextBlock.builder()
                                        .text("Answer")
                                        .metadata(
                                                Map.of(
                                                        ContentBlockMetadataKeys.THOUGHT_SIGNATURE,
                                                        "not-valid-base64!"))
                                        .build())
                        .build();

        // Lenient handling: the corrupt signature is dropped with a warning, the request still
        // goes out and the conversation continues instead of aborting.
        Msg restored = roundTrip(message);
        String requestJson = toJson(restored);

        Map<String, Object> part = firstPart(requestJson);
        assertEquals("Answer", part.get("text"));
        assertFalse(part.containsKey("thoughtSignature"));
        assertFalse(part.containsKey("thought"));
    }

    @Test
    void shouldReplayThinkingPartWithCorruptSignatureAsOrdinaryTextPart() {
        Msg message =
                AssistantMessage.builder()
                        .content(
                                ThinkingBlock.builder()
                                        .thinking("Reasoning")
                                        .metadata(
                                                Map.of(
                                                        ContentBlockMetadataKeys.THOUGHT_SIGNATURE,
                                                        "not-valid-base64!"))
                                        .build())
                        .build();

        Msg restored = roundTrip(message);
        String requestJson = toJson(restored);

        // The thought flag rides on the restored signature: shipping it without the signature
        // would produce the signature-less thought Part Gemini is most likely to reject.
        Map<String, Object> part = firstPart(requestJson);
        assertEquals("Reasoning", part.get("text"));
        assertFalse(part.containsKey("thought"));
        assertFalse(part.containsKey("thoughtSignature"));
    }

    /**
     * Serializes the message to JSON and back, mimicking session persistence where byte[]
     * metadata values become Base64 strings.
     */
    private Msg roundTrip(Msg message) {
        String json = JsonUtils.getJsonCodec().toJson(message);
        return JsonUtils.getJsonCodec().fromJson(json, Msg.class);
    }

    private String toJson(Msg message) {
        return converter.convertMessages(List.of(message)).get(0).toJson();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        return JsonUtils.getJsonCodec().fromJson(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstPart(String requestJson) {
        return parts(parseJson(requestJson)).get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parts(Map<String, Object> contentJson) {
        return (List<Map<String, Object>>) contentJson.get("parts");
    }
}
