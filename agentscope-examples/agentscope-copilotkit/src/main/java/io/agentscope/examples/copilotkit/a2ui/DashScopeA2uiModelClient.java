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
package io.agentscope.examples.copilotkit.a2ui;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fallback A2UI composer running on DashScope, so the demo still produces surfaces without a Gemini
 * key.
 *
 * <p>A fresh single-iteration {@link ReActAgent} is built per request: the composer is stateless and
 * must never inherit conversation memory from a previous surface.
 */
@Component
public class DashScopeA2uiModelClient implements A2uiModelClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String modelName;

    public DashScopeA2uiModelClient(
            @Value("${agentscope.copilotkit.a2ui.dashscope.model:qwen-plus}") String modelName) {
        this.modelName = modelName;
    }

    @Override
    public String name() {
        return "dashscope:" + modelName;
    }

    @Override
    public boolean isAvailable() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        ReActAgent composer =
                ReActAgent.builder()
                        .name("A2UI Composer")
                        .sysPrompt(systemPrompt)
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                        .modelName(modelName)
                                        .stream(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .maxIters(1)
                        .build();
        Msg reply = composer.call(new UserMessage("user", userPrompt)).block(TIMEOUT);
        return reply == null ? null : reply.getTextContent();
    }
}
