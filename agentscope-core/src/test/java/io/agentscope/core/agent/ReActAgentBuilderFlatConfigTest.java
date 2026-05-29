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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Verifies the flat ModelConfig/ReactConfig setters on ReActAgent.Builder:
 * maxRetries, fallbackModel(Model), fallbackModel(String), stopOnReject.
 */
class ReActAgentBuilderFlatConfigTest {

    private static final class StubModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }
    }

    private static Model stubModel() {
        return new StubModel();
    }

    @Test
    @SuppressWarnings("deprecation")
    void maxRetriesFlatSetterOverridesModelConfig() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .modelConfig(new ModelConfig(7, null))
                        .maxRetries(11)
                        .build();
        assertEquals(11, agent.getModelConfig().maxRetries());
    }

    @Test
    void fallbackModelInstanceWiring() {
        Model fb = stubModel();
        ReActAgent agent =
                ReActAgent.builder().name("t").model(stubModel()).fallbackModel(fb).build();
        assertSame(fb, agent.getModelConfig().fallbackModel());
    }

    @Test
    void fallbackModelStringResolvesViaRegistry() {
        Model fb = stubModel();
        ModelRegistry.register("my-fb", fb);
        try {
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("t")
                            .model(stubModel())
                            .fallbackModel("my-fb")
                            .build();
            assertSame(fb, agent.getModelConfig().fallbackModel());
        } finally {
            ModelRegistry.reset();
        }
    }

    @Test
    void fallbackModelStringUnknownThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ReActAgent.builder()
                                .name("t")
                                .model(stubModel())
                                .fallbackModel("nonexistent-model")
                                .build());
    }

    @Test
    @SuppressWarnings("deprecation")
    void stopOnRejectFlatSetterOverridesReactConfig() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .reactConfig(new ReactConfig(5, false))
                        .stopOnReject(true)
                        .build();
        assertTrue(agent.getReactConfig().stopOnReject());
    }

    @Test
    void maxItersExplicitWins() {
        ReActAgent agent = ReActAgent.builder().name("t").model(stubModel()).maxIters(7).build();
        assertEquals(7, agent.getReactConfig().maxIters());
    }

    @Test
    void defaultsWhenUnset() {
        ReActAgent agent = ReActAgent.builder().name("t").model(stubModel()).build();
        assertEquals(ModelConfig.DEFAULT_MAX_RETRIES, agent.getModelConfig().maxRetries());
        assertNull(agent.getModelConfig().fallbackModel());
        assertEquals(ReactConfig.DEFAULT_STOP_ON_REJECT, agent.getReactConfig().stopOnReject());
    }

    @Test
    void modelConfigFallbackAcceptsModelInterface() {
        Model fb = stubModel();
        ModelConfig cfg = new ModelConfig(5, fb);
        assertNotNull(cfg.fallbackModel());
        assertSame(fb, cfg.fallbackModel());
    }
}
