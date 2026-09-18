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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
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
 * loaded from the {@link ModelProvider} SPI.
 *
 * <p>Provider extension modules read API keys from their own standard environment
 * variables when auto-creating models.
 */
public final class ModelRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ModelRegistry.class);

    private static final ConcurrentHashMap<String, Model> namedModels = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ProviderEntry> userFactories =
            new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<ModelCacheKey, Model> resolvedCache =
            new ConcurrentHashMap<>();
    private static volatile List<ModelProvider> serviceProviders;

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
     * and before SPI providers.
     *
     * @param modelNameRegex regex with semantics of Pattern#matches(CharSequence) on the
     *     full model id
     * @param factory creates a {@link Model} from the full model id
     */
    public static void registerFactory(String modelNameRegex, ModelFactory factory) {
        Objects.requireNonNull(modelNameRegex, "modelNameRegex");
        Objects.requireNonNull(factory, "factory");
        registerFactory(modelNameRegex, (modelId, context) -> factory.create(modelId));
    }

    /**
     * Registers a context-aware factory matched against the full {@code modelId} string using
     * {@link Pattern#matches}. Newly registered factories are consulted before older user
     * registrations and before SPI providers.
     *
     * @param modelNameRegex regex with semantics of Pattern#matches(CharSequence) on the
     *     full model id
     * @param factory creates a {@link Model} from the full model id and creation context
     */
    public static void registerFactory(String modelNameRegex, ContextModelFactory factory) {
        Objects.requireNonNull(modelNameRegex, "modelNameRegex");
        Objects.requireNonNull(factory, "factory");
        Pattern pattern = Pattern.compile(modelNameRegex);
        userFactories.add(0, new ProviderEntry(pattern, factory));
    }

    /**
     * Resolves a {@link Model} for the given id: named registration first, then cached
     * factory-created instance, then user factories (newest first), then SPI providers.
     *
     * @throws IllegalArgumentException if the id cannot be resolved or creation fails
     */
    public static Model resolve(String modelId) {
        return resolve(modelId, ModelCreationContext.empty());
    }

    /**
     * Resolves a {@link Model} for the given id and creation context: named registration first,
     * then cache when enabled, then user factories (newest first), then SPI providers.
     *
     * <p>Non-empty context resolution is not cached unless the context uses
     * {@link CachePolicy#ENABLED}.
     *
     * @throws IllegalArgumentException if the id cannot be resolved, the context cannot be safely
     * cached, or creation fails
     */
    public static Model resolve(String modelId, ModelCreationContext context) {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(context, "context");
        String trimmed = modelId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }

        Model named = namedModels.get(trimmed);
        if (named != null) {
            return named;
        }

        ModelCacheKey cacheKey = cacheKey(trimmed, context);
        if (cacheKey != null) {
            Model cached = resolvedCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        ProviderEntry entry = findMatchingUserEntry(trimmed);
        ModelProvider provider = entry == null ? findServiceProvider(trimmed, context) : null;
        if (entry == null && provider == null) {
            throw new IllegalArgumentException(buildNotFoundMessage(trimmed));
        }

        try {
            Model created;
            if (entry != null) {
                created = entry.factory().create(trimmed, context);
                Objects.requireNonNull(created, "ModelFactory returned null for: " + trimmed);
            } else {
                created = provider.create(trimmed, context);
                Objects.requireNonNull(
                        created,
                        "ModelProvider "
                                + provider.providerId()
                                + " returned null for: "
                                + trimmed);
            }
            if (cacheKey != null) {
                resolvedCache.put(cacheKey, created);
            }
            return created;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Failed to create model for id: " + trimmed + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns {@code true} if {@link #resolve(String)} can find a named model or a matching factory/
     * provider pattern (without creating an instance).
     */
    public static boolean canResolve(String modelId) {
        return canResolve(modelId, ModelCreationContext.empty());
    }

    /**
     * Returns {@code true} if {@link #resolve(String, ModelCreationContext)} can find a named model
     * or a matching factory/provider pattern (without creating an instance).
     */
    public static boolean canResolve(String modelId, ModelCreationContext context) {
        Objects.requireNonNull(context, "context");
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
                || findServiceProvider(trimmed, context) != null;
    }

    /**
     * Clears named models, user-registered factories, the factory-resolution cache,
     * and cached SPI providers. Intended for tests.
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

    @FunctionalInterface
    public interface ContextModelFactory {
        Model create(String modelId, ModelCreationContext context);
    }

    private record ProviderEntry(Pattern pattern, ContextModelFactory factory) {}

    private record ModelCacheKey(String modelId, String cacheIdentity) {}

    private static ProviderEntry findMatchingUserEntry(String modelId) {
        for (ProviderEntry e : userFactories) {
            if (e.pattern().matcher(modelId).matches()) {
                return e;
            }
        }
        return null;
    }

    private static ModelProvider findServiceProvider(String modelId, ModelCreationContext context) {
        ModelProvider matched = null;
        for (ModelProvider provider : loadServiceProviders()) {
            boolean supports;
            try {
                supports = provider.supports(modelId, context);
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

    /**
     * Builds the registry cache key for a model resolution.
     *
     * <p>The rules intentionally avoid treating arbitrary context objects as comparable:
     *
     * <ul>
     *   <li>Empty contexts keep the legacy behavior and cache only by {@code modelId}.
     *   <li>Non-empty contexts are not cached unless {@link CachePolicy#ENABLED} is selected.
     *   <li>An explicit {@code cacheId} is the authoritative identity for complex contexts.
     *   <li>Without {@code cacheId}, only standard simple fields are fingerprinted. Options and
     *       components are opaque and must not rely on deep hashing or object {@code hashCode()}.
     * </ul>
     */
    private static ModelCacheKey cacheKey(String modelId, ModelCreationContext context) {
        if (context.isEmpty()) {
            return new ModelCacheKey(modelId, "legacy");
        }
        if (context.getCachePolicy() == CachePolicy.DISABLED
                || context.getCachePolicy() == CachePolicy.DEFAULT) {
            return null;
        }

        String cacheId = context.getCacheId();
        if (cacheId != null) {
            return new ModelCacheKey(modelId, "explicit:" + cacheId);
        }
        if (context.hasOpaqueCacheInputs()) {
            throw new IllegalArgumentException(
                    "ModelCreationContext cachePolicy(ENABLED) with options or components "
                            + "requires an explicit cacheId");
        }
        return new ModelCacheKey(modelId, "standard:" + standardContextFingerprint(context));
    }

    /**
     * Creates a cache fingerprint from provider-neutral simple fields only.
     *
     * <p>API keys are never written to the key in plain text. The digest is not intended as a
     * security boundary; it only prevents accidental secret exposure in heap dumps, logs, and test
     * diagnostics.
     */
    private static String standardContextFingerprint(ModelCreationContext context) {
        return "apiKeySha256="
                + sha256Hex(context.getApiKey())
                + "|baseUrl="
                + valueOf(context.getBaseUrl())
                + "|endpointPath="
                + valueOf(context.getEndpointPath())
                + "|stream="
                + valueOf(context.getStream())
                + "|enableThinking="
                + valueOf(context.getEnableThinking());
    }

    private static String valueOf(Object value) {
        return value == null ? "<null>" : value.toString();
    }

    private static String sha256Hex(String value) {
        if (value == null) {
            return "<null>";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
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

    private static String buildNotFoundMessage(String modelId) {
        return "Cannot resolve model: \""
                + modelId
                + "\".\n\nPossible causes:\n"
                + "  - No named model registered with this name. Use ModelRegistry.register(\""
                + modelId
                + "\", instance).\n"
                + "  - No matching provider factory or extension SPI provider.\n"
                + "    Format: \"<provider>:<model-name>\", e.g. \"openai:gpt-5.5\","
                + " \"gemini:gemini-2.0-flash\", \"dashscope:qwen-max\","
                + " \"dashscope:qwen3.7-plus\".\n"
                + "  - OpenAI models require the agentscope-extensions-model-openai module on the"
                + " classpath and OPENAI_API_KEY.\n"
                + "  - Gemini models require the agentscope-extensions-model-gemini module on the"
                + " classpath and GEMINI_API_KEY.\n"
                + "  - Anthropic models require the agentscope-extensions-model-anthropic module"
                + " on the classpath.\n"
                + "  - DashScope models require the agentscope-extensions-model-dashscope module"
                + " on the classpath and DASHSCOPE_API_KEY.\n"
                + "  - DashScope short form: \"qwen*\" model ids (e.g. \"qwen-max\","
                + " \"qwen3.7-plus\") are provided by agentscope-extensions-model-dashscope.\n"
                + "  - Ollama models require the agentscope-extensions-model-ollama module on the"
                + " classpath. OLLAMA_BASE_URL is optional and defaults to"
                + " http://localhost:11434.\n"
                + "  - Missing API key environment variable (e.g., DASHSCOPE_API_KEY).";
    }
}
