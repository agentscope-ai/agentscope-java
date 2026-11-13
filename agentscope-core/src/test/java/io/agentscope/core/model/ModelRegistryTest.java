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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.agentscope.core.message.Msg;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

class ModelRegistryTest {

    @BeforeEach
    void setUp() {
        ModelRegistry.reset();
    }

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    void resolve_namedInstance_returnsRegistered() {
        Model m = new StubModel("x");
        ModelRegistry.register("my-model", m);
        assertSame(m, ModelRegistry.resolve("my-model"));
    }

    @Test
    void resolve_namedInstance_precedesFactory() {
        Model named = new StubModel("named");
        ModelRegistry.register("openai:fake", named);
        AtomicInteger factoryCalls = new AtomicInteger();
        ModelRegistry.registerFactory(
                "openai:(.+)",
                id -> {
                    factoryCalls.incrementAndGet();
                    return new StubModel("factory");
                });
        assertSame(named, ModelRegistry.resolve("openai:fake"));
        assertSame(named, ModelRegistry.resolve("openai:fake"));
        assertTrue(factoryCalls.get() == 0);
    }

    @Test
    void unregister_removesNamedRegistration_andFallsBackToCachedFactoryInstance() {
        ModelRegistry.registerFactory("local:(.+)", id -> new StubModel(id));
        Model cached = ModelRegistry.resolve("local:alpha");

        Model named = new StubModel("named");
        ModelRegistry.register("local:alpha", named);
        assertSame(named, ModelRegistry.resolve("local:alpha"));

        assertSame(named, ModelRegistry.unregister("local:alpha"));
        assertNull(ModelRegistry.unregister("never-registered"));
        assertSame(cached, ModelRegistry.resolve("local:alpha"));
    }

    @Test
    void resolve_unknownModel_throwsWithHelpMessage() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ModelRegistry.resolve("totally-unknown-id"));
        assertTrue(ex.getMessage().contains("Cannot resolve model"));
        assertTrue(ex.getMessage().contains("OPENAI_API_KEY"));
        assertTrue(ex.getMessage().contains("agentscope-extensions-model-dashscope"));
    }

    @Test
    void resolve_caching_returnsSameInstance() {
        ModelRegistry.registerFactory("local:(.+)", id -> new StubModel(id));

        Model a = ModelRegistry.resolve("local:alpha");
        Model b = ModelRegistry.resolve("local:alpha");
        assertSame(a, b);
    }

    @Test
    void registerFactory_evictsCachedInstancesMatchingNewPattern() {
        ModelRegistry.registerFactory("acme:(.+)", id -> new StubModel("old"));
        ModelRegistry.registerFactory("other:(.+)", id -> new StubModel("other"));
        assertEquals("old", ModelRegistry.resolve("acme:m1").getModelName());
        Model other = ModelRegistry.resolve("other:m1");

        ModelRegistry.registerFactory("acme:(.+)", id -> new StubModel("new"));

        assertEquals("new", ModelRegistry.resolve("acme:m1").getModelName());
        assertSame(other, ModelRegistry.resolve("other:m1"));
    }

    @Test
    void registerFactory_evictsEntryWhoseCreationIsStillInFlight() throws Exception {
        CountDownLatch inOldFactory = new CountDownLatch(1);
        CountDownLatch releaseOldFactory = new CountDownLatch(1);
        ModelRegistry.registerFactory(
                "acme:(.+)",
                id -> {
                    inOldFactory.countDown();
                    await(releaseOldFactory);
                    return new StubModel("old");
                });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Model> inFlight = pool.submit(() -> ModelRegistry.resolve("acme:m1"));
            assertTrue(inOldFactory.await(5, TimeUnit.SECONDS));

            ModelRegistry.registerFactory("acme:(.+)", id -> new StubModel("new"));
            releaseOldFactory.countDown();

            // The call that started before the re-registration may finish with the old factory,
            // but its result must not stay cached.
            assertEquals("old", inFlight.get(5, TimeUnit.SECONDS).getModelName());
            assertEquals("new", ModelRegistry.resolve("acme:m1").getModelName());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void evictResolvedCache_entryWhoseCreationIsStillInFlight() throws Exception {
        CountDownLatch inFactory = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        AtomicInteger factoryCalls = new AtomicInteger();
        ModelRegistry.registerFactory(
                "acme:(.+)",
                id -> {
                    factoryCalls.incrementAndGet();
                    inFactory.countDown();
                    await(releaseFactory);
                    return new StubModel(id);
                });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Model> inFlight = pool.submit(() -> ModelRegistry.resolve("acme:m1"));
            assertTrue(inFactory.await(5, TimeUnit.SECONDS));

            ModelRegistry.evictResolvedCache((ModelCreationContext) null, "acme:m1");
            releaseFactory.countDown();

            // The call that started before the eviction finishes with its instance,
            // but that instance must not stay cached.
            Model inFlightModel = inFlight.get(5, TimeUnit.SECONDS);
            assertEquals("acme:m1", inFlightModel.getModelName());
            assertNotSame(inFlightModel, ModelRegistry.resolve("acme:m1"));
            assertEquals(2, factoryCalls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void evictResolvedCache_selectionDimensions_removeOnlyMatchingEntries() {
        ModelRegistry.registerFactory("a:(.+)", (id, context) -> new StubModel(id));
        ModelRegistry.registerFactory("b:(.+)", (id, context) -> new StubModel(id));
        ModelCreationContext tenantA = tenantContext("tenant-a");
        ModelCreationContext tenantB = tenantContext("tenant-b");
        Model legacyA = ModelRegistry.resolve("a:m1");
        Model legacyB = ModelRegistry.resolve("b:m1");
        Model tenantAA = ModelRegistry.resolve("a:m1", tenantA);
        Model tenantAB = ModelRegistry.resolve("a:m1", tenantB);
        Model tenantBB = ModelRegistry.resolve("b:m1", tenantB);

        // Precise selection: the id is trimmed; other identities and other ids stay cached.
        ModelRegistry.evictResolvedCache(tenantA, "  a:m1  ");
        assertNotSame(tenantAA, ModelRegistry.resolve("a:m1", tenantA));
        assertSame(tenantAB, ModelRegistry.resolve("a:m1", tenantB));
        assertSame(legacyA, ModelRegistry.resolve("a:m1"));

        // Id wildcard: every identity of that id, other ids untouched.
        ModelRegistry.evictResolvedCache((ModelCreationContext) null, "a:m1");
        assertNotSame(legacyA, ModelRegistry.resolve("a:m1"));
        assertNotSame(tenantAB, ModelRegistry.resolve("a:m1", tenantB));
        assertSame(tenantBB, ModelRegistry.resolve("b:m1", tenantB));

        // Identity wildcard: a blank id behaves like null and spans model ids.
        Model tenantABAgain = ModelRegistry.resolve("a:m1", tenantB);
        ModelRegistry.evictResolvedCache(tenantB, "   ");
        assertNotSame(tenantABAgain, ModelRegistry.resolve("a:m1", tenantB));
        assertNotSame(tenantBB, ModelRegistry.resolve("b:m1", tenantB));

        // Named registrations survive an id-wide eviction and win resolution.
        Model named = new StubModel("named");
        ModelRegistry.register("a:m1", named);
        ModelRegistry.evictResolvedCache((ModelCreationContext) null, "a:m1");
        assertSame(named, ModelRegistry.resolve("a:m1"));

        // Empty context selects the resolve(String) entries.
        assertSame(legacyB, ModelRegistry.resolve("b:m1"));
        ModelRegistry.evictResolvedCache(ModelCreationContext.empty(), "b:m1");
        assertNotSame(legacyB, ModelRegistry.resolve("b:m1"));
    }

    @Test
    void evictResolvedCache_missingSelectionOrUnsafeContext_rejectedOrNoOp() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        Model legacy = ModelRegistry.resolve("local:alpha");
        ModelCreationContext withOption =
                ModelCreationContext.builder()
                        .option("custom", "value")
                        .cachePolicy(CachePolicy.ENABLED)
                        .build();
        ModelCreationContext defaultPolicy = ModelCreationContext.builder().apiKey("key-a").build();

        // Both selection dimensions absent is rejected; a blank id counts as absent.
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.evictResolvedCache((ModelCreationContext) null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.evictResolvedCache((ModelCreationContext) null, "   "));
        // Opaque enabled contexts require a cacheId, like resolve.
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.evictResolvedCache(withOption, null));
        // Contexts that are never cached have no entries to evict.
        ModelRegistry.evictResolvedCache(defaultPolicy, null);
        ModelRegistry.evictResolvedCache(defaultPolicy, "local:alpha");
        assertSame(legacy, ModelRegistry.resolve("local:alpha"));
    }

    @Test
    void evictResolvedCache_strings_preciseAndIdWide_modelIdRequired() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelRegistry.registerCreationContext(
                "t1", ModelCreationContext.builder().apiKey("k1").build());
        Model legacy = ModelRegistry.resolve("local:alpha");
        Model t1Alpha =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("t1"));
        Model t1Beta = ModelRegistry.resolve("local:beta", ModelRegistry.getCreationContext("t1"));

        // Precise: only (explicit:t1, local:alpha); neighbouring entries untouched.
        ModelRegistry.evictResolvedCache("t1", "local:alpha");
        assertNotSame(
                t1Alpha,
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("t1")));
        assertSame(
                t1Beta,
                ModelRegistry.resolve("local:beta", ModelRegistry.getCreationContext("t1")));
        assertSame(legacy, ModelRegistry.resolve("local:alpha"));

        // Absent contextId (null or blank): every identity of that model id.
        Model secondLegacy = ModelRegistry.resolve("local:alpha");
        Model secondT1Alpha =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("t1"));
        ModelRegistry.evictResolvedCache("", "local:alpha");
        assertNotSame(secondLegacy, ModelRegistry.resolve("local:alpha"));
        assertNotSame(
                secondT1Alpha,
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("t1")));
        assertSame(
                t1Beta,
                ModelRegistry.resolve("local:beta", ModelRegistry.getCreationContext("t1")));

        // The model id is always required; a present contextId must be registered.
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.evictResolvedCache("t1", "   "));
        assertThrows(
                NullPointerException.class,
                () -> ModelRegistry.evictResolvedCache("t1", (String) null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.evictResolvedCache("no-ctx", "local:x"));
    }

    @Test
    void evictResolvedCacheForContext_clearsWholeBucket_onlyThatIdentity() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelRegistry.registerCreationContext(
                "t1", ModelCreationContext.builder().apiKey("k1").build());
        ModelRegistry.registerCreationContext(
                "t2", ModelCreationContext.builder().apiKey("k2").build());
        Model legacy = ModelRegistry.resolve("local:alpha");
        Model t1Alpha =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("t1"));
        Model t1Beta = ModelRegistry.resolve("local:beta", ModelRegistry.getCreationContext("t1"));
        Model t2Alpha =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("t2"));

        ModelRegistry.evictResolvedCacheForContext("t1");

        assertNotSame(
                t1Alpha,
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("t1")));
        assertNotSame(
                t1Beta,
                ModelRegistry.resolve("local:beta", ModelRegistry.getCreationContext("t1")));
        assertSame(
                t2Alpha,
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("t2")));
        assertSame(legacy, ModelRegistry.resolve("local:alpha"));

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.evictResolvedCacheForContext("no-ctx"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.evictResolvedCacheForContext("   "));
    }

    private static ModelCreationContext tenantContext(String cacheId) {
        return ModelCreationContext.builder()
                .apiKey("key-" + cacheId)
                .cachePolicy(CachePolicy.ENABLED)
                .cacheId(cacheId)
                .build();
    }

    @Test
    void resolve_concurrentFirstResolution_createsSingleInstance() throws Exception {
        int threads = 8;
        AtomicInteger factoryCalls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ModelRegistry.registerFactory(
                "race:(.+)",
                id -> {
                    factoryCalls.incrementAndGet();
                    sleep(50);
                    return new StubModel(id);
                });
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Model>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    await(start);
                                    return ModelRegistry.resolve("race:m");
                                }));
            }
            start.countDown();
            Model first = results.get(0).get(5, TimeUnit.SECONDS);
            for (Future<Model> result : results) {
                assertSame(first, result.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, factoryCalls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void resolve_failedCreation_isNotCached() {
        AtomicInteger factoryCalls = new AtomicInteger();
        ModelRegistry.registerFactory(
                "flaky:(.+)",
                id -> {
                    if (factoryCalls.incrementAndGet() == 1) {
                        throw new IllegalStateException("boom");
                    }
                    return new StubModel(id);
                });

        assertThrows(IllegalArgumentException.class, () -> ModelRegistry.resolve("flaky:m"));
        Model recovered = ModelRegistry.resolve("flaky:m");
        assertSame(recovered, ModelRegistry.resolve("flaky:m"));
        assertEquals(2, factoryCalls.get());
    }

    @Test
    void resolve_factoryResolvingItsOwnId_failsInsteadOfHanging() {
        AtomicInteger factoryCalls = new AtomicInteger();
        ModelRegistry.registerFactory(
                "self:(.+)",
                id ->
                        factoryCalls.incrementAndGet() == 1
                                ? ModelRegistry.resolve(id)
                                : new StubModel(id));

        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    IllegalArgumentException e =
                            assertThrows(
                                    IllegalArgumentException.class,
                                    () -> ModelRegistry.resolve("self:m"));
                    assertInstanceOf(IllegalStateException.class, e.getCause());
                });
        // The failed attempt must not leave a stuck entry behind.
        assertEquals("self:m", ModelRegistry.resolve("self:m").getModelName());
        assertEquals(2, factoryCalls.get());
    }

    @Test
    void resolve_withNonEmptyDefaultContext_doesNotCache() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelCreationContext context = ModelCreationContext.builder().apiKey("key-a").build();

        Model a = ModelRegistry.resolve("local:alpha", context);
        Model b = ModelRegistry.resolve("local:alpha", context);

        assertNotSame(a, b);
    }

    @Test
    void resolve_withDisabledCachePolicy_doesNotCache() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("key-a")
                        .cachePolicy(CachePolicy.DISABLED)
                        .build();

        Model a = ModelRegistry.resolve("local:alpha", context);
        Model b = ModelRegistry.resolve("local:alpha", context);

        assertNotSame(a, b);
    }

    @Test
    void resolve_withEnabledCachePolicyAndCacheId_usesCacheId() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelCreationContext first =
                ModelCreationContext.builder()
                        .apiKey("key-a")
                        .cachePolicy(CachePolicy.ENABLED)
                        .cacheId("tenant-a")
                        .build();
        ModelCreationContext sameCacheIdDifferentSecret =
                ModelCreationContext.builder()
                        .apiKey("key-b")
                        .cachePolicy(CachePolicy.ENABLED)
                        .cacheId("tenant-a")
                        .build();
        ModelCreationContext differentCacheId =
                ModelCreationContext.builder()
                        .apiKey("key-a")
                        .cachePolicy(CachePolicy.ENABLED)
                        .cacheId("tenant-b")
                        .build();

        Model a = ModelRegistry.resolve("local:alpha", first);
        Model b = ModelRegistry.resolve("local:alpha", sameCacheIdDifferentSecret);
        Model c = ModelRegistry.resolve("local:alpha", differentCacheId);

        assertSame(a, b);
        assertNotSame(a, c);
    }

    @Test
    void resolve_withEnabledCachePolicyAndStandardFields_derivesSafeCacheKey() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelCreationContext first =
                ModelCreationContext.builder()
                        .apiKey("key-a")
                        .baseUrl("https://one.example")
                        .stream(false)
                        .cachePolicy(CachePolicy.ENABLED)
                        .build();
        ModelCreationContext same =
                ModelCreationContext.builder()
                        .apiKey("key-a")
                        .baseUrl("https://one.example")
                        .stream(false)
                        .cachePolicy(CachePolicy.ENABLED)
                        .build();
        ModelCreationContext differentApiKey =
                ModelCreationContext.builder()
                        .apiKey("key-b")
                        .baseUrl("https://one.example")
                        .stream(false)
                        .cachePolicy(CachePolicy.ENABLED)
                        .build();

        Model a = ModelRegistry.resolve("local:alpha", first);
        Model b = ModelRegistry.resolve("local:alpha", same);
        Model c = ModelRegistry.resolve("local:alpha", differentApiKey);

        assertSame(a, b);
        assertNotSame(a, c);
    }

    @Test
    void resolve_withEnabledCachePolicyAndOpaqueInputs_requiresCacheId() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelCreationContext withOption =
                ModelCreationContext.builder()
                        .option("custom", "value")
                        .cachePolicy(CachePolicy.ENABLED)
                        .build();
        ModelCreationContext withComponent =
                ModelCreationContext.builder()
                        .component(StringBuilder.class, new StringBuilder("opaque"))
                        .cachePolicy(CachePolicy.ENABLED)
                        .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.resolve("local:alpha", withOption));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.resolve("local:alpha", withComponent));
    }

    @Test
    void registerFactory_contextAwareFactory_receivesContext() {
        ModelRegistry.registerFactory(
                "local:(.+)", (id, context) -> new StubModel(context.getBaseUrl() + "/" + id));

        Model model =
                ModelRegistry.resolve(
                        "local:alpha",
                        ModelCreationContext.builder().baseUrl("https://example.com").build());

        assertTrue(model.getModelName().contains("https://example.com/local:alpha"));
    }

    @Test
    void modelCreationContext_toStringRedactsApiKey() {
        ModelCreationContext context = ModelCreationContext.builder().apiKey("secret-key").build();

        assertFalse(context.toString().contains("secret-key"));
        assertTrue(context.toString().contains("[REDACTED]"));
    }

    @Test
    void modelCreationContext_toBuilderCopiesExistingValues() {
        StringBuilder component = new StringBuilder("component");
        ModelCreationContext original =
                ModelCreationContext.builder()
                        .apiKey("secret-key")
                        .baseUrl("https://example.com")
                        .option("custom", "value")
                        .component(StringBuilder.class, component)
                        .cachePolicy(CachePolicy.ENABLED)
                        .cacheId("tenant-a")
                        .build();

        ModelCreationContext copy = original.toBuilder().baseUrl("https://other.example").build();

        assertTrue(original.getBaseUrl().equals("https://example.com"));
        assertTrue(copy.getApiKey().equals("secret-key"));
        assertTrue(copy.getBaseUrl().equals("https://other.example"));
        assertTrue(copy.option("custom", String.class).equals("value"));
        assertSame(component, copy.component(StringBuilder.class));
        assertTrue(copy.getCachePolicy() == CachePolicy.ENABLED);
        assertTrue(copy.getCacheId().equals("tenant-a"));
    }

    @Test
    void registerFactory_userFactory_resolvesMatchingPattern() {
        Model custom = new StubModel("custom-openai");
        ModelRegistry.registerFactory("openai:(.+)", id -> custom);
        assertSame(custom, ModelRegistry.resolve("openai:anything"));
    }

    @Test
    void canResolve_openAiWithoutExtension_returnsFalse() {
        assertFalse(ModelRegistry.canResolve("openai:gpt-5.5"));
    }

    @Test
    void canResolve_geminiWithoutExtension_returnsFalse() {
        assertFalse(ModelRegistry.canResolve("gemini:gemini-2.0-flash"));
    }

    @Test
    void canResolve_anthropicWithoutExtension_returnsFalse() {
        assertFalse(ModelRegistry.canResolve("anthropic:claude-sonnet-4.5"));
    }

    @Test
    void canResolve_dashscopeWithoutExtension_returnsFalse() {
        assertFalse(ModelRegistry.canResolve("dashscope:qwen-max"));
        assertFalse(ModelRegistry.canResolve("qwen-max"));
    }

    @Test
    void canResolve_ollamaWithoutExtension_returnsFalse() {
        assertFalse(ModelRegistry.canResolve("ollama:llama3"));
    }

    @Test
    void resolve_spiProvider_returnsModel() {
        Model model = ModelRegistry.resolve("fake-spi:alpha");
        assertInstanceOf(StubModel.class, model);
        assertTrue(model.getModelName().contains("fake-spi:alpha"));
    }

    @Test
    void registerFactory_userFactory_takesPriorityOverSpiProvider() {
        Model custom = new StubModel("custom-spi");
        ModelRegistry.registerFactory("fake-spi:(.+)", id -> custom);
        assertSame(custom, ModelRegistry.resolve("fake-spi:anything"));
    }

    @Test
    void canResolve_unknownPattern_returnsFalse() {
        org.junit.jupiter.api.Assertions.assertFalse(
                ModelRegistry.canResolve("unknown-provider:x"));
    }

    @Test
    void resolve_blankModelId_throws() {
        assertThrows(IllegalArgumentException.class, () -> ModelRegistry.resolve("   "));
    }

    @Test
    void loadServiceProviders_fallsBackToRegistryClassLoaderWhenTcclCannotSeeServices()
            throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader emptyClassLoader = new URLClassLoader(new URL[0], null)) {
            Thread.currentThread().setContextClassLoader(emptyClassLoader);
            ModelRegistry.reloadProviders();

            assertTrue(ModelRegistry.canResolve("fake-spi:alpha"));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            ModelRegistry.reset();
        }
    }

    @Test
    void loadServiceProviders_ignoresBrokenTcclServiceDeclaration(@TempDir Path tempDir)
            throws Exception {
        writeServiceFile(tempDir, "missing.DoesNotExist");

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader brokenClassLoader =
                new URLClassLoader(new URL[] {tempDir.toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(brokenClassLoader);
            ModelRegistry.reloadProviders();

            assertTrue(ModelRegistry.canResolve("fake-spi:alpha"));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            ModelRegistry.reset();
        }
    }

    @Test
    void reloadProviders_rediscoversProvidersFromChangedTccl(@TempDir Path tempDir)
            throws Exception {
        writeServiceFile(tempDir, TcclOnlyModelProvider.class.getName());

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader tcclProviderClassLoader =
                new URLClassLoader(
                        new URL[] {tempDir.toUri().toURL()},
                        ModelRegistryTest.class.getClassLoader())) {
            ModelRegistry.reloadProviders();
            assertFalse(ModelRegistry.canResolve("tccl-only:alpha"));

            Thread.currentThread().setContextClassLoader(tcclProviderClassLoader);
            assertFalse(ModelRegistry.canResolve("tccl-only:alpha"));

            ModelRegistry.reloadProviders();
            assertTrue(ModelRegistry.canResolve("tccl-only:alpha"));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            ModelRegistry.reset();
        }
    }

    @Test
    void register_trimmedName_isResolvable() {
        Model m = new StubModel("x");
        ModelRegistry.register("  padded  ", m);
        assertSame(m, ModelRegistry.resolve("padded"));
        assertSame(m, ModelRegistry.getRegistered("padded"));
    }

    @Test
    void register_blankName_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.register("   ", new StubModel("x")));
    }

    @Test
    void register_nameContainingAt_throws() {
        // '@' is reserved for the contextId@modelId identity syntax; a binding must never be
        // able to shadow a composite identity string.
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ModelRegistry.register(
                                        "plan@dashscope:qwen-plus", new StubModel("x")));
        assertTrue(ex.getMessage().contains("reserved"));
    }

    @Test
    void register_sameNameDifferentInstance_replaces() {
        Model first = new StubModel("first");
        Model second = new StubModel("second");
        ModelRegistry.register("swap", first);
        ModelRegistry.register("swap", second);
        assertSame(second, ModelRegistry.resolve("swap"));
    }

    @Test
    void unregister_trimmedName_removesBinding() {
        Model m = new StubModel("x");
        ModelRegistry.register("trim-me", m);
        assertSame(m, ModelRegistry.unregister("  trim-me  "));
        assertNull(ModelRegistry.getRegistered("trim-me"));
    }

    @Test
    void resolve_enabledContextWithNoStandardFields_sharesLegacyInstance() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelCreationContext enabledEmpty =
                ModelCreationContext.builder().cachePolicy(CachePolicy.ENABLED).build();

        Model legacy = ModelRegistry.resolve("local:alpha");

        assertSame(legacy, ModelRegistry.resolve("local:alpha", enabledEmpty));
    }

    @Test
    void evictResolvedCache_eitherForm_hitsSharedLegacyEntry() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelCreationContext enabledEmpty =
                ModelCreationContext.builder().cachePolicy(CachePolicy.ENABLED).build();
        Model first = ModelRegistry.resolve("local:alpha");

        ModelRegistry.evictResolvedCache(enabledEmpty, "local:alpha");
        assertNotSame(first, ModelRegistry.resolve("local:alpha"));

        Model shared = ModelRegistry.resolve("local:alpha", enabledEmpty);
        ModelRegistry.evictResolvedCache(ModelCreationContext.empty(), "local:alpha");
        assertNotSame(shared, ModelRegistry.resolve("local:alpha", enabledEmpty));
    }

    @Test
    void resolve_enabledContextWithApiKey_staysIsolatedFromLegacyBucket() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelCreationContext withKey =
                ModelCreationContext.builder()
                        .apiKey("key-a")
                        .cachePolicy(CachePolicy.ENABLED)
                        .build();

        Model legacy = ModelRegistry.resolve("local:alpha");
        Model isolated = ModelRegistry.resolve("local:alpha", withKey);

        assertNotSame(legacy, isolated);
    }

    @Test
    void resolve_unknownModel_listsInstalledProviderIds() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ModelRegistry.resolve("totally-unknown-id"));

        assertTrue(ex.getMessage().contains("Installed SPI providers:"));
        assertTrue(ex.getMessage().contains("fake-spi"));
    }

    @Test
    void resolve_twoArgs_ignoreRegisteredInstances_evenWithEmptyContext() {
        Model named = new StubModel("named");
        ModelRegistry.register("shared:entry", named);
        ModelRegistry.registerFactory("shared:(.+)", (id, context) -> new StubModel("factory"));
        ModelCreationContext tenant = ModelCreationContext.builder().apiKey("tenant-key").build();

        assertEquals("factory", ModelRegistry.resolve("shared:entry", tenant).getModelName());
        assertEquals(
                "factory",
                ModelRegistry.resolve("shared:entry", ModelCreationContext.empty()).getModelName());
        assertSame(named, ModelRegistry.resolve("shared:entry"));
    }

    @Test
    void resolve_twoArgs_instanceIdWithoutFactory_notFound() {
        Model named = new StubModel("named");
        ModelRegistry.register("only:named", named);
        ModelCreationContext tenant = ModelCreationContext.builder().apiKey("tenant-key").build();

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ModelRegistry.resolve("only:named", tenant));
        assertTrue(ex.getMessage().contains("Cannot resolve model"));
    }

    @Test
    void canResolve_twoArgs_ignoreRegisteredInstances() {
        Model named = new StubModel("named");
        ModelRegistry.register("only:named", named);
        ModelCreationContext tenant = ModelCreationContext.builder().apiKey("tenant-key").build();

        assertTrue(ModelRegistry.canResolve("only:named"));
        assertFalse(ModelRegistry.canResolve("only:named", tenant));
        assertFalse(ModelRegistry.canResolve("only:named", ModelCreationContext.empty()));
    }

    @Test
    void getRegistered_roundTrip_andNull() {
        Model m = new StubModel("x");
        ModelRegistry.register("  rt  ", m);
        assertSame(m, ModelRegistry.getRegistered("rt"));
        assertSame(m, ModelRegistry.getRegistered("  rt  "));
        assertNull(ModelRegistry.getRegistered("never-registered"));
        assertThrows(NullPointerException.class, () -> ModelRegistry.getRegistered(null));
    }

    @Test
    void creationContext_twoContextIdsDistinct_sameIdAndContextReused() {
        ModelRegistry.registerFactory(
                "local:(.+)", (id, context) -> new StubModel(context.getApiKey() + "/" + id));
        ModelRegistry.registerCreationContext(
                "tenant-a", ModelCreationContext.builder().apiKey("key-a").build());
        ModelRegistry.registerCreationContext(
                "tenant-b", ModelCreationContext.builder().apiKey("key-b").build());

        Model a1 =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("tenant-a"));
        Model a2 =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("tenant-a"));
        Model b1 =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("tenant-b"));

        assertSame(a1, a2);
        assertNotSame(a1, b1);
        assertEquals("key-a/local:alpha", a1.getModelName());
        assertEquals("key-b/local:alpha", b1.getModelName());
    }

    @Test
    void creationContext_rotation_inFlightOldResolutionCannotPoisonNewGeneration()
            throws Exception {
        CountDownLatch firstCreateStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstCreate = new CountDownLatch(1);
        ModelRegistry.registerFactory(
                "local:(.+)",
                (id, context) -> {
                    if ("old-key".equals(context.getApiKey())) {
                        firstCreateStarted.countDown();
                        await(releaseFirstCreate);
                    }
                    return new StubModel(context.getApiKey() + "/" + id);
                });
        ModelRegistry.registerCreationContext(
                "t", ModelCreationContext.builder().apiKey("old-key").build());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Model> inFlight = pool.submit(() -> ModelRegistry.resolve("t@local:alpha"));
            assertTrue(firstCreateStarted.await(5, TimeUnit.SECONDS));

            // Rotate while the old-context creation is still in flight.
            ModelRegistry.registerCreationContext(
                    "t", ModelCreationContext.builder().apiKey("new-key").build());

            // A resolution starting AFTER the rotation must see the new credentials, even
            // though the stale creation publishes its entry afterwards (retired generation).
            Model fresh = ModelRegistry.resolve("t@local:alpha");
            assertEquals("new-key/local:alpha", fresh.getModelName());

            releaseFirstCreate.countDown();
            Model stale = inFlight.get(5, TimeUnit.SECONDS);
            assertEquals("old-key/local:alpha", stale.getModelName());
        } finally {
            releaseFirstCreate.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void creationContext_reRegister_rotatesForResolutionsStartedAfter() {
        ModelRegistry.registerFactory(
                "local:(.+)", (id, context) -> new StubModel(context.getApiKey() + "/" + id));
        ModelRegistry.registerCreationContext(
                "tenant", ModelCreationContext.builder().apiKey("old").build());

        Model before =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("tenant"));
        assertEquals("old/local:alpha", before.getModelName());

        ModelRegistry.registerCreationContext(
                "tenant", ModelCreationContext.builder().apiKey("new").build());

        Model after =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("tenant"));
        assertEquals("new/local:alpha", after.getModelName());
        assertNotSame(before, after);
    }

    @Test
    void creationContext_unregister_evictsAndReturnsPrevious() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelRegistry.registerCreationContext(
                "tenant", ModelCreationContext.builder().apiKey("key-a").build());
        Model first =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("tenant"));

        ModelCreationContext removed = ModelRegistry.unregisterCreationContext("  tenant  ");

        assertNotNull(removed);
        assertEquals("key-a", removed.getApiKey());
        assertThrows(
                IllegalArgumentException.class, () -> ModelRegistry.getCreationContext("tenant"));

        // Re-registration starts a new generation: the old entry must not be reused.
        ModelRegistry.registerCreationContext(
                "tenant", ModelCreationContext.builder().apiKey("key-b").build());
        Model second =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("tenant"));
        assertNotSame(first, second);
    }

    @Test
    void creationContext_hasCreationContext_reflectsRegistrationLifecycle() {
        assertFalse(ModelRegistry.hasCreationContext("tenant"));
        assertFalse(ModelRegistry.hasCreationContext(null));
        assertFalse(ModelRegistry.hasCreationContext("   "));

        ModelRegistry.registerCreationContext(
                "tenant", ModelCreationContext.builder().apiKey("key-a").build());
        assertTrue(ModelRegistry.hasCreationContext("tenant"));
        assertTrue(ModelRegistry.hasCreationContext("  tenant  "));

        ModelRegistry.unregisterCreationContext("tenant");
        assertFalse(ModelRegistry.hasCreationContext("tenant"));
    }

    @Test
    void creationContext_blankContextId_throws() {
        ModelCreationContext context = ModelCreationContext.builder().apiKey("key-a").build();
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.registerCreationContext("   ", context));
        assertThrows(NullPointerException.class, () -> ModelRegistry.getCreationContext(null));
    }

    @Test
    void creationContext_contextIdContainingAt_throws() {
        ModelCreationContext context = ModelCreationContext.builder().apiKey("key-a").build();
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.registerCreationContext("plan@x", context));
    }

    @Test
    void creationContext_contextIdContainingHash_throws() {
        ModelCreationContext context = ModelCreationContext.builder().apiKey("key-a").build();
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRegistry.registerCreationContext("plan#1", context));
    }

    @Test
    void modelCreationContext_cacheIdContainingHash_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelCreationContext.builder()
                                .cachePolicy(CachePolicy.ENABLED)
                                .cacheId("plan#1")
                                .build());
    }

    @Test
    void creationContext_rotation_sparesUnrelatedCacheIds() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelRegistry.registerCreationContext(
                "tenant-a", ModelCreationContext.builder().apiKey("key-a").build());
        ModelRegistry.registerCreationContext(
                "tenant-b", ModelCreationContext.builder().apiKey("key-b").build());
        ModelCreationContext manual =
                ModelCreationContext.builder()
                        .apiKey("key-m")
                        .cachePolicy(CachePolicy.ENABLED)
                        .cacheId("tenant-a-manual")
                        .build();

        Model namedA =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("tenant-a"));
        Model namedB =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("tenant-b"));
        Model manualInstance = ModelRegistry.resolve("local:alpha", manual);

        // Rotating tenant-a must not purge tenant-b's or a hand-built cache id's entries.
        ModelRegistry.registerCreationContext(
                "tenant-a", ModelCreationContext.builder().apiKey("key-a-2").build());

        Model rotated =
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("tenant-a"));
        assertNotSame(namedA, rotated);
        assertSame(
                namedB,
                ModelRegistry.resolve("local:alpha", ModelRegistry.getCreationContext("tenant-b")));
        assertSame(manualInstance, ModelRegistry.resolve("local:alpha", manual));
    }

    @Test
    void resolve_compositeIdentity_usesCreationContextAndCaches() {
        AtomicReference<String> seenApiKey = new AtomicReference<>();
        ModelRegistry.registerFactory(
                "dashscope:(.+)",
                (id, context) -> {
                    seenApiKey.set(context.getApiKey());
                    return new StubModel(id);
                });
        ModelRegistry.registerCreationContext(
                "mytokenplan01", ModelCreationContext.builder().apiKey("plan-key").build());

        Model a = ModelRegistry.resolve("mytokenplan01@dashscope:qwen-plus");

        assertEquals("plan-key", seenApiKey.get());
        assertEquals("dashscope:qwen-plus", a.getModelName());
        assertSame(a, ModelRegistry.resolve("mytokenplan01@dashscope:qwen-plus"));

        // canResolve mirrors the composite form: both parts checkable, unknown contextId false.
        assertTrue(ModelRegistry.canResolve("mytokenplan01@dashscope:qwen-plus"));
        assertFalse(ModelRegistry.canResolve("no-such-plan@dashscope:qwen-plus"));

        // Rotation applies to resolutions started after the re-registration.
        ModelRegistry.registerCreationContext(
                "mytokenplan01", ModelCreationContext.builder().apiKey("plan-key-2").build());
        Model rotated = ModelRegistry.resolve("mytokenplan01@dashscope:qwen-plus");
        assertNotSame(a, rotated);
        assertEquals("plan-key-2", seenApiKey.get());
    }

    @Test
    void resolve_plainIdentity_followsFlatChain() {
        Model named = new StubModel("named");
        ModelRegistry.register("plain-name", named);

        assertSame(named, ModelRegistry.resolve("plain-name"));
        assertThrows(
                IllegalArgumentException.class, () -> ModelRegistry.resolve("never-registered"));
        assertThrows(IllegalArgumentException.class, () -> ModelRegistry.resolve("   "));
    }

    @Test
    void resolve_compositeUnknownContext_failsFast() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ModelRegistry.resolve("no-such-plan@dashscope:x"));
        assertTrue(ex.getMessage().contains("No ModelCreationContext registered"));
    }

    @Test
    void creationContext_unregisterUnknown_returnsNull() {
        assertNull(ModelRegistry.unregisterCreationContext("never-registered"));
    }

    @Test
    void resolve_fingerprintDelimiterValues_stayDistinct() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        // Pre-hardening these two contexts collided on '|'/'=' separator confusion.
        ModelCreationContext merged =
                ModelCreationContext.builder()
                        .baseUrl("X|endpointPath=Y")
                        .cachePolicy(CachePolicy.ENABLED)
                        .build();
        ModelCreationContext split =
                ModelCreationContext.builder()
                        .baseUrl("X")
                        .endpointPath("Y")
                        .cachePolicy(CachePolicy.ENABLED)
                        .build();
        // A literal "<null>" value must not collide with an absent field.
        ModelCreationContext sentinel =
                ModelCreationContext.builder()
                        .baseUrl("<null>")
                        .cachePolicy(CachePolicy.ENABLED)
                        .build();

        Model a = ModelRegistry.resolve("local:alpha", merged);
        Model b = ModelRegistry.resolve("local:alpha", split);
        Model c = ModelRegistry.resolve("local:alpha", sentinel);

        assertNotSame(a, b);
        assertNotSame(a, c);
        assertNotSame(b, c);
    }

    @Test
    void creationContext_normalizedToEnabledWithGenerationCacheId() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelRegistry.registerCreationContext(
                "norm", ModelCreationContext.builder().apiKey("key-a").build());

        // Generation-suffixed cacheId: one cache identity per registration generation.
        ModelCreationContext normalized = ModelRegistry.getCreationContext("norm");
        assertTrue(normalized.getCachePolicy() == CachePolicy.ENABLED);
        assertEquals("norm#1", normalized.getCacheId());

        // Both the composite form and the two-step composition share that identity.
        Model viaComposite = ModelRegistry.resolve("norm@local:alpha");
        assertSame(viaComposite, ModelRegistry.resolve("local:alpha", normalized));
    }

    @Test
    void reloadProviders_keepsUserFactoryEntries_evictsSpiEntries() {
        ModelRegistry.registerFactory("user:(.+)", id -> new StubModel("user-" + id));
        Model userEntry = ModelRegistry.resolve("user:m1");
        Model spiEntry = ModelRegistry.resolve("fake-spi:alpha");

        ModelRegistry.reloadProviders();

        assertSame(userEntry, ModelRegistry.resolve("user:m1"));
        assertNotSame(spiEntry, ModelRegistry.resolve("fake-spi:alpha"));
    }

    @Test
    void findServiceProvider_ambiguousSpi_throwsListingAllCandidates(@TempDir Path tempDir)
            throws Exception {
        writeServiceFile(
                tempDir,
                AmbiguousOneProvider.class.getName()
                        + System.lineSeparator()
                        + AmbiguousTwoProvider.class.getName());

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader ambiguousClassLoader =
                new URLClassLoader(
                        new URL[] {tempDir.toUri().toURL()},
                        ModelRegistryTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(ambiguousClassLoader);
            ModelRegistry.reloadProviders();

            IllegalArgumentException resolveEx =
                    assertThrows(
                            IllegalArgumentException.class, () -> ModelRegistry.resolve("amb:m1"));
            assertTrue(resolveEx.getMessage().contains(AmbiguousOneProvider.class.getName()));
            assertTrue(resolveEx.getMessage().contains(AmbiguousTwoProvider.class.getName()));

            IllegalArgumentException canResolveEx =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> ModelRegistry.canResolve("amb:m1"));
            assertTrue(canResolveEx.getMessage().contains("Multiple ModelProvider"));

            // Non-ambiguous ids are unaffected.
            assertInstanceOf(StubModel.class, ModelRegistry.resolve("fake-spi:alpha"));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            ModelRegistry.reset();
        }
    }

    @Test
    void explicitCacheId_driftWarnsOnceButStaysAuthoritative() {
        Logger registryLogger = (Logger) LoggerFactory.getLogger(ModelRegistry.class);
        List<String> warnings = new ArrayList<>();
        AppenderBase<ILoggingEvent> appender =
                new AppenderBase<>() {
                    @Override
                    protected void append(ILoggingEvent event) {
                        if (Level.WARN.equals(event.getLevel())) {
                            warnings.add(event.getFormattedMessage());
                        }
                    }
                };
        appender.start();
        registryLogger.addAppender(appender);
        try {
            ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
            ModelCreationContext first =
                    ModelCreationContext.builder()
                            .apiKey("key-a")
                            .cachePolicy(CachePolicy.ENABLED)
                            .cacheId("tenant")
                            .build();
            ModelCreationContext drifted =
                    ModelCreationContext.builder()
                            .apiKey("key-b")
                            .cachePolicy(CachePolicy.ENABLED)
                            .cacheId("tenant")
                            .build();

            Model a = ModelRegistry.resolve("local:alpha", first);
            Model b = ModelRegistry.resolve("local:alpha", drifted);

            assertSame(a, b);
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("tenant"));
            assertFalse(warnings.get(0).contains("key-b"));

            assertSame(a, ModelRegistry.resolve("local:alpha", drifted));
            assertEquals(1, warnings.size());
        } finally {
            registryLogger.detachAppender(appender);
        }
    }

    @Test
    void explicitCacheId_sharedAcrossModelIds_keepsIndependentFingerprints() {
        Logger registryLogger = (Logger) LoggerFactory.getLogger(ModelRegistry.class);
        List<String> warnings = new ArrayList<>();
        AppenderBase<ILoggingEvent> appender =
                new AppenderBase<>() {
                    @Override
                    protected void append(ILoggingEvent event) {
                        if (Level.WARN.equals(event.getLevel())) {
                            warnings.add(event.getFormattedMessage());
                        }
                    }
                };
        appender.start();
        registryLogger.addAppender(appender);
        try {
            ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
            ModelCreationContext alpha =
                    ModelCreationContext.builder()
                            .apiKey("key-a")
                            .cachePolicy(CachePolicy.ENABLED)
                            .cacheId("tenant")
                            .build();
            ModelCreationContext beta =
                    ModelCreationContext.builder()
                            .apiKey("key-b")
                            .cachePolicy(CachePolicy.ENABLED)
                            .cacheId("tenant")
                            .build();

            Model a1 = ModelRegistry.resolve("local:alpha", alpha);
            // Same cacheId, different model id: records its own entry's fingerprint and must
            // not make (local:alpha, explicit:tenant) look drifted.
            Model b1 = ModelRegistry.resolve("local:beta", beta);
            assertNotSame(a1, b1); // distinct cache keys, distinct instances
            Model a2 = ModelRegistry.resolve("local:alpha", alpha);

            assertSame(a1, a2);
            assertEquals(0, warnings.size());
        } finally {
            registryLogger.detachAppender(appender);
        }
    }

    @Test
    void resolve_compositeModelIdContainingAt_splitsAtFirstSeparator() {
        ModelRegistry.registerFactory("local:(.+)", (id, context) -> new StubModel(id));
        ModelRegistry.registerCreationContext(
                "ctx", ModelCreationContext.builder().apiKey("key-a").build());

        // Context ids and binding names cannot contain '@', so the first '@' is the composite
        // separator and any later '@' belongs to the model id.
        Model m = ModelRegistry.resolve("ctx@local:x@y");

        assertEquals("local:x@y", m.getModelName());
    }

    private static void writeServiceFile(Path root, String providerClassName) throws Exception {
        Path serviceDir = root.resolve("META-INF/services");
        Files.createDirectories(serviceDir);
        Files.writeString(
                serviceDir.resolve("io.agentscope.core.model.spi.ModelProvider"),
                providerClassName + System.lineSeparator());
    }

    public static final class FakeSpiModelProvider
            implements io.agentscope.core.model.spi.ModelProvider {

        @Override
        public String providerId() {
            return "fake-spi";
        }

        @Override
        public boolean supports(String modelId) {
            return modelId != null && modelId.startsWith("fake-spi:");
        }

        @Override
        public Model create(String modelId) {
            return new StubModel("spi-" + modelId);
        }
    }

    public static final class TcclOnlyModelProvider
            implements io.agentscope.core.model.spi.ModelProvider {

        @Override
        public String providerId() {
            return "tccl-only";
        }

        @Override
        public boolean supports(String modelId) {
            return modelId != null && modelId.startsWith("tccl-only:");
        }

        @Override
        public Model create(String modelId) {
            return new StubModel("tccl-" + modelId);
        }
    }

    /** SPI stub: supports the shared {@code amb:} prefix to trigger ambiguity errors. */
    public static final class AmbiguousOneProvider
            implements io.agentscope.core.model.spi.ModelProvider {

        @Override
        public String providerId() {
            return "amb-one";
        }

        @Override
        public boolean supports(String modelId) {
            return modelId != null && modelId.startsWith("amb:");
        }

        @Override
        public Model create(String modelId) {
            return new StubModel("one-" + modelId);
        }
    }

    /** SPI stub: supports the shared {@code amb:} prefix to trigger ambiguity errors. */
    public static final class AmbiguousTwoProvider
            implements io.agentscope.core.model.spi.ModelProvider {

        @Override
        public String providerId() {
            return "amb-two";
        }

        @Override
        public boolean supports(String modelId) {
            return modelId != null && modelId.startsWith("amb:");
        }

        @Override
        public Model create(String modelId) {
            return new StubModel("two-" + modelId);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class StubModel implements Model {
        private final String name;

        StubModel(String name) {
            this.name = name;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return name;
        }
    }
}
