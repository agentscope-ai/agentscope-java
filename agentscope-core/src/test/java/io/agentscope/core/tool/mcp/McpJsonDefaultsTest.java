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
package io.agentscope.core.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpJsonDefaultsTest {

    @Test
    void shouldResolveProvidersFromContextClassLoader() {
        assertNotNull(McpJsonDefaults.jsonMapper());
        assertNotNull(McpJsonDefaults.jsonSchemaValidator());
    }

    @Test
    void shouldFallbackWhenContextClassLoaderCannotSeeMcpProviders() {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader isolatedClassLoader = new ClassLoader(null) {};

        assertTrue(
                ServiceLoader.load(McpJsonMapperSupplier.class, isolatedClassLoader)
                        .findFirst()
                        .isEmpty());
        assertTrue(
                ServiceLoader.load(JsonSchemaValidatorSupplier.class, isolatedClassLoader)
                        .findFirst()
                        .isEmpty());

        try {
            Thread.currentThread().setContextClassLoader(isolatedClassLoader);

            assertNotNull(McpJsonDefaults.jsonMapper());
            assertNotNull(McpJsonDefaults.jsonSchemaValidator());
            assertNotNull(
                    McpClientBuilder.create("isolated-loader")
                            .stdioTransport("echo", "test")
                            .buildSync());
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Test
    void shouldFallbackWhenContextClassLoaderIsNull() {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(null);

            assertNotNull(McpJsonDefaults.jsonMapper());
            assertNotNull(McpJsonDefaults.jsonSchemaValidator());
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Test
    void shouldFallbackWhenContextClassLoaderHasBrokenServiceDeclaration(@TempDir Path tempDir)
            throws Exception {
        writeServiceFile(tempDir, McpJsonMapperSupplier.class.getName(), "missing.DoesNotExist");

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader brokenClassLoader =
                new URLClassLoader(new URL[] {tempDir.toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(brokenClassLoader);

            assertNotNull(McpJsonDefaults.jsonMapper());
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Test
    void shouldThrowWhenNoDefaultSupplierIsAvailable() throws Exception {
        Method loadSupplier =
                McpJsonDefaults.class.getDeclaredMethod("loadSupplier", Class.class, String.class);
        loadSupplier.setAccessible(true);

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(McpJsonDefaults.class.getClassLoader());

            InvocationTargetException exception =
                    assertThrows(
                            InvocationTargetException.class,
                            () ->
                                    loadSupplier.invoke(
                                            null, MissingSupplier.class, "MissingSupplier"));

            IllegalStateException cause =
                    assertInstanceOf(IllegalStateException.class, exception.getCause());
            assertEquals("No default MissingSupplier implementation found", cause.getMessage());
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private static void writeServiceFile(Path root, String serviceName, String providerName)
            throws Exception {
        Path serviceDirectory = root.resolve("META-INF/services");
        Files.createDirectories(serviceDirectory);
        Files.writeString(
                serviceDirectory.resolve(serviceName), providerName + System.lineSeparator());
    }

    private interface MissingSupplier extends Supplier<Object> {}
}
