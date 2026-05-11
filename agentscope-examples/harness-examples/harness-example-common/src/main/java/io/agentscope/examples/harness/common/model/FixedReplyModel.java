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
package io.agentscope.examples.harness.common.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Flux;

/**
 * A minimal, deterministic {@link Model} implementation for examples: one assistant text turn
 * with finish reason {@code stop}, so the agent loop can complete without remote LLM calls.
 */
public final class FixedReplyModel implements Model {

    private final String modelName;
    private final String replyText;

    public FixedReplyModel(String modelName, String replyText) {
        this.modelName = modelName;
        this.replyText = replyText;
    }

    public static FixedReplyModel done() {
        return new FixedReplyModel("fixed-reply", "done");
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        ChatResponse chunk =
                new ChatResponse(
                        "local-" + UUID.randomUUID(),
                        List.of(TextBlock.builder().text(replyText).build()),
                        null,
                        Map.of(),
                        "stop");
        return Flux.just(chunk);
    }
}
