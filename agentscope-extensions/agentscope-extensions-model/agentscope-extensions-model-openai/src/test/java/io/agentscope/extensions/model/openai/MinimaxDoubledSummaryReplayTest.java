/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.extensions.model.openai.dto.OpenAIChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import io.agentscope.extensions.model.openai.formatter.OpenAIResponseParser;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

// MiniMax-M3 streaming summary event dedup regression
class MinimaxDoubledSummaryReplayTest {

    // 5 chat.completion.chunk deltas + 1 chat.completion summary, captured from real MiniMax-M3
    private static final String[] SSE_PAYLOADS = {
        "{\"id\":\"06b0ebbde818dbb3769e9726ab05cb9f\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"name\":\"MiniMax"
            + " AI\",\"audio_content\":\"\",\"reasoning_content\":\"The\",\"reasoning_details\":[{\"type\":\"reasoning.text\",\"id\":\"reasoning-text-1\",\"format\":\"MiniMax-response-v1\",\"index\":0,\"text\":\"The\"}]}}],\"created\":1784789181,\"model\":\"MiniMax-M3\",\"object\":\"chat.completion.chunk\",\"usage\":{\"total_tokens\":0,\"total_characters\":0},\"input_sensitive\":false,\"output_sensitive\":false,\"input_sensitive_type\":0,\"output_sensitive_type\":0,\"output_sensitive_int\":0,\"service_tier\":\"standard\"}",
        "{\"id\":\"06b0ebbde818dbb3769e9726ab05cb9f\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"name\":\"MiniMax"
            + " AI\",\"audio_content\":\"\",\"reasoning_content\":\" user wants me to generate a"
            + " daily AI briefing HTML dashboard using the AIHOT skill. Let me load the skill first"
            + " to understand how to"
            + " fetch\",\"reasoning_details\":[{\"type\":\"reasoning.text\",\"id\":\"reasoning-text-1\",\"format\":\"MiniMax-response-v1\",\"index\":0,\"text\":\""
            + " user wants me to generate a daily AI briefing HTML dashboard using the AIHOT skill."
            + " Let me load the skill first to understand how to"
            + " fetch\"}]}}],\"created\":1784789181,\"model\":\"MiniMax-M3\",\"object\":\"chat.completion.chunk\",\"usage\":{\"total_tokens\":0,\"total_characters\":0},\"input_sensitive\":false,\"output_sensitive\":false,\"input_sensitive_type\":0,\"output_sensitive_type\":0,\"output_sensitive_int\":0,\"service_tier\":\"standard\"}",
        "{\"id\":\"06b0ebbde818dbb3769e9726ab05cb9f\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"name\":\"MiniMax"
            + " AI\",\"audio_content\":\"\",\"reasoning_content\":\" the data, then create the HTML"
            + " dashboard according to the specifications.\\n"
            + "\\n"
            + "Let me start by loading the AIHOT"
            + " skill.\",\"reasoning_details\":[{\"type\":\"reasoning.text\",\"id\":\"reasoning-text-1\",\"format\":\"MiniMax-response-v1\",\"index\":0,\"text\":\""
            + " the data, then create the HTML dashboard according to the specifications.\\n"
            + "\\n"
            + "Let me start by loading the AIHOT"
            + " skill.\"}]}}],\"created\":1784789181,\"model\":\"MiniMax-M3\",\"object\":\"chat.completion.chunk\",\"usage\":{\"total_tokens\":0,\"total_characters\":0},\"input_sensitive\":false,\"output_sensitive\":false,\"input_sensitive_type\":0,\"output_sensitive_type\":0,\"output_sensitive_int\":0,\"service_tier\":\"standard\"}",
        "{\"id\":\"06b0ebbde818dbb3769e9726ab05cb9f\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"name\":\"MiniMax"
            + " AI\",\"tool_calls\":[{\"id\":\"call_function_fo7n64ha38ds_1\",\"type\":\"function\",\"function\":{\"name\":\"load_skill_through_path\",\"arguments\":\"{\\\"skillId\\\":"
            + " \\\"AIHOT_marketplace\\\", \\\"path\\\":"
            + " \\\"SKILL.md\\\"\"},\"index\":0}],\"audio_content\":\"\"}}],\"created\":1784789181,\"model\":\"MiniMax-M3\",\"object\":\"chat.completion.chunk\",\"usage\":{\"total_tokens\":0,\"total_characters\":0},\"input_sensitive\":false,\"output_sensitive\":false,\"input_sensitive_type\":0,\"output_sensitive_type\":0,\"output_sensitive_int\":0,\"service_tier\":\"standard\"}",
        "{\"id\":\"06b0ebbde818dbb3769e9726ab05cb9f\",\"choices\":[{\"finish_reason\":\"tool_calls\",\"index\":0,\"delta\":{\"role\":\"assistant\",\"name\":\"MiniMax"
            + " AI\",\"tool_calls\":[{\"function\":{\"arguments\":\"}\"},\"index\":0}],\"audio_content\":\"\"}}],\"created\":1784789181,\"model\":\"MiniMax-M3\",\"object\":\"chat.completion.chunk\",\"usage\":{\"total_tokens\":0,\"total_characters\":0},\"input_sensitive\":false,\"output_sensitive\":false,\"input_sensitive_type\":0,\"output_sensitive_type\":0,\"output_sensitive_int\":0,\"service_tier\":\"standard\"}",
        "{\"id\":\"06b0ebbde818dbb3769e9726ab05cb9f\",\"choices\":[{\"finish_reason\":\"tool_calls\",\"index\":0,\"message\":{\"role\":\"assistant\",\"name\":\"MiniMax"
            + " AI\",\"tool_calls\":[{\"id\":\"call_function_fo7n64ha38ds_1\",\"type\":\"function\",\"function\":{\"name\":\"load_skill_through_path\",\"arguments\":\"{\\\"skillId\\\":"
            + " \\\"AIHOT_marketplace\\\", \\\"path\\\":"
            + " \\\"SKILL.md\\\"}\"},\"index\":0}],\"audio_content\":\"\",\"reasoning_content\":\"The"
            + " user wants me to generate a daily AI briefing HTML dashboard using the AIHOT skill."
            + " Let me load the skill first to understand how to fetch the data, then create the"
            + " HTML dashboard according to the specifications.\\n"
            + "\\n"
            + "Let me start by loading the AIHOT"
            + " skill.\",\"reasoning_details\":[{\"type\":\"reasoning.text\",\"id\":\"reasoning-text-1\",\"format\":\"MiniMax-response-v1\",\"index\":0,\"text\":\"The"
            + " user wants me to generate a daily AI briefing HTML dashboard using the AIHOT skill."
            + " Let me load the skill first to understand how to fetch the data, then create the"
            + " HTML dashboard according to the specifications.\\n"
            + "\\n"
            + "Let me start by loading the AIHOT"
            + " skill.\"}]}}],\"created\":1784789181,\"model\":\"MiniMax-M3\",\"object\":\"chat.completion\",\"usage\":{\"total_tokens\":12078,\"total_characters\":0,\"prompt_tokens\":11977,\"completion_tokens\":101,\"prompt_tokens_details\":{\"cached_tokens\":114}},\"input_sensitive\":false,\"output_sensitive\":false,\"input_sensitive_type\":0,\"output_sensitive_type\":0,\"output_sensitive_int\":0,\"service_tier\":\"standard\",\"base_resp\":{\"status_code\":0,\"status_msg\":\"\"}}"
    };

    // Fix point: transport layer clears the non-chunk summary message, keeps finishReason
    @Test
    void summaryEventMessageClearedByTransportLayer() {
        List<OpenAIResponse> responses = runStream();
        assertEquals(6, responses.size());
        OpenAIResponse summary = responses.get(5);
        assertFalse(summary.isChunk());
        assertEquals("chat.completion", summary.getObject());
        OpenAIChoice choice = summary.getFirstChoice();
        assertNotNull(choice);
        assertNull(choice.getMessage(), "summary message must be cleared by transport layer");
        assertEquals("tool_calls", choice.getFinishReason(), "finishReason must be preserved");
    }

    // End-to-end: once the summary message is cleared, ReasoningContext accumulates single-copy
    // reasoning and tool args instead of doubled ones
    @Test
    void reasoningAndToolArgsNotDoubled() {
        List<OpenAIResponse> responses = runStream();
        OpenAIResponseParser parser = new OpenAIResponseParser();
        Instant start = Instant.now();
        ReasoningContext ctx = new ReasoningContext("test");
        for (OpenAIResponse r : responses) {
            ChatResponse cr = parser.parseResponse(r, start);
            if (cr != null) {
                ctx.processChunk(cr);
            }
        }
        Msg finalMsg = ctx.buildFinalMessage();
        assertNotNull(finalMsg);
        // deltas already accumulated the full reasoning; the summary must not append it again
        assertEquals(
                1,
                countMatches(ctx.getAccumulatedThinking(), "Let me start by loading"),
                "reasoning must not be doubled by the summary event");
        // tool args must stay a single valid JSON object -> skillId appears once
        String allArgs =
                ctx.getAllAccumulatedToolCalls().stream()
                        .map(ToolUseBlock::getContent)
                        .collect(Collectors.joining());
        assertEquals(
                1,
                countMatches(allArgs, "AIHOT_marketplace"),
                "tool args must not be doubled into {...}{...}");
    }

    private List<OpenAIResponse> runStream() {
        HttpTransport transport =
                new HttpTransport() {
                    @Override
                    public Flux<String> stream(HttpRequest request) {
                        return Flux.fromArray(SSE_PAYLOADS).concatWith(Flux.just("[DONE]"));
                    }

                    @Override
                    public HttpResponse execute(HttpRequest request) {
                        throw new UnsupportedOperationException("execute not used");
                    }

                    @Override
                    public void close() {}
                };
        OpenAIClient client = new OpenAIClient(transport);
        OpenAIRequest request = new OpenAIRequest();
        request.setStream(true);
        return client.stream("test-key", "http://localhost/v1", request, null)
                .collectList()
                .block();
    }

    private static int countMatches(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
