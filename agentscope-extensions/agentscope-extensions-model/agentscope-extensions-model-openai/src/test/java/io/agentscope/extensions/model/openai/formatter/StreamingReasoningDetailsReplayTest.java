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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIReasoningDetail;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StreamingReasoningDetailsReplayTest {

    @ParameterizedTest
    @CsvSource({
        "reasoning.text,false",
        "reasoning.text,true",
        "reasoning.summary,false",
        "reasoning.summary,true",
        "reasoning.encrypted,false",
        "reasoning.encrypted,true"
    })
    void preservesReasoningBlocksWithoutReplayingStreamingIndexes(String type, boolean persisted) {
        OpenAIResponseParser parser = new OpenAIResponseParser();
        ReasoningContext context = new ReasoningContext("assistant");
        for (int index = 0; index < 2; index++) {
            String json =
                    """
                    {"id":"reply-1","object":"chat.completion.chunk","choices":[
                      {"index":0,"delta":{"reasoning_details":[
                        {"type":"%s","id":"rs_%d","index":%d,
                         "format":"anthropic-claude-v1","data":"opaque_%d","text":"text_%d"}
                      ]}}
                    ]}
                    """
                            .formatted(type, index, index, index, index);
            OpenAIResponse response = JsonUtils.getJsonCodec().fromJson(json, OpenAIResponse.class);
            assertEquals(
                    index,
                    response.getFirstChoice().getDelta().getReasoningDetails().get(0).getIndex());
            context.processChunk(parser.parseResponse(response, Instant.now()));
        }

        Msg history = context.buildFinalMessage();
        if (persisted) {
            history =
                    JsonUtils.getJsonCodec()
                            .fromJson(JsonUtils.getJsonCodec().toJson(history), Msg.class);
        }
        String originalHistory = JsonUtils.getJsonCodec().toJson(history);
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();
        OpenAIRequest request =
                formatter.buildRequest(
                        "kimi-k2.8-preview", formatter.format(List.of(history)), true);
        OpenAIMessage replay = request.getMessages().get(0);
        assertNotNull(replay.getReasoningDetails());
        assertEquals(
                List.of("rs_0", "rs_1"),
                replay.getReasoningDetails().stream().map(OpenAIReasoningDetail::getId).toList());
        assertEquals(
                List.of("opaque_0", "opaque_1"),
                replay.getReasoningDetails().stream().map(OpenAIReasoningDetail::getData).toList());
        assertEquals(
                List.of("text_0", "text_1"),
                replay.getReasoningDetails().stream().map(OpenAIReasoningDetail::getText).toList());
        for (OpenAIReasoningDetail detail : replay.getReasoningDetails()) {
            assertEquals(type, detail.getType());
            assertEquals("anthropic-claude-v1", detail.getFormat());
        }
        JsonNode wire =
                JsonUtils.getJsonCodec()
                        .fromJson(JsonUtils.getJsonCodec().toJson(request), JsonNode.class);
        JsonNode details = wire.path("messages").path(0).path("reasoning_details");
        assertEquals(2, details.size());
        for (JsonNode detail : details) {
            assertFalse(
                    detail.has("index"),
                    "Request reasoning details must not contain streaming index");
        }
        assertEquals(originalHistory, JsonUtils.getJsonCodec().toJson(history));
    }
}
