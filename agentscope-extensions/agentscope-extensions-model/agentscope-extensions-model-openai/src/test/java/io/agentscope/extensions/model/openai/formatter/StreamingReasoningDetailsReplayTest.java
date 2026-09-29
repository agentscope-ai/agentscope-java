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

import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIReasoningDetail;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamingReasoningDetailsReplayTest {

    @Test
    void preservesDistinctReasoningBlocksAcrossStreamingChunks() {
        OpenAIResponseParser parser = new OpenAIResponseParser();
        ReasoningContext context = new ReasoningContext("assistant");
        for (int index = 0; index < 2; index++) {
            String json =
                    """
                    {"id":"reply-1","object":"chat.completion.chunk","choices":[
                      {"index":0,"delta":{"reasoning_details":[
                        {"type":"reasoning.encrypted","id":"rs_%d","index":%d,
                         "format":"anthropic-claude-v1","data":"opaque_%d"}
                      ]}}
                    ]}
                    """
                            .formatted(index, index, index);
            OpenAIResponse response = JsonUtils.getJsonCodec().fromJson(json, OpenAIResponse.class);
            context.processChunk(parser.parseResponse(response, Instant.now()));
        }

        OpenAIMessage replay =
                new OpenAIMessageConverter(m -> "", b -> "")
                        .convertToMessage(context.buildFinalMessage(), false);
        assertNotNull(replay.getReasoningDetails());
        assertEquals(
                List.of("rs_0", "rs_1"),
                replay.getReasoningDetails().stream().map(OpenAIReasoningDetail::getId).toList());
        assertEquals(
                List.of("opaque_0", "opaque_1"),
                replay.getReasoningDetails().stream().map(OpenAIReasoningDetail::getData).toList());
    }
}
