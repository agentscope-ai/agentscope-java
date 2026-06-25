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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
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
    @EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
    void resolve_dashscopeShortFormat_createsDashScopeChatModel() {
        Model m = ModelRegistry.resolve("qwen-max");
        assertInstanceOf(DashScopeChatModel.class, m);
    }

    @Test
    void resolve_unknownModel_throwsWithHelpMessage() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ModelRegistry.resolve("totally-unknown-id"));
        assertTrue(ex.getMessage().contains("Cannot resolve model"));
        assertTrue(ex.getMessage().contains("OPENAI_API_KEY"));
    }

    @Test
    void resolve_caching_returnsSameInstance() {
        Model a = ModelRegistry.resolve("ollama:llama3");
        Model b = ModelRegistry.resolve("ollama:llama3");
        assertSame(a, b);
    }

    @Test
    void registerFactory_userFactory_takesPriorityOverBuiltin() {
        Model custom = new StubModel("custom-openai");
        ModelRegistry.registerFactory("openai:(.+)", id -> custom);
        assertSame(custom, ModelRegistry.resolve("openai:anything"));
    }

    @Test
    void canResolve_openAiWithoutExtension_returnsFalse() {
        assertFalse(ModelRegistry.canResolve("openai:gpt-5.5"));
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
