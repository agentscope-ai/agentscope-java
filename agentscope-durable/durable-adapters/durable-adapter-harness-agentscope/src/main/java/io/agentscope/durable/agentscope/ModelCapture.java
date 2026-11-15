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
package io.agentscope.durable.agentscope;

import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Call-local semantic accumulation. No session-log reads participate in outcome construction. */
final class ModelCapture {
    static final String OUTCOME = "durableModelOutcome";
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private final Map<String, Object> request;
    private final String replayHash;
    private final ReasoningContext content = new ReasoningContext("durable");
    private final List<ContentBlock> additional = new ArrayList<>();
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private ChatUsage usage;
    private String id;
    private String finishReason;
    private long bytes;

    ModelCapture(
            String model,
            String purpose,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options,
            String replayHash) {
        var data = new LinkedHashMap<String, Object>();
        data.put("model", model);
        data.put("purpose", purpose);
        data.put("messages", messages);
        data.put("tools", tools);
        data.put("options", options);
        this.request = freeze(data);
        this.replayHash = replayHash;
        if (JsonUtils.getJsonCodec().toJson(request).length() > MAX_BYTES)
            throw new DurableException(
                    DurableException.Code.INVALID, "Model request exceeds capture limit");
    }

    synchronized void nextAttempt() {
        usage = null;
        finishReason = null;
        metadata.clear();
    }

    synchronized void accept(ChatResponse chunk) {
        bytes =
                Math.addExact(
                        bytes,
                        JsonUtils.getJsonCodec()
                                .toJson(chunk)
                                .getBytes(StandardCharsets.UTF_8)
                                .length);
        if (bytes > MAX_BYTES)
            throw new DurableException(
                    DurableException.Code.INVALID, "Model response exceeds capture limit");
        id = chunk.getId();
        if (chunk.getUsage() != null) usage = chunk.getUsage();
        if (chunk.getFinishReason() != null) finishReason = chunk.getFinishReason();
        if (chunk.getMetadata() != null) metadata.putAll(chunk.getMetadata());
        var blocks = chunk.getContent() == null ? List.<ContentBlock>of() : chunk.getContent();
        content.processChunk(
                ChatResponse.builder()
                        .id(id)
                        .content(blocks)
                        .usage(usage)
                        .metadata(chunk.getMetadata())
                        .finishReason(finishReason)
                        .build());
        for (var block : blocks)
            if (!(block instanceof TextBlock)
                    && !(block instanceof ThinkingBlock)
                    && !(block instanceof ToolUseBlock)
                    && !(block instanceof ToolResultBlock result && result.isServerTool()))
                additional.add(block);
    }

    synchronized Map<String, Object> freeze(String status) {
        var outcome = new LinkedHashMap<String, Object>();
        outcome.put("request", request);
        outcome.put("replayHash", replayHash);
        if (usage != null) outcome.put("usage", freeze(usage));
        if (finishReason != null) outcome.put("finishReason", finishReason);
        if ("completed".equals(status)) {
            var message = content.buildFinalMessage();
            var blocks = new ArrayList<ContentBlock>();
            if (message != null) blocks.addAll(message.getContent());
            blocks.addAll(additional);
            var response =
                    ChatResponse.builder()
                            .id(id)
                            .content(blocks)
                            .usage(usage)
                            .metadata(metadata)
                            .finishReason(finishReason)
                            .build();
            outcome.put("responses", List.of(freeze(response)));
        } else outcome.put("responses", List.of());
        return freeze(outcome);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> freeze(Object data) {
        return JsonUtils.getJsonCodec().fromJson(JsonUtils.getJsonCodec().toJson(data), Map.class);
    }
}
