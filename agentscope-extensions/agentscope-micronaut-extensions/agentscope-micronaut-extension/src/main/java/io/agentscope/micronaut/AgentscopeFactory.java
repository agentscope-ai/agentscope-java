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
package io.agentscope.micronaut;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.micronaut.model.ModelProviderType;
import io.agentscope.micronaut.properties.AgentProperties;
import io.agentscope.micronaut.properties.AgentscopeProperties;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * Micronaut factory that exposes default Model, Memory, Toolkit and ReActAgent beans for
 * AgentScope.
 *
 * <p>Basic configuration with DashScope (default provider):
 *
 * <pre>{@code
 * agentscope:
 *   # Select model provider (defaults to dashscope when omitted)
 *   model:
 *     provider: dashscope
 *
 *   dashscope:
 *     enabled: true
 *     api-key: ${DASHSCOPE_API_KEY}
 *     model-name: qwen-plus
 *     stream: true
 *     enable-thinking: true
 *
 *   agent:
 *     enabled: true
 *     name: "Assistant"
 *     sys-prompt: "You are a helpful AI assistant."
 *     max-iters: 10
 * }</pre>
 *
 * <p>Using OpenAI as provider:
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: openai
 *
 *   openai:
 *     enabled: true
 *     api-key: ${OPENAI_API_KEY}
 *     model-name: gpt-4.1-mini
 *     stream: true
 * }</pre>
 *
 * <p>Using Gemini as provider (direct API):
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: gemini
 *
 *   gemini:
 *     enabled: true
 *     api-key: ${GEMINI_API_KEY}
 *     model-name: gemini-2.0-flash
 *     stream: true
 * }</pre>
 *
 * <p>Using Gemini via Vertex AI:
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: gemini
 *
 *   gemini:
 *     enabled: true
 *     project: your-gcp-project-id
 *     location: us-central1
 *     model-name: gemini-2.0-flash
 *     vertex-ai: true
 *     stream: true
 * }</pre>
 *
 * <p>Using Anthropic as provider:
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: anthropic
 *
 *   anthropic:
 *     enabled: true
 *     api-key: ${ANTHROPIC_API_KEY}
 *     model-name: claude-sonnet-4.5
 *     stream: true
 * }</pre>
 */
@Factory
@Requires(classes = ReActAgent.class)
public class AgentscopeFactory {

    /**
     * Default Memory implementation backed by InMemoryMemory.
     *
     * <p>Memory is stateful and not thread-safe, so we expose it as a prototype-scoped bean. In
     * multi-threaded / web environments, it is recommended to obtain instances lazily via injection
     * or programmatically.
     */
    @Prototype
    @Requires(missingBeans = Memory.class)
    public Memory agentscopeMemory() {
        return new InMemoryMemory();
    }

    /**
     * Default Toolkit implementation with an initially empty tool set.
     *
     * <p>Toolkit holds mutable state and is not thread-safe, so it is also exposed as a
     * prototype-scoped bean. In application code, prefer obtaining instances lazily via injection
     * or programmatically.
     */
    @Prototype
    @Requires(missingBeans = Toolkit.class)
    public Toolkit agentscopeToolkit() {
        return new Toolkit();
    }

    /**
     * Default Model implementation.
     *
     * <p>If DashScopeChatModel is on the classpath and dashscope auto-configuration is enabled,
     * this method creates a DashScopeChatModel based on {@link ModelProviderType} settings.
     */
    @Singleton
    @Requires(classes = Model.class)
    @Requires(missingBeans = Model.class)
    public Model agentscopeModel(AgentscopeProperties properties) {
        return ModelProviderType.fromProperties(properties).createModel(properties);
    }

    /**
     * Default ReActAgent that wires together the configured Model, Memory and Toolkit beans using
     * {@link AgentProperties}.
     *
     * <p>ReActAgent keeps session-level state (memory, toolkit, etc.) and is not thread-safe, so
     * it is exposed as a prototype-scoped bean. In Controllers / Services, prefer injecting new
     * agent instances per session or request.
     */
    @Prototype
    @Requires(property = "agentscope.agent.enabled", value = "true", defaultValue = "true")
    @Requires(missingBeans = ReActAgent.class)
    public ReActAgent agentscopeReActAgent(
            Model model, Memory memory, Toolkit toolkit, AgentscopeProperties properties) {
        AgentProperties config = properties.getAgent();
        if (config == null) {
            throw new IllegalStateException(
                    "AgentProperties must be configured when agent is enabled");
        }

        // Validate configuration
        if (config.getName() == null || config.getName().trim().isEmpty()) {
            throw new IllegalStateException(
                    "agentscope.agent.name must be configured and not empty");
        }
        if (config.getSysPrompt() == null || config.getSysPrompt().trim().isEmpty()) {
            throw new IllegalStateException(
                    "agentscope.agent.sys-prompt must be configured and not empty");
        }
        if (config.getMaxIters() <= 0) {
            throw new IllegalStateException(
                    "agentscope.agent.max-iters must be a positive integer, got: "
                            + config.getMaxIters());
        }

        return ReActAgent.builder()
                .name(config.getName())
                .sysPrompt(config.getSysPrompt())
                .model(model)
                .memory(memory)
                .toolkit(toolkit)
                .maxIters(config.getMaxIters())
                .build();
    }
}
