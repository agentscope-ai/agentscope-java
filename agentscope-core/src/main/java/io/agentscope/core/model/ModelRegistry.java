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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry for resolving {@link Model} instances from string identifiers. User-registered
 * factories take precedence over providers loaded from the {@link ModelProvider} SPI.
 *
 * <h2>Concept dictionary</h2>
 *
 * <p><b>{@code modelId} identifies WHAT to create; the pair of {@code modelId} and the creation
 * context identifies the created instance. A modelId alone never identifies a Model instance —
 * the creation context completes the identity.</b>
 *
 * <ul>
 *   <li><b>providerId</b> — routing key, the prefix of a modelId ({@code dashscope}); see {@link
 *       ModelProvider#providerId()}.
 *   <li><b>modelName</b> — the bare catalog name sent to the upstream API ({@code qwen-max});
 *       what {@link Model#getModelName()} returns.
 *   <li><b>modelId</b> — the recipe string {@code providerId + ":" + modelName} ({@code
 *       dashscope:qwen-max}); the routing argument of {@code resolve}, {@code registerFactory},
 *       and {@code supports}.
 *   <li><b>{@link ModelCreationContext}</b> — the creation inputs: "with what to build it".
 *   <li><b>modelCreationContextIdentity</b> — the controlled projection of a creation context
 *       that forms the context-derived component of an instance identity: {@code legacy}, {@code
 *       standard:<fingerprint>}, or {@code explicit:<cacheId>}.
 *   <li><b>ModelIdentity</b> — the instance identity {@code (modelId,
 *       modelCreationContextIdentity)}; anchored in code by the internal cache key.
 *       Registered instances additionally provide a string handle (modelInstanceId) for a
 *       whole instance.
 * </ul>
 *
 * <h2>modelId format and matching rules</h2>
 *
 * <p>{@code <providerId>:<modelName>} is the recommended canonical form; actual routing follows
 * these matching rules:
 *
 * <table border="1">
 *   <caption>Input forms and their resolution path</caption>
 *   <tr><th>Input form</th><th>Match rule</th><th>Routes to</th></tr>
 *   <tr><td>{@code <providerId>:<modelName>} (canonical, recommended)</td>
 *       <td>SPI provider pattern</td><td>the matching extension module</td></tr>
 *   <tr><td>any @-free string (including canonical ids)</td><td>model-instance registration,
 *       exact match, preceded only by composite dispatch</td>
 *       <td>the instance registered via {@code register(modelInstanceId, model)}</td></tr>
 *   <tr><td>any string</td><td>user factory regex, full-string {@link Pattern#matches}</td>
 *       <td>{@code registerFactory} routing</td></tr>
 *   <tr><td>{@code qwen*} bare name (sole legacy exception)</td>
 *       <td>DashScope {@code qwen.+}</td><td>the DashScope module</td></tr>
 *   <tr><td>{@code contextId@modelId} (composite identity)</td>
 *       <td>split on the reserved {@code @} (see {@link #resolve(String)})</td>
 *       <td>{@code resolve(modelId, getCreationContext(contextId))}</td></tr>
 * </table>
 *
 * <p>The {@code @} character is reserved for the composite model identity syntax {@code
 * contextId@modelId}: {@link #register(String, Model)} instance ids and {@link
 * #registerCreationContext(String, ModelCreationContext)} context ids must not contain it, so a
 * registration can never shadow a composite identity. The {@code #} character is reserved for
 * the rotation-generation cache-id suffix ({@code contextId#generation}) that {@link
 * #registerCreationContext(String, ModelCreationContext)} derives: context ids and hand-built
 * {@link ModelCreationContext.Builder#cacheId(String)} values must not contain it, so a named
 * generation identity can never collide with a hand-built cache id, and rotating one id can
 * never purge another id's entries.
 *
 * <p>The registry holds two physically separate, structurally isomorphic instance slots ("string
 * identity → Model instance"): {@code modelInstances} for instances the user pinned explicitly
 * (never auto-evicted — eviction would be an unrecoverable loss), and {@code resolvedCache} for
 * factory/SPI products (evictable because losslessly recomputable). "Cache" is reserved for the
 * latter.
 */
public final class ModelRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ModelRegistry.class);

    /** Instances pinned by the user via {@link #register(String, Model)}, keyed by model instance id. */
    private static final ConcurrentHashMap<String, Model> modelInstances =
            new ConcurrentHashMap<>();

    /** User-registered factories, newest first. */
    private static final CopyOnWriteArrayList<FactoryEntry> userFactories =
            new CopyOnWriteArrayList<>();

    /**
     * Factory-created instances. Each entry is published before creation starts, so concurrent
     * first resolutions of the same key wait for one creation instead of each creating (and
     * orphaning) their own instance.
     */
    private static final ConcurrentHashMap<ModelCacheKey, CompletableFuture<Model>> resolvedCache =
            new ConcurrentHashMap<>();

    /** Cache keys the current thread is creating, to fail fast on recursive self-resolution. */
    private static final ThreadLocal<Set<ModelCacheKey>> CREATING =
            ThreadLocal.withInitial(HashSet::new);

    /**
     * Creation contexts keyed by contextId, normalized to ENABLED with a generation-suffixed
     * cacheId ({@code contextId#<generation>}).
     */
    private static final ConcurrentHashMap<String, ModelCreationContext> creationContexts =
            new ConcurrentHashMap<>();

    /**
     * Rotation generations per contextId. The suffix flows into the cache identity ({@code
     * explicit:<contextId>#<generation>}), so a stale entry published by an in-flight
     * pre-rotation resolution is keyed under a retired generation and can never be read by a
     * resolution that starts after the rotation.
     */
    private static final ConcurrentHashMap<String, AtomicLong> contextGenerations =
            new ConcurrentHashMap<>();

    /**
     * Standard-field fingerprints recorded when explicit-identity entries were created (and
     * updated to the latest observed value when a drift warning fires), for drift warning
     * only; the explicit cacheId itself stays authoritative. Keyed by the full cache key so
     * one cacheId shared across model ids keeps independent records.
     */
    private static final ConcurrentHashMap<ModelCacheKey, String> explicitFingerprints =
            new ConcurrentHashMap<>();

    /** SPI providers discovered so far; {@code null} until first load, reset by {@link #reloadProviders()}. */
    private static volatile List<ModelProvider> serviceProviders;

    private ModelRegistry() {}

    /**
     * Registers a whole {@link Model} instance under a unique {@code modelInstanceId}. {@link #resolve(String)} returns this instance
     * for an exact {@code modelInstanceId} match (no caching of factory-created instances applies).
     *
     * <p>In plain terms: {@code modelInstanceId} is just a name you give this {@link Model}
     * instance — {@code resolve(name)} hands back exactly this object.
     *
     * <p>The model instance id is trimmed. At most one instance is bound per id at any time;
     * re-registering an id with a different instance replaces the registration (logged as a warning).
     * Instance ids share the string namespace with model ids: in the single-argument {@link
     * #resolve(String)} identity path an exact instance-id match takes precedence over factory
     * routing (shadowing semantics), while {@link #resolve(String, ModelCreationContext)} never
     * consults registered instances — its two parameters are the derivation dimensions. A model
     * instance id is a registry handle to a whole instance, distinct from {@link
     * Model#getModelName()} (the provider-side catalog name).
     *
     * @throws IllegalArgumentException if the trimmed model instance id is blank or contains {@code @}
     *     (reserved for the {@code contextId@modelId} model identity syntax)
     */
    public static void register(String modelInstanceId, Model model) {
        Objects.requireNonNull(modelInstanceId, "modelInstanceId");
        Objects.requireNonNull(model, "model");
        String trimmed = modelInstanceId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("modelInstanceId must not be blank");
        }
        if (trimmed.indexOf('@') >= 0) {
            throw new IllegalArgumentException(
                    "modelInstanceId must not contain '@' — it is reserved for the"
                            + " contextId@modelId model identity syntax: \""
                            + trimmed
                            + "\"");
        }
        Model previous = modelInstances.put(trimmed, model);
        if (previous != null && previous != model) {
            logger.warn(
                    "Replaced model instance registration \"{}\": resolve(String) now returns the"
                        + " new instance; callers still holding the previous one are unaffected.",
                    trimmed);
        }
    }

    /**
     * Removes the instance registration for {@code modelInstanceId} (trimmed), the inverse of {@link
     * #register(String, Model)}. Subsequent resolutions of this id follow the regular resolution
     * order (cache, user factories, SPI providers).
     *
     * <p>Factory-created instances cached before the instance registration remain cached and become
     * reachable again once the registration is removed; call {@link #evictResolvedCache(ModelCreationContext,
     * String)} with a {@code null} context to also force fresh creation. The removed instance is
     * not closed: the registry does not own it, and
     * callers that already hold one may still be using it.
     *
     * @return the removed instance, or {@code null} if nothing was registered under this id
     */
    public static Model unregister(String modelInstanceId) {
        Objects.requireNonNull(modelInstanceId, "modelInstanceId");
        return modelInstances.remove(modelInstanceId.trim());
    }

    /**
     * Returns the model registered under {@code modelInstanceId} (trimmed), or {@code null}. The
     * query half of the {@link #register(String, Model) register}/{@link #unregister(String)
     * unregister} family: returns exactly the registered instance — never creates one, consults
     * factories, or routes to providers (that is {@link #resolve(String)}).
     */
    public static Model getRegistered(String modelInstanceId) {
        Objects.requireNonNull(modelInstanceId, "modelInstanceId");
        return modelInstances.get(modelInstanceId.trim());
    }

    /**
     * Registers a factory matched against the full {@code modelId} string using {@link
     * Pattern#matches}. Newly registered factories are consulted before older user registrations
     * and before SPI providers.
     *
     * @param modelIdRegex regex with semantics of Pattern#matches(CharSequence) on the full
     *     model id, including the {@code providerId} prefix — write it against the complete id,
     *     not the bare model name
     * @param factory creates a {@link Model} from the full model id
     */
    public static void registerFactory(String modelIdRegex, ModelFactory factory) {
        Objects.requireNonNull(modelIdRegex, "modelIdRegex");
        Objects.requireNonNull(factory, "factory");
        registerFactory(modelIdRegex, (modelId, context) -> factory.create(modelId));
    }

    /**
     * Registers a context-aware factory matched against the full {@code modelId} string using
     * {@link Pattern#matches}. Newly registered factories are consulted before older user
     * registrations and before SPI providers.
     *
     * <p>Cached instances whose model id matches the new pattern are evicted, so the next
     * {@link #resolve} of such an id is served by this factory. Evicted instances are not closed:
     * the registry does not own them, and callers that already hold one may still be using it.
     *
     * @param modelIdRegex regex with semantics of Pattern#matches(CharSequence) on the full
     *     model id, including the {@code providerId} prefix — write it against the complete id,
     *     not the bare model name
     * @param factory creates a {@link Model} from the full model id and creation context
     */
    public static void registerFactory(String modelIdRegex, ContextModelFactory factory) {
        Objects.requireNonNull(modelIdRegex, "modelIdRegex");
        Objects.requireNonNull(factory, "factory");
        Pattern pattern = Pattern.compile(modelIdRegex);
        // Add first, then evict: resolve() publishes its cache entry before it looks up a
        // factory, so a resolution that raced past the add and picked an older factory has
        // already published its entry by the time we evict, and that entry is removed here.
        userFactories.add(0, new FactoryEntry(pattern, factory));
        resolvedCache
                .keySet()
                .removeIf(key -> pattern.matcher(key.modelId()).matches() && evictEntry(key));
    }

    /**
     * String-keyed eviction by model id, optionally scoped to one creation context. With a
     * {@code contextId}: removes exactly the {@code (explicit:<contextId>, modelId)} entry
     * (a present id must be registered — fail-fast on typos). Without one ({@code null} or
     * blank): removes every cached entry of that model id across all cache identities. The
     * model id is always required — deliberate protection against accidental over-deletion;
     * to clear a whole context's entries use {@link #evictResolvedCacheForContext(String)}.
     * Instance registrations and user factories are not affected; evicted instances are not
     * closed.
     *
     * <p>Note: a literal {@code null} contextId is ambiguous against the {@link
     * ModelCreationContext} overload at the call site — pass a blank string or a typed
     * variable instead.
     *
     * @throws IllegalArgumentException if the model id is blank, or a present {@code
     *     contextId} is not registered
     */
    public static void evictResolvedCache(String contextId, String modelId) {
        String trimmedModelId = requireNonBlank(modelId, "modelId");
        String trimmedContextId = contextId == null ? null : contextId.trim();
        if (trimmedContextId == null || trimmedContextId.isEmpty()) {
            evictResolvedCache((ModelCreationContext) null, trimmedModelId);
            return;
        }
        evictResolvedCache(getCreationContext(trimmedContextId), trimmedModelId);
    }

    /**
     * Evicts every cached entry created under the creation context registered as {@code
     * contextId} — across all registration generations (identity {@code explicit:<contextId>}
     * and {@code explicit:<contextId>#<generation>}), regardless of model id — the explicit
     * by-context form of {@link #evictResolvedCache(ModelCreationContext, String)}'s identity
     * wildcard. Instance registrations and user factories are not affected; evicted instances
     * are not closed.
     *
     * @throws IllegalArgumentException if the {@code contextId} is blank or not registered
     */
    public static void evictResolvedCacheForContext(String contextId) {
        String trimmed = requireNonBlank(contextId, "contextId");
        getCreationContext(trimmed); // fail fast on unregistered ids
        evictContextIdentity(trimmed);
    }

    /**
     * Evicts cached factory-created instances selected by creation context and model id, either
     * of which may be {@code null} as a wildcard, but not both.
     *
     * <p>A {@code null} context matches every cache identity, so {@code (null, modelId)} evicts
     * all cached entries of that id. A {@code null} or blank model id matches every id, so
     * {@code (context, null)} evicts all instances cached under the context's identity. Both
     * present evicts the precise {@code (identity, id)} entry; both absent is rejected.
     *
     * <p>The identity is derived with the same rules as {@link #resolve(String,
     * ModelCreationContext)}: the empty context selects the {@code resolve(String)} entries,
     * {@code cacheId(...)} selects explicit identities, and other enabled contexts select the
     * standard field fingerprint, so the same field values that created the entries must be
     * passed. Contexts that are never cached ({@link CachePolicy#DISABLED} or non-empty {@link
     * CachePolicy#DEFAULT}) have no entries, so the call is a no-op; an ENABLED context with
     * options/components but no {@code cacheId} is rejected with an exception — it can never
     * have produced a cached entry. A model id is trimmed and matched exactly; a blank id is
     * treated as absent.
     *
     * <p>Instance registrations and user factories are not affected; a resolution whose creation is
     * still in flight when this method runs may publish a cache entry afterwards, created under
     * the current registry state. Evicted instances are not closed: the registry does not own
     * them, and callers that already hold one may still be using it.
     */
    public static void evictResolvedCache(ModelCreationContext context, String modelId) {
        String raw = modelId == null ? null : modelId.trim();
        String trimmed = raw == null || raw.isEmpty() ? null : raw;
        if (context == null && trimmed == null) {
            throw new IllegalArgumentException("context and modelId must not both be null");
        }
        if (context != null && trimmed != null) {
            // Precise selection: compute the same key resolve would use and remove it directly.
            ModelCacheKey key = cacheKey(trimmed, context);
            if (key != null && resolvedCache.remove(key) != null) {
                forgetExplicitFingerprint(key);
            }
            return;
        }
        if (context != null) {
            String identity = modelCreationContextIdentity(context);
            if (identity == null) {
                return; // such contexts are never cached
            }
            resolvedCache
                    .keySet()
                    .removeIf(
                            key ->
                                    key.modelCreationContextIdentity().equals(identity)
                                            && evictEntry(key));
            return;
        }
        resolvedCache.keySet().removeIf(key -> key.modelId().equals(trimmed) && evictEntry(key));
    }

    /**
     * Resolves a model identity string. The composite form {@code contextId@modelId} (e.g.
     * {@code mytokenplan01@dashscope:qwen-plus}) resolves the model id with the creation
     * context registered under {@code contextId} — cached under the current registration
     * generation's identity ({@code explicit:<contextId>#<generation>}), so rotation via
     * {@link #registerCreationContext(String, ModelCreationContext)} applies to resolutions
     * started after it. Any other string resolves
     * as: registered instance ({@code modelInstanceId}) first, then the flat chain of {@link
     * #resolve(String, ModelCreationContext)} with the empty context (cache → user factory →
     * SPI). The instance check comes first on purpose: an explicit pin via {@link
     * #register(String, Model)} outranks pattern-based routing and the internal cache — a
     * registration must never be shadowed by derived artifacts. Instance ids and context ids
     * never contain {@code @} (rejected at registration), so a composite cannot be shadowed.
     *
     * @param modelIdentity composite {@code contextId@modelId}, a modelId, a modelInstanceId,
     *     or a factory-matched string
     * @throws IllegalArgumentException if the identity is blank, its {@code contextId} is not
     *     registered, or resolution fails
     */
    public static Model resolve(String modelIdentity) {
        Objects.requireNonNull(modelIdentity, "modelIdentity");
        String trimmed = modelIdentity.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("modelIdentity must not be blank");
        }
        // Instance ids and context ids cannot contain '@', so the first '@' is the composite
        // separator; any later '@' belongs to the model id.
        int at = trimmed.indexOf('@');
        if (at > 0 && at < trimmed.length() - 1) {
            return resolve(trimmed.substring(at + 1), getCreationContext(trimmed.substring(0, at)));
        }
        // A bare modelInstanceId points at a pinned whole instance; identity space only.
        Model registered = modelInstances.get(trimmed);
        if (registered != null) {
            return registered;
        }
        return resolve(trimmed, ModelCreationContext.empty());
    }

    /**
     * Resolves a {@link Model} by its two derivation dimensions — model id ("what to create")
     * and creation context ("with what inputs"): cache when enabled, then user factories
     * (newest first), then SPI providers.
     *
     * <p>Registered instances are part of the single-argument {@link #resolve(String)} identity
     * path only: an instance id passed here is treated as a plain model id and resolves through
     * factories/providers or fails with not-found — an instance is a whole pinned object, while
     * this overload derives a model from creation inputs, and the two are not mixed. Without a
     * context, call {@link #resolve(String)}; for a registered instance, {@link
     * #getRegistered(String)}.
     *
     * <p>Non-empty context resolution is not cached unless the context uses
     * {@link CachePolicy#ENABLED}.
     *
     * <p>For cached resolutions, concurrent first calls with the same key share a single creation
     * and receive the same instance. A failed creation is not cached.
     *
     * @throws IllegalArgumentException if the id cannot be resolved, the context cannot be safely
     *     cached, or creation fails
     */
    public static Model resolve(String modelId, ModelCreationContext context) {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(context, "context");
        String trimmed = modelId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }

        ModelCacheKey cacheKey = cacheKey(trimmed, context);
        if (cacheKey == null) {
            return create(trimmed, context);
        }
        CompletableFuture<Model> pending = new CompletableFuture<>();
        CompletableFuture<Model> existing = resolvedCache.putIfAbsent(cacheKey, pending);
        if (existing != null) {
            if (!existing.isDone() && CREATING.get().contains(cacheKey)) {
                // A factory resolving its own id would otherwise wait on itself forever.
                throw new IllegalStateException(
                        "Recursive resolution of model id while it is being created: " + trimmed);
            }
            Model cached = awaitCached(existing);
            warnOnExplicitFingerprintDrift(cacheKey, context);
            return cached;
        }
        Set<ModelCacheKey> creating = CREATING.get();
        creating.add(cacheKey);
        try {
            Model created = create(trimmed, context);
            // Record before completing: waiters wake from awaitCached and immediately run
            // the drift check, which must already see this entry's fingerprint.
            recordExplicitFingerprint(cacheKey, context);
            pending.complete(created);
            return created;
        } catch (RuntimeException | Error e) {
            // Do not cache failures: waiters see this error, later callers retry creation.
            resolvedCache.remove(cacheKey, pending);
            pending.completeExceptionally(e);
            throw e;
        } finally {
            creating.remove(cacheKey);
        }
    }

    /** Waits for a shared in-flight creation, rethrowing the original failure unchanged. */
    private static Model awaitCached(CompletableFuture<Model> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }

    /**
     * Creates a model via the first matching user factory, or the matching SPI provider when no
     * factory matches. Wraps failures and null results in {@link IllegalArgumentException}.
     */
    private static Model create(String trimmed, ModelCreationContext context) {
        FactoryEntry entry = findMatchingUserEntry(trimmed);
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
            return created;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Failed to create model for id: " + trimmed + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns {@code true} if {@link #resolve(String)} would succeed for this identity (without
     * creating an instance): the composite {@code contextId@modelId} checks both parts — an
     * unregistered {@code contextId} yields {@code false}; any other string follows the flat
     * chain, where a registered model instance id resolves only through this single-argument
     * path.
     *
     * @throws IllegalArgumentException if multiple SPI providers claim the model id
     */
    public static boolean canResolve(String modelIdentity) {
        if (modelIdentity == null) {
            return false;
        }
        String trimmed = modelIdentity.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        int at = trimmed.indexOf('@');
        if (at > 0 && at < trimmed.length() - 1) {
            // Direct map read: an unregistered contextId is a plain false, not an error.
            ModelCreationContext context = creationContexts.get(trimmed.substring(0, at).trim());
            return context != null && canResolve(trimmed.substring(at + 1), context);
        }
        if (modelInstances.containsKey(trimmed)) {
            return true;
        }
        return canResolve(trimmed, ModelCreationContext.empty());
    }

    /**
     * Returns {@code true} if {@link #resolve(String, ModelCreationContext)} can find a matching
     * factory/provider pattern (without creating an instance). Registered instances belong to
     * the single-argument {@link #resolve(String)} identity path and do not count here.
     *
     * @throws IllegalArgumentException if multiple SPI providers claim the id
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
        return findMatchingUserEntry(trimmed) != null
                || findServiceProvider(trimmed, context) != null;
    }

    /**
     * Registers a creation context under {@code contextId}, normalized so resolutions via
     * {@link #getCreationContext(String)} cache under one identity per registration
     * generation: the context is rebuilt with {@link CachePolicy#ENABLED} and {@code
     * cacheId(contextId + "#" + generation)}, where the generation increments on every
     * re-registration; all other fields are kept as supplied.
     *
     * <p>In plain terms: {@code contextId} is just a name you give this {@link
     * ModelCreationContext}. {@code getCreationContext(name)} hands the (normalized) context
     * back, and the composite identity {@code name@modelId} resolves with it — the
     * credentials/endpoint pairing travels with one string.
     *
     * <p><b>Rotation semantics.</b> Re-registering the same {@code contextId} starts a new
     * generation and purges every cached entry of previous generations. The rotation takes
     * effect for resolutions that <em>start</em> after the re-registration, including the
     * cache path: a new-generation resolution never reads a previous generation's entry. An
     * in-flight pre-rotation resolution may still complete with — and locally return — the
     * old configuration, and may publish under its retired generation key; that key is
     * unreachable for post-rotation resolutions and is removed by the next rotation's purge.
     *
     * <p><b>Credential copy boundary.</b> Providers copy context fields into the instances
     * they create, and contexts are immutable — rotation replaces the registry entry with a
     * new object instead of mutating one. Instances permanently hold the credentials they
     * were created with: there is no live link to the registry. Rotation guarantees fresh
     * credentials only for resolutions starting after it; instances already beyond the
     * registry's reach (models passed directly to agents, captured resolve results, in-flight
     * calls inside the residual window) keep old credentials until their holders release
     * them. A full leak response is: rotate here + revoke the old key at the provider +
     * holders release old instances; this registry covers only the first.
     *
     * @throws IllegalArgumentException if the trimmed contextId is blank, or contains {@code @}
     *     (reserved for the {@code contextId@modelId} model identity syntax) or {@code #}
     *     (reserved for the rotation-generation cache-id suffix)
     */
    public static void registerCreationContext(String contextId, ModelCreationContext context) {
        Objects.requireNonNull(contextId, "contextId");
        Objects.requireNonNull(context, "context");
        String trimmed = contextId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("contextId must not be blank");
        }
        if (trimmed.indexOf('@') >= 0) {
            throw new IllegalArgumentException(
                    "contextId must not contain '@' — it is reserved for the contextId@modelId"
                            + " model identity syntax: \""
                            + trimmed
                            + "\"");
        }
        if (trimmed.indexOf('#') >= 0) {
            throw new IllegalArgumentException(
                    "contextId must not contain '#' — it is reserved for the"
                            + " rotation-generation cache-id suffix (contextId#generation): \""
                            + trimmed
                            + "\"");
        }
        long generation =
                contextGenerations
                        .computeIfAbsent(trimmed, k -> new AtomicLong())
                        .incrementAndGet();
        ModelCreationContext normalized =
                context.toBuilder()
                        .cachePolicy(CachePolicy.ENABLED)
                        .registryCacheId(trimmed + "#" + generation)
                        .build();
        creationContexts.put(trimmed, normalized);
        // Purge every generation's entries (the previous one included): an in-flight
        // resolution holding the old context may still publish under the retired generation
        // key afterwards — that key is unreachable for post-rotation resolutions and is
        // removed by the next rotation's purge.
        evictContextIdentity(trimmed);
    }

    /**
     * Returns the creation context registered under {@code contextId} (trimmed), for the
     * holder-side two-step composition {@code resolve(modelId, getCreationContext(contextId))}.
     *
     * @throws IllegalArgumentException if nothing is registered under this id — failing fast
     *     here beats an opaque NullPointerException from {@code resolve(modelId, null)}
     */
    public static ModelCreationContext getCreationContext(String contextId) {
        Objects.requireNonNull(contextId, "contextId");
        String trimmed = contextId.trim();
        ModelCreationContext context = creationContexts.get(trimmed);
        if (context == null) {
            throw new IllegalArgumentException(
                    "No ModelCreationContext registered for contextId: \"" + trimmed + "\"");
        }
        return context;
    }

    /**
     * Returns {@code true} if a creation context is registered under {@code contextId} (trimmed) —
     * the probing half of {@link #getCreationContext(String)}: an unregistered id is a plain
     * {@code false}, never an exception. A {@code null} or blank id is also {@code false}
     * (mirroring {@link #canResolve(String)}), so this is safe as a guard before the two-step
     * composition {@code resolve(modelId, getCreationContext(contextId))}.
     */
    public static boolean hasCreationContext(String contextId) {
        return contextId != null && creationContexts.containsKey(contextId.trim());
    }

    /**
     * Removes the registration for {@code contextId} and evicts every cached entry created
     * from it, across all registration generations. Returns the removed context, or {@code
     * null} if nothing was registered. Instances already created are not closed: the
     * registry does not own them.
     *
     * <p>The internal rotation-generation counter for this id is deliberately kept: it must
     * stay monotonic across unregister/re-register cycles, otherwise a re-registration would
     * restart at generation 1 and could collide with a stale retired-generation entry from
     * the previous cycle.
     */
    public static ModelCreationContext unregisterCreationContext(String contextId) {
        Objects.requireNonNull(contextId, "contextId");
        String trimmed = contextId.trim();
        ModelCreationContext removed = creationContexts.remove(trimmed);
        if (removed != null) {
            evictContextIdentity(trimmed);
        }
        return removed;
    }

    /**
     * Clears registered instances, user-registered factories, registered creation contexts
     * with their rotation generations, the factory-resolution cache with its drift-warning
     * side table, and cached SPI providers. Intended for tests.
     */
    public static void reset() {
        modelInstances.clear();
        userFactories.clear();
        creationContexts.clear();
        contextGenerations.clear();
        explicitFingerprints.clear();
        resolvedCache.clear();
        reloadProviders();
    }

    /**
     * Clears the cached SPI providers so the next resolution can rediscover providers from the
     * current classloader context, and evicts cached entries that can only have come from SPI
     * providers (a changed provider set invalidates their routing dimension). Entries matching
     * a user factory survive: they were necessarily produced by that factory. Evicted
     * instances are not closed.
     */
    public static void reloadProviders() {
        serviceProviders = null;
        // Evict entries that can only have come from SPI providers: a changed provider set
        // (e.g. a TCCL/classloader change) invalidates their routing dimension. Entries
        // matching a user factory survive — registerFactory evicts on registration, so a
        // surviving matching entry was necessarily produced by that factory and does not
        // depend on the SPI set. This also clears stale references into old classloaders.
        resolvedCache
                .keySet()
                .removeIf(key -> findMatchingUserEntry(key.modelId()) == null && evictEntry(key));
    }

    /** Creates a {@link Model} from the full model id, ignoring the resolution context. */
    @FunctionalInterface
    public interface ModelFactory {
        Model create(String modelId);
    }

    /** Creates a {@link Model} from the full model id plus the caller's creation context. */
    @FunctionalInterface
    public interface ContextModelFactory {
        Model create(String modelId, ModelCreationContext context);
    }

    /** One user-registered factory with its full-string match pattern. */
    private record FactoryEntry(Pattern pattern, ContextModelFactory factory) {}

    /**
     * Cache key anchoring one instance identity: what to create ({@code modelId}) paired with the
     * creation-context identity it was built under.
     */
    private record ModelCacheKey(String modelId, String modelCreationContextIdentity) {}

    /** Returns the newest user factory whose pattern full-matches the id, or {@code null}. */
    private static FactoryEntry findMatchingUserEntry(String modelId) {
        for (FactoryEntry e : userFactories) {
            if (e.pattern().matcher(modelId).matches()) {
                return e;
            }
        }
        return null;
    }

    /**
     * Finds the single SPI provider claiming the id. Providers whose {@code supports} throws are
     * skipped with a warning; multiple claimants fail with an error listing all candidates.
     */
    private static ModelProvider findServiceProvider(String modelId, ModelCreationContext context) {
        ModelProvider matched = null;
        List<ModelProvider> ambiguous = null;
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
            if (matched == null) {
                matched = provider;
            } else {
                if (ambiguous == null) {
                    ambiguous = new ArrayList<>();
                }
                ambiguous.add(provider);
            }
        }
        if (ambiguous != null) {
            throw new IllegalArgumentException(
                    "Multiple ModelProvider implementations support model id \""
                            + modelId
                            + "\": "
                            + describeProviders(matched, ambiguous)
                            + ". Remove the conflicting provider from the classpath, or register"
                            + " an explicit factory via registerFactory to choose one.");
        }
        return matched;
    }

    /** Formats the ambiguity error's candidate list: {@code [first, rest...]}. */
    private static String describeProviders(ModelProvider first, List<ModelProvider> rest) {
        StringBuilder sb = new StringBuilder("[").append(providerDescription(first));
        for (ModelProvider provider : rest) {
            sb.append(", ").append(providerDescription(provider));
        }
        return sb.append(']').toString();
    }

    /** Renders one candidate as class name, providerId, and module/classpath origin. */
    private static String providerDescription(ModelProvider provider) {
        Module module = provider.getClass().getModule();
        String origin =
                module != null && module.isNamed() ? "module " + module.getName() : "classpath";
        return provider.getClass().getName()
                + " (providerId="
                + provider.providerId()
                + ", "
                + origin
                + ")";
    }

    /**
     * Builds the registry cache key for a model resolution, or {@code null} when the context is
     * not cacheable.
     */
    private static ModelCacheKey cacheKey(String modelId, ModelCreationContext context) {
        String identity = modelCreationContextIdentity(context);
        return identity == null ? null : new ModelCacheKey(modelId, identity);
    }

    /**
     * Derives the creation-context identity for cache keying, or {@code null} when such contexts
     * are never cached.
     *
     * <p>The rules intentionally avoid treating arbitrary context objects as comparable:
     *
     * <ul>
     *   <li>Empty contexts keep the legacy behavior and cache only by {@code modelId}. An
     *       ENABLED context with no standard fields expresses the same creation inputs and maps
     *       to the same {@code legacy} identity.
     *   <li>Non-empty contexts are not cached unless {@link CachePolicy#ENABLED} is selected.
     *   <li>An explicit {@code cacheId} is the authoritative identity for complex contexts.
     *   <li>Without {@code cacheId}, only standard simple fields are fingerprinted. Options and
     *       components are opaque and must not rely on deep hashing or object {@code hashCode()}.
     * </ul>
     */
    private static String modelCreationContextIdentity(ModelCreationContext context) {
        if (context.isEmpty()) {
            return "legacy";
        }
        if (context.getCachePolicy() == CachePolicy.DISABLED
                || context.getCachePolicy() == CachePolicy.DEFAULT) {
            return null;
        }

        String cacheId = context.getCacheId();
        if (cacheId != null) {
            return "explicit:" + cacheId;
        }
        if (context.hasOpaqueCacheInputs()) {
            throw new IllegalArgumentException(
                    "ModelCreationContext cachePolicy(ENABLED) with options or components "
                            + "requires an explicit cacheId");
        }
        if (hasNoStandardFields(context)) {
            // Same creation inputs as the empty context: both are the "legacy" identity.
            // Without this, the same inputs would split into two buckets and two Model
            // instances (two HTTP clients).
            return "legacy";
        }
        return "standard:" + standardContextFingerprint(context);
    }

    /** Returns {@code true} when all five standard fields are null (identity equals legacy). */
    private static boolean hasNoStandardFields(ModelCreationContext context) {
        return context.getApiKey() == null
                && context.getBaseUrl() == null
                && context.getEndpointPath() == null
                && context.getStream() == null
                && context.getEnableThinking() == null;
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
                + lenPrefixed(sha256Hex(context.getApiKey()))
                + "|baseUrl="
                + lenPrefixed(valueOf(context.getBaseUrl()))
                + "|endpointPath="
                + lenPrefixed(valueOf(context.getEndpointPath()))
                + "|stream="
                + lenPrefixed(valueOf(context.getStream()))
                + "|enableThinking="
                + lenPrefixed(valueOf(context.getEnableThinking()));
    }

    /** Length-prefixed encoding: unambiguous even when field values contain '|' or '='. */
    private static String lenPrefixed(String value) {
        return value.length() + ":" + value;
    }

    /**
     * Null-safe string rendering for fingerprint components: {@code null} becomes the empty
     * string, which no real field can equal (the builder trims empty strings to null) — unlike a
     * literal sentinel such as {@code "<null>"}, which a real value could match.
     */
    private static String valueOf(Object value) {
        return value == null ? "" : value.toString();
    }

    /** Records the creation-time fingerprint of an explicit-identity entry for drift warning. */
    private static void recordExplicitFingerprint(ModelCacheKey key, ModelCreationContext context) {
        if (explicitCacheIdOf(key) != null) {
            explicitFingerprints.put(key, standardContextFingerprint(context));
        }
    }

    /**
     * Warns (once per drift) when the standard fields of an explicit-identity resolution differ
     * from the fields that created the cached instance. Warning only: the cacheId stays
     * authoritative and field values are never logged.
     */
    private static void warnOnExplicitFingerprintDrift(
            ModelCacheKey key, ModelCreationContext context) {
        String cacheId = explicitCacheIdOf(key);
        if (cacheId == null) {
            return;
        }
        String recorded = explicitFingerprints.get(key);
        String current = standardContextFingerprint(context);
        if (recorded != null && !recorded.equals(current)) {
            logger.warn(
                    "ModelCreationContext fields for cacheId \"{}\" (model id \"{}\") differ from"
                            + " the previously recorded fields for this cache entry; the explicit"
                            + " cacheId stays authoritative.",
                    cacheId,
                    key.modelId());
            explicitFingerprints.put(key, current);
        }
    }

    /** Extracts the cacheId from an {@code explicit:...} identity, or {@code null} otherwise. */
    private static String explicitCacheIdOf(ModelCacheKey key) {
        String identity = key.modelCreationContextIdentity();
        return identity.startsWith("explicit:") ? identity.substring("explicit:".length()) : null;
    }

    /** Side-table cleanup companion for cache-entry removals. */
    private static boolean evictEntry(ModelCacheKey key) {
        forgetExplicitFingerprint(key);
        return true;
    }

    /** Drops the drift-warning fingerprint for a removed cache entry, if it had one. */
    private static void forgetExplicitFingerprint(ModelCacheKey key) {
        explicitFingerprints.remove(key);
    }

    /**
     * Evicts every cached entry created under {@code explicit:<contextId>} or any of its
     * generation-suffixed identities ({@code explicit:<contextId>#<generation>}), regardless of
     * model id — the cache-side half of context rotation.
     */
    private static void evictContextIdentity(String contextId) {
        String identity = "explicit:" + contextId;
        String generational = identity + "#";
        resolvedCache
                .keySet()
                .removeIf(
                        key -> {
                            String candidate = key.modelCreationContextIdentity();
                            return (candidate.equals(identity)
                                            || candidate.startsWith(generational))
                                    && evictEntry(key);
                        });
    }

    /** Hex SHA-256 digest, or the {@code "<null>"} sentinel for absent values. */
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

    /** Returns the cached provider list, lazily discovering it from TCCL plus this class's loader. */
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

    /** Loads providers via {@link ServiceLoader} from one classloader, skipping broken declarations. */
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

    /** Dedupe check by class name across the two classloader passes. */
    private static boolean containsProvider(
            List<ModelProvider> providers, ModelProvider candidate) {
        for (ModelProvider provider : providers) {
            if (provider.getClass().getName().equals(candidate.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    /** Builds the not-found help text: format hints, module hints, and installed provider ids. */
    private static String buildNotFoundMessage(String modelId) {
        return "Cannot resolve model: \""
                + modelId
                + "\".\n\nPossible causes:\n"
                + "  - No model instance registered under this id. Use ModelRegistry.register(\""
                + modelId
                + "\", instance).\n"
                + "  - No matching provider factory or extension SPI provider.\n"
                + "    Format: \"<provider>:<model-name>\", e.g. \"openai:gpt-5.5\","
                + " \"dashscope:qwen-max\".\n"
                + "  - Installed SPI providers: "
                + installedProviderIds()
                + ".\n"
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

    /** Sorted distinct provider ids of the installed SPI providers, or {@code "(none)"}. */
    private static String installedProviderIds() {
        String joined =
                loadServiceProviders().stream()
                        .map(ModelProvider::providerId)
                        .distinct()
                        .sorted()
                        .collect(Collectors.joining(", "));
        return joined.isEmpty() ? "(none)" : joined;
    }

    /** Trimmed non-blank check for eviction selectors: blank keys would over-select. */
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
