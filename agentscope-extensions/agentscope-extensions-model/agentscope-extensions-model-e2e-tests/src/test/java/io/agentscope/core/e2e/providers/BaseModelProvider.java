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
package io.agentscope.core.e2e.providers;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import java.util.HashSet;
import java.util.Set;

/**
 * Abstract base class for ModelProvider implementations.
 *
 * <p>Provides common functionality for API key handling and capability management. Subclasses
 * should use the {@link ModelCapabilities} annotation to declare their capabilities.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
 * public class MyProvider extends BaseModelProvider {
 *
 *     public MyProvider(String modelName, boolean multiAgent) {
 *         super("MY_API_KEY", modelName, multiAgent);
 *     }
 *
 *     @Override
 *     protected ReActAgent doCreateAgent(String name, Toolkit toolkit, String apiKey) {
 *         // Create and return the agent
 *     }
 *
 *     @Override
 *     public String getProviderName() {
 *         return "My Provider";
 *     }
 * }
 * }</pre>
 */
public abstract class BaseModelProvider implements ModelProvider {

    private final String apiKeyEnvVar;
    private final String modelName;
    private final boolean multiAgentFormatter;

    /**
     * Creates a new provider with the specified configuration.
     *
     * @param apiKeyEnvVar The environment variable name for the API key
     * @param modelName The model name to use
     * @param multiAgentFormatter Whether to use multi-agent formatter
     */
    protected BaseModelProvider(
            String apiKeyEnvVar, String modelName, boolean multiAgentFormatter) {
        this.apiKeyEnvVar = apiKeyEnvVar;
        this.modelName = modelName;
        this.multiAgentFormatter = multiAgentFormatter;
    }

    /**
     * Gets the API key from environment variable or system property.
     *
     * @return The API key, or null if not configured
     */
    protected String getApiKey() {
        String apiKey = System.getenv(apiKeyEnvVar);
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getProperty(apiKeyEnvVar);
        }
        return apiKey;
    }

    /**
     * Gets the API key, throwing an exception if not configured.
     *
     * @return The API key
     * @throws IllegalStateException if the API key is not configured
     */
    protected String requireApiKey() {
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    apiKeyEnvVar + " environment variable is required for " + getProviderName());
        }
        return apiKey;
    }

    @Override
    public final ReActAgent createAgent(String name, Toolkit toolkit) {
        return createAgentBuilder(name, toolkit).build();
    }

    @Override
    public ReActAgent.Builder createAgentBuilder(String name, Toolkit toolkit) {
        String apiKey = requireApiKey();
        return doCreateAgentBuilder(name, toolkit, apiKey);
    }

    /**
     * Creates the agent builder with the validated API key.
     *
     * @param name The agent name
     * @param toolkit The toolkit to use
     * @param apiKey The validated API key
     * @return The agent builder for further configuration
     */
    protected abstract ReActAgent.Builder doCreateAgentBuilder(
            String name, Toolkit toolkit, String apiKey);

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Checks if this provider uses multi-agent formatter.
     *
     * @return true if multi-agent formatter is used
     */
    public boolean isMultiAgentFormatter() {
        return multiAgentFormatter;
    }

    @Override
    public boolean isEnabled() {
        String apiKey = getApiKey();
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public boolean supportsThinking() {
        return getCapabilities().contains(ModelCapability.THINKING);
    }

    @Override
    public boolean supportsToolCalling() {
        return getCapabilities().contains(ModelCapability.TOOL_CALLING);
    }

    @Override
    public Set<ModelCapability> getCapabilities() {
        // First check for annotation on the actual class
        ModelCapabilities annotation = this.getClass().getAnnotation(ModelCapabilities.class);
        if (annotation != null) {
            return Set.of(annotation.value());
        }

        // Check parent classes for annotation
        Class<?> clazz = this.getClass().getSuperclass();
        while (clazz != null && clazz != Object.class) {
            annotation = clazz.getAnnotation(ModelCapabilities.class);
            if (annotation != null) {
                Set<ModelCapability> caps = new HashSet<>(Set.of(annotation.value()));
                // Add MULTI_AGENT_FORMATTER if using multi-agent formatter
                if (multiAgentFormatter) {
                    caps.add(ModelCapability.MULTI_AGENT_FORMATTER);
                }
                return caps;
            }
            clazz = clazz.getSuperclass();
        }

        // Fallback: basic capabilities
        Set<ModelCapability> caps = new HashSet<>();
        caps.add(ModelCapability.BASIC);
        if (multiAgentFormatter) {
            caps.add(ModelCapability.MULTI_AGENT_FORMATTER);
        }
        return caps;
    }

    /**
     * Gets the environment variable name for the API key.
     *
     * @return The environment variable name
     */
    public String getApiKeyEnvVar() {
        return apiKeyEnvVar;
    }

    @Override
    public String toString() {
        return getProviderName() + " [" + modelName + "]";
    }
}
