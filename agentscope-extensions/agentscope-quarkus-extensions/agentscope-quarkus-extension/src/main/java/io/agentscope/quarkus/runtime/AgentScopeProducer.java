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
package io.agentscope.quarkus.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * CDI Producer for AgentScope components. This class provides auto-configuration
 * creating beans based on application.properties configuration.
 *
 * <p>Example configuration:
 *
 * <pre>
 * agentscope.model.provider=dashscope
 * agentscope.dashscope.api-key=${DASHSCOPE_API_KEY}
 * agentscope.dashscope.model-name=qwen-plus
 * agentscope.agent.name=MyAssistant
 * </pre>
 */
@ApplicationScoped
public class AgentScopeProducer {

    @Inject AgentScopeConfig config;

    private Toolkit toolkit;

    /**
     * Initializes the shared Toolkit instance. Called by CDI container after bean
     * construction. The @PostConstruct annotation ensures this method is executed exactly once
     * and thread-safely by the CDI container.
     */
    @PostConstruct
    void init() {
        this.toolkit = new Toolkit();
    }

    /**
     * Produces a Model bean based on the configured provider. Supports: dashscope, openai, gemini,
     * anthropic.
     *
     * @return configured Model instance
     */
    @Produces
    @ApplicationScoped
    public Model createModel() {
        String provider = config.model().provider();

        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Model provider cannot be null or empty");
        }

        return switch (provider.toLowerCase()) {
            case "dashscope" -> createDashscopeModel();
            case "openai" -> createOpenAIModel();
            case "gemini" -> createGeminiModel();
            case "anthropic" -> createAnthropicModel();
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported model provider: "
                                    + provider
                                    + ". Supported providers: dashscope, openai, gemini,"
                                    + " anthropic");
        };
    }

    /**
     * Produces a Memory bean. Uses InMemoryMemory as default implementation. This is a
     * dependent-scoped bean, creating a new instance per injection point.
     *
     * @return new InMemoryMemory instance
     */
    @Produces
    @Dependent
    public Memory createMemory() {
        return new InMemoryMemory();
    }

    /**
     * Produces a Toolkit bean. Returns the shared toolkit instance initialized by
     * {@code @PostConstruct}. This is an application-scoped bean, ensuring all agents use
     * the same toolkit instance across the application for consistent tool management.
     *
     * @return configured Toolkit instance
     */
    @Produces
    @ApplicationScoped
    public Toolkit createToolkit() {
        return toolkit;
    }

    /**
     * Produces a ReActAgent bean configured with Model, Memory, and Toolkit. This is a
     * dependent-scoped bean, creating a new agent instance per injection point.
     *
     * <p>The Toolkit is obtained from the initialized shared instance rather than
     * injected to avoid CDI ambiguity between auto-discovered Toolkit and the producer.
     *
     * @param model the Model to use
     * @param memory the Memory to use
     * @return configured ReActAgent
     */
    @Produces
    @Dependent
    public ReActAgent createAgent(Model model, Memory memory) {
        return ReActAgent.builder()
                .name(config.agent().name())
                .sysPrompt(config.agent().sysPrompt())
                .model(model)
                .memory(memory)
                .toolkit(toolkit)
                .maxIters(config.agent().maxIters())
                .build();
    }

    private Model createDashscopeModel() {
        AgentScopeConfig.DashscopeConfig dashscope = config.dashscope();

        String apiKey =
                dashscope
                        .apiKey()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "DashScope API key is required. Configure it using"
                                                        + " agentscope.dashscope.api-key."));

        DashScopeChatModel.Builder builder =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(dashscope.modelName()).stream(
                        dashscope.stream());

        if (dashscope.enableThinking()) {
            builder.enableThinking(true);
        }

        dashscope.baseUrl().ifPresent(builder::baseUrl);

        return builder.build();
    }

    private Model createOpenAIModel() {
        AgentScopeConfig.OpenAIConfig openai = config.openai();

        String apiKey =
                openai.apiKey()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "OpenAI API key is required. Configure it using"
                                                        + " agentscope.openai.api-key."));

        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(openai.modelName()).stream(
                        openai.stream());

        openai.baseUrl().ifPresent(builder::baseUrl);

        return builder.build();
    }

    private Model createGeminiModel() {
        AgentScopeConfig.GeminiConfig gemini = config.gemini();

        GeminiChatModel.Builder builder =
                GeminiChatModel.builder()
                        .modelName(gemini.modelName())
                        .streamEnabled(gemini.stream());

        if (gemini.useVertexAi()) {
            // Vertex AI configuration
            String project =
                    gemini.project()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "GCP project is required for Vertex AI. Set"
                                                            + " agentscope.gemini.project."));
            String location =
                    gemini.location()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "GCP location is required for Vertex AI. Set"
                                                            + " agentscope.gemini.location."));

            builder.project(project).location(location).vertexAI(true);
        } else {
            // Direct API configuration - requires API key
            String apiKey =
                    gemini.apiKey()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Gemini API key is required. Configure it using"
                                                        + " agentscope.gemini.api-key."
                                                        + " Alternatively, use Vertex AI by setting"
                                                        + " agentscope.gemini.use-vertex-ai=true"));
            builder.apiKey(apiKey);
        }

        return builder.build();
    }

    private Model createAnthropicModel() {
        AgentScopeConfig.AnthropicConfig anthropic = config.anthropic();

        String apiKey =
                anthropic
                        .apiKey()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Anthropic API key is required. Configure it using"
                                                        + " agentscope.anthropic.api-key."));

        AnthropicChatModel.Builder builder =
                AnthropicChatModel.builder().apiKey(apiKey).modelName(anthropic.modelName()).stream(
                        anthropic.stream());

        anthropic.baseUrl().ifPresent(builder::baseUrl);

        return builder.build();
    }
}
