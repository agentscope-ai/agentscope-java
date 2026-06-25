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
package io.agentscope.core.model;

import io.agentscope.core.model.spi.ModelProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry for resolving {@link Model} instances from string identifiers (named instances or
 * {@code provider:model} patterns). User-registered factories take precedence over providers
 * loaded from the {@link ModelProvider} SPI and built-in providers.
 *
 * <p>Provider extension modules read API keys from their own standard environment variables when
 * auto-creating models. Remaining built-in providers read {@code DASHSCOPE_API_KEY}, {@code
 * GEMINI_API_KEY}, {@code ANTHROPIC_API_KEY} (optional for Anthropic SDK), {@code OLLAMA_BASE_URL}
 * (optional, defaults to {@code http://localhost:11434}).
 */
public final class ModelRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ModelRegistry.class);

    private static final ConcurrentHashMap<String, Model> namedModels = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ProviderEntry> userFactories =
            new CopyOnWriteArrayList<>();
    private static final List<ProviderEntry> builtinFactories = new ArrayList<>();
    private static final ConcurrentHashMap<String, Model> resolvedCache = new ConcurrentHashMap<>();
    private static volatile List<ModelProvider> serviceProviders;

    static {
        registerBuiltin(
                "dashscope:(.+)",
                modelId -> {
                    String modelName = modelId.substring("dashscope:".length());
                    String apiKey = requireApiKey("DASHSCOPE_API_KEY", modelId);
                    return DashScopeChatModel.builder().apiKey(apiKey).modelName(modelName).stream(
                                    true)
                            .build();
                });
        registerBuiltin(
                "qwen.+",
                modelId -> {
                    String apiKey = requireApiKey("DASHSCOPE_API_KEY", modelId);
                    return DashScopeChatModel.builder().apiKey(apiKey).modelName(modelId).stream(
                                    true)
                            .build();
                });
        registerBuiltin(
                "anthropic:(.+)",
                modelId -> {
                    String modelName = modelId.substring("anthropic:".length());
                    String apiKey = env("ANTHROPIC_API_KEY");
                    return AnthropicChatModel.builder().apiKey(apiKey).modelName(modelName).stream(
                                    true)
                            .build();
                });
        registerBuiltin(
                "gemini:(.+)",
                modelId -> {
                    String modelName = modelId.substring("gemini:".length());
                    String apiKey = requireApiKey("GEMINI_API_KEY", modelId);
                    return GeminiChatModel.builder()
                            .apiKey(apiKey)
                            .modelName(modelName)
                            .streamEnabled(true)
                            .build();
                });
        registerBuiltin(
                "ollama:(.+)",
                modelId -> {
                    String modelName = modelId.substring("ollama:".length());
                    String baseUrl = env("OLLAMA_BASE_URL");
                    if (baseUrl == null || baseUrl.isBlank()) {
                        baseUrl = "http://localhost:11434";
                    }
                    return OllamaChatModel.builder().modelName(modelName).baseUrl(baseUrl).build();
                });
    }

    private ModelRegistry() {}

    /**
     * Registers a named {@link Model} instance. {@link #resolve(String)} returns this instance for
     * an exact {@code name} match (no caching of factory-created instances applies).
     */
    public static void register(String name, Model model) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(model, "model");
        namedModels.put(name, model);
    }

    /**
     * Registers a factory matched against the full {@code modelId} string using {@link
     * Pattern#matches}. Newly registered factories are consulted before older user registrations
     * and before built-in providers.
     *
     * @param modelNameRegex regex with semantics of Pattern#matches(CharSequence) on the
     *     full model id
     * @param factory creates a {@link Model} from the full model id
     */
    public static void registerFactory(String modelNameRegex, ModelFactory factory) {
        Objects.requireNonNull(modelNameRegex, "modelNameRegex");
        Objects.requireNonNull(factory, "factory");
        Pattern pattern = Pattern.compile(modelNameRegex);
        userFactories.add(0, new ProviderEntry(pattern, factory));
    }

    /**
     * Resolves a {@link Model} for the given id: named registration first, then cached
     * factory-created instance, then user factories (newest first), then SPI providers, then
     * built-in factories.
     *
     * @throws IllegalArgumentException if the id cannot be resolved or creation fails
     */
    public static Model resolve(String modelId) {
        Objects.requireNonNull(modelId, "modelId");
        String trimmed = modelId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }

        Model named = namedModels.get(trimmed);
        if (named != null) {
            return named;
        }

        Model cached = resolvedCache.get(trimmed);
        if (cached != null) {
            return cached;
        }

        ProviderEntry entry = findMatchingUserEntry(trimmed);
        ModelProvider provider = entry == null ? findServiceProvider(trimmed) : null;
        ProviderEntry builtinEntry =
                entry == null && provider == null ? findMatchingBuiltinEntry(trimmed) : null;
        if (entry == null && provider == null && builtinEntry == null) {
            throw new IllegalArgumentException(buildNotFoundMessage(trimmed));
        }

        try {
            Model created;
            if (entry != null) {
                created = entry.factory().create(trimmed);
                Objects.requireNonNull(created, "ModelFactory returned null for: " + trimmed);
            } else if (provider != null) {
                created = provider.create(trimmed);
                Objects.requireNonNull(
                        created,
                        "ModelProvider "
                                + provider.providerId()
                                + " returned null for: "
                                + trimmed);
            } else {
                created = builtinEntry.factory().create(trimmed);
                Objects.requireNonNull(created, "ModelFactory returned null for: " + trimmed);
            }
            resolvedCache.put(trimmed, created);
            return created;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Failed to create model for id: " + trimmed + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns {@code true} if {@link #resolve(String)} can find a named model or a matching factory
     * pattern (without creating an instance).
     */
    public static boolean canResolve(String modelId) {
        if (modelId == null) {
            return false;
        }
        String trimmed = modelId.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (namedModels.containsKey(trimmed)) {
            return true;
        }
        return findMatchingUserEntry(trimmed) != null
                || findServiceProvider(trimmed) != null
                || findMatchingBuiltinEntry(trimmed) != null;
    }

    /**
     * Clears named models, user-registered factories, and the factory-resolution cache. Built-in
     * provider rules are preserved. Intended for tests.
     */
    public static void reset() {
        namedModels.clear();
        userFactories.clear();
        resolvedCache.clear();
        reloadProviders();
    }

    /**
     * Clears the cached SPI providers so the next resolution can rediscover providers from the
     * current classloader context.
     */
    public static void reloadProviders() {
        serviceProviders = null;
    }

    @FunctionalInterface
    public interface ModelFactory {
        Model create(String modelId);
    }

    private record ProviderEntry(Pattern pattern, ModelFactory factory) {}

    private static void registerBuiltin(String regex, ModelFactory factory) {
        builtinFactories.add(new ProviderEntry(Pattern.compile(regex), factory));
    }

    private static ProviderEntry findMatchingUserEntry(String modelId) {
        for (ProviderEntry e : userFactories) {
            if (e.pattern().matcher(modelId).matches()) {
                return e;
            }
        }
        return null;
    }

    private static ProviderEntry findMatchingBuiltinEntry(String modelId) {
        for (ProviderEntry e : builtinFactories) {
            if (e.pattern().matcher(modelId).matches()) {
                return e;
            }
        }
        return null;
    }

    private static ModelProvider findServiceProvider(String modelId) {
        ModelProvider matched = null;
        for (ModelProvider provider : loadServiceProviders()) {
            boolean supports;
            try {
                supports = provider.supports(modelId);
            } catch (RuntimeException | LinkageError e) {
                logger.warn(
                        "Skipping ModelProvider {} because supports(\"{}\") failed",
                        provider.getClass().getName(),
                        modelId,
                        e);
                continue;
            }
            if (!supports) {
                continue;
            }
            if (matched != null) {
                logger.warn(
                        "Multiple ModelProvider implementations support model id \"{}\": {} and"
                                + " {}. Using the first discovered provider.",
                        modelId,
                        matched.getClass().getName(),
                        provider.getClass().getName());
                continue;
            }
            matched = provider;
        }
        return matched;
    }

    private static List<ModelProvider> loadServiceProviders() {
        List<ModelProvider> providers = serviceProviders;
        if (providers != null) {
            return providers;
        }
        synchronized (ModelRegistry.class) {
            providers = serviceProviders;
            if (providers == null) {
                providers = new ArrayList<>();
                providers.addAll(
                        loadServiceProviders(Thread.currentThread().getContextClassLoader()));
                Collection<ModelProvider> fallback =
                        loadServiceProviders(ModelRegistry.class.getClassLoader());
                for (ModelProvider provider : fallback) {
                    if (!containsProvider(providers, provider)) { // deduplicate
                        providers.add(provider);
                    }
                }
                providers = List.copyOf(providers);
                serviceProviders = providers;
            }
            return providers;
        }
    }

    private static Collection<ModelProvider> loadServiceProviders(ClassLoader classLoader) {
        List<ModelProvider> providers = new ArrayList<>();
        ServiceLoader<ModelProvider> loader =
                classLoader != null
                        ? ServiceLoader.load(ModelProvider.class, classLoader)
                        : ServiceLoader.load(ModelProvider.class);
        Iterator<ModelProvider> iterator = loader.iterator();
        while (true) {
            boolean hasNext;
            try {
                hasNext = iterator.hasNext();
            } catch (ServiceConfigurationError | LinkageError e) {
                logger.warn("Skipping invalid ModelProvider service declaration", e);
                break;
            }
            if (!hasNext) {
                break;
            }
            try {
                providers.add(iterator.next());
            } catch (ServiceConfigurationError | LinkageError e) {
                logger.warn("Skipping invalid ModelProvider service declaration", e);
            }
        }
        return providers;
    }

    private static boolean containsProvider(
            List<ModelProvider> providers, ModelProvider candidate) {
        for (ModelProvider provider : providers) {
            if (provider.getClass().getName().equals(candidate.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static String requireApiKey(String envKey, String modelId) {
        String v = env(envKey);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable "
                            + envKey
                            + " is required to auto-create model: "
                            + modelId);
        }
        return v;
    }

    private static String buildNotFoundMessage(String modelId) {
        return "Cannot resolve model: \""
                + modelId
                + "\".\n\nPossible causes:\n"
                + "  - No named model registered with this name. Use ModelRegistry.register(\""
                + modelId
                + "\", instance).\n"
                + "  - No matching provider factory or extension SPI provider. Remaining built-in"
                + " providers: dashscope, gemini, anthropic, ollama.\n"
                + "    Format: \"<provider>:<model-name>\", e.g. \"openai:gpt-5.5\","
                + " \"dashscope:qwen-max\", \"dashscope:qwen3.7-plus\".\n"
                + "  - OpenAI models require the agentscope-extensions-model-openai module on the"
                + " classpath and OPENAI_API_KEY.\n"
                + "  - DashScope short form: \"qwen*\" model ids (e.g. \"qwen-max\","
                + " \"qwen3.7-plus\") require DASHSCOPE_API_KEY.\n"
                + "  - Missing API key environment variable (e.g., DASHSCOPE_API_KEY).";
    }
}
