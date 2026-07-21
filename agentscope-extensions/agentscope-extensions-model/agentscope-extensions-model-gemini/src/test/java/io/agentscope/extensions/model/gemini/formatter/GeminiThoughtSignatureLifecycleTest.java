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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.formatter.FormatterException;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlockMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiThoughtSignatureLifecycleTest {

    private final GeminiResponseParser parser = new GeminiResponseParser();
    private final GeminiMessageConverter converter = new GeminiMessageConverter();

    @Test
    void shouldPreserveSignaturesThroughStreamingMemoryAndJsonRoundTrip() {
        byte[] thinkingSignature = "thinking-signature".getBytes(StandardCharsets.UTF_8);
        byte[] textSignature = "text-signature".getBytes(StandardCharsets.UTF_8);
        byte[] toolSignature = "tool-signature".getBytes(StandardCharsets.UTF_8);

        Part thinkingPart =
                Part.builder()
                        .text("Thinking")
                        .thought(true)
                        .thoughtSignature(thinkingSignature)
                        .build();
        Part textPart = Part.builder().text("Answer").thoughtSignature(textSignature).build();
        FunctionCall functionCall =
                FunctionCall.builder()
                        .id("call-1")
                        .name("search")
                        .args(Map.of("query", "AgentScope"))
                        .build();
        Part toolPart =
                Part.builder().functionCall(functionCall).thoughtSignature(toolSignature).build();
        Content modelContent =
                Content.builder()
                        .role("model")
                        .parts(List.of(thinkingPart, textPart, toolPart))
                        .build();
        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-1")
                        .candidates(List.of(Candidate.builder().content(modelContent).build()))
                        .build();

        ChatResponse parsed = parser.parseResponse(response, Instant.now());
        ReasoningContext reasoningContext = new ReasoningContext("assistant");
        reasoningContext.processChunk(parsed);
        Msg accumulated = reasoningContext.buildFinalMessage();
        String json = JsonUtils.getJsonCodec().toJson(accumulated);
        Msg restored = JsonUtils.getJsonCodec().fromJson(json, Msg.class);

        List<Part> replayedParts =
                converter.convertMessages(List.of(restored)).get(0).parts().orElseThrow();

        assertEquals(3, replayedParts.size());
        assertArrayEquals(thinkingSignature, replayedParts.get(0).thoughtSignature().orElseThrow());
        assertArrayEquals(textSignature, replayedParts.get(1).thoughtSignature().orElseThrow());
        assertArrayEquals(toolSignature, replayedParts.get(2).thoughtSignature().orElseThrow());
    }

    @Test
    void shouldPreserveDistinctSignaturesOnMultipleTextParts() {
        byte[] firstSignature = "first-signature".getBytes(StandardCharsets.UTF_8);
        byte[] secondSignature = "second-signature".getBytes(StandardCharsets.UTF_8);
        Content modelContent =
                Content.builder()
                        .role("model")
                        .parts(
                                List.of(
                                        Part.builder()
                                                .text("First")
                                                .thoughtSignature(firstSignature)
                                                .build(),
                                        Part.builder()
                                                .text("Second")
                                                .thoughtSignature(secondSignature)
                                                .build()))
                        .build();
        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-multiple-text-parts")
                        .candidates(List.of(Candidate.builder().content(modelContent).build()))
                        .build();

        ChatResponse parsed = parser.parseResponse(response, Instant.now());
        ReasoningContext reasoningContext = new ReasoningContext("assistant");
        reasoningContext.processChunk(parsed);
        Msg accumulated = reasoningContext.buildFinalMessage();
        String json = JsonUtils.getJsonCodec().toJson(accumulated);
        Msg restored = JsonUtils.getJsonCodec().fromJson(json, Msg.class);

        List<Part> replayedParts =
                converter.convertMessages(List.of(restored)).get(0).parts().orElseThrow();

        assertEquals(2, replayedParts.size());
        assertEquals("First", replayedParts.get(0).text().orElseThrow());
        assertEquals("Second", replayedParts.get(1).text().orElseThrow());
        assertArrayEquals(firstSignature, replayedParts.get(0).thoughtSignature().orElseThrow());
        assertArrayEquals(secondSignature, replayedParts.get(1).thoughtSignature().orElseThrow());
    }

    @Test
    void shouldRejectInvalidBase64Signature() {
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

        assertThrows(FormatterException.class, () -> converter.convertMessages(List.of(message)));
    }
}
