/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.micronaut.properties;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Root configuration properties for AgentScope.
 *
 * <p>At a high level, configuration is grouped as:
 *
 * <ul>
 *   <li>{@link AgentProperties} under {@code agentscope.agent}</li>
 *   <li>{@link DashscopeProperties} under {@code agentscope.dashscope}</li>
 *   <li>{@link ModelProperties} under {@code agentscope.model}</li>
 *   <li>{@link OpenAIProperties} under {@code agentscope.openai}</li>
 *   <li>{@link GeminiProperties} under {@code agentscope.gemini}</li>
 *   <li>{@link AnthropicProperties} under {@code agentscope.anthropic}</li>
 * </ul>
 */
@ConfigurationProperties("agentscope")
public class AgentscopeProperties {

    private AgentProperties agent = new AgentProperties();

    private DashscopeProperties dashscope = new DashscopeProperties();

    private ModelProperties model = new ModelProperties();

    private OpenAIProperties openai = new OpenAIProperties();

    private GeminiProperties gemini = new GeminiProperties();

    private AnthropicProperties anthropic = new AnthropicProperties();

    public AgentProperties getAgent() {
        return agent;
    }

    public void setAgent(AgentProperties agent) {
        this.agent = agent;
    }

    public DashscopeProperties getDashscope() {
        return dashscope;
    }

    public void setDashscope(DashscopeProperties dashscope) {
        this.dashscope = dashscope;
    }

    public ModelProperties getModel() {
        return model;
    }

    public void setModel(ModelProperties model) {
        this.model = model;
    }

    public OpenAIProperties getOpenai() {
        return openai;
    }

    public void setOpenai(OpenAIProperties openai) {
        this.openai = openai;
    }

    public GeminiProperties getGemini() {
        return gemini;
    }

    public void setGemini(GeminiProperties gemini) {
        this.gemini = gemini;
    }

    public AnthropicProperties getAnthropic() {
        return anthropic;
    }

    public void setAnthropic(AnthropicProperties anthropic) {
        this.anthropic = anthropic;
    }

    @ConfigurationProperties("agent")
    public static class AgentProperties {
        /**
         * Whether to create the default ReActAgent bean.
         */
        private boolean enabled = true;

        /**
         * Default agent name.
         */
        private String name = "Assistant";

        /**
         * Default system prompt used by the agent.
         */
        private String sysPrompt = "You are a helpful AI assistant.";

        /**
         * Maximum number of ReAct iterations for a single request.
         */
        private int maxIters = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSysPrompt() {
            return sysPrompt;
        }

        public void setSysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
        }

        public int getMaxIters() {
            return maxIters;
        }

        public void setMaxIters(int maxIters) {
            this.maxIters = maxIters;
        }
    }

    @ConfigurationProperties("dashscope")
    public static class DashscopeProperties {

        /**
         * Whether to enable DashScope model auto-configuration.
         */
        private boolean enabled = true;

        /**
         * DashScope API key used to authenticate requests.
         */
        private String apiKey;

        /**
         * DashScope model name, for example {@code qwen-plus} or {@code qwen-max}.
         */
        private String modelName = "qwen-plus";

        /**
         * Whether to enable streaming responses.
         */
        private boolean stream = true;

        /**
         * Whether to enable thinking mode (optional).
         */
        private Boolean enableThinking;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public boolean isStream() {
            return stream;
        }

        public void setStream(boolean stream) {
            this.stream = stream;
        }

        public Boolean getEnableThinking() {
            return enableThinking;
        }

        public void setEnableThinking(Boolean enableThinking) {
            this.enableThinking = enableThinking;
        }
    }

    @ConfigurationProperties("model")
    public static class ModelProperties {

        /**
         * Model provider identifier.
         *
         * <p>Supported values:
         *
         * <ul>
         *   <li>{@code dashscope} (default, when null or empty)</li>
         *   <li>{@code openai}</li>
         *   <li>{@code gemini}</li>
         *   <li>{@code anthropic}</li>
         * </ul>
         */
        private String provider;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }

    @ConfigurationProperties("openai")
    public static class OpenAIProperties {

        /**
         * Whether OpenAI model auto-configuration is enabled.
         */
        private boolean enabled = true;

        /**
         * OpenAI API key.
         */
        private String apiKey;

        /**
         * OpenAI model name, for example {@code gpt-4.1-mini}.
         */
        private String modelName = "gpt-4.1-mini";

        /**
         * Optional base URL for compatible OpenAI endpoints.
         */
        private String baseUrl;

        /**
         * Whether streaming responses are enabled.
         */
        private boolean stream = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public boolean isStream() {
            return stream;
        }

        public void setStream(boolean stream) {
            this.stream = stream;
        }
    }

    @ConfigurationProperties("gemini")
    public static class GeminiProperties {

        /**
         * Whether Gemini model auto-configuration is enabled.
         */
        private boolean enabled = true;

        /**
         * Gemini API key (for direct Gemini API usage).
         */
        private String apiKey;

        /**
         * Gemini model name, for example {@code gemini-2.0-flash}.
         */
        private String modelName = "gemini-2.0-flash";

        /**
         * Whether streaming responses are enabled.
         */
        private boolean stream = true;

        /**
         * Google Cloud project ID (for Vertex AI usage).
         */
        private String project;

        /**
         * Google Cloud location, for example {@code us-central1}.
         */
        private String location;

        /**
         * Whether to use Vertex AI (true) instead of direct Gemini API.
         */
        private Boolean vertexAI;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public boolean isStream() {
            return stream;
        }

        public void setStream(boolean stream) {
            this.stream = stream;
        }

        public String getProject() {
            return project;
        }

        public void setProject(String project) {
            this.project = project;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public Boolean getVertexAI() {
            return vertexAI;
        }

        public void setVertexAI(Boolean vertexAI) {
            this.vertexAI = vertexAI;
        }
    }

    @ConfigurationProperties("anthropic")
    public static class AnthropicProperties {

        /**
         * Whether Anthropic model auto-configuration is enabled.
         */
        private boolean enabled = true;

        /**
         * Anthropic API key.
         */
        private String apiKey;

        /**
         * Anthropic API base URL (optional).
         */
        private String baseUrl;

        /**
         * Anthropic model name, for example {@code claude-sonnet-4.5}.
         */
        private String modelName = "claude-sonnet-4.5";

        /**
         * Whether streaming responses are enabled.
         */
        private boolean stream = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public boolean isStream() {
            return stream;
        }

        public void setStream(boolean stream) {
            this.stream = stream;
        }
    }
}
