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

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * Loads the default MCP JSON components using a class loader that can see their providers.
 *
 * <p>Worker threads in executable JARs may use a context class loader that cannot access nested
 * dependencies. In that case, fall back to the AgentScope class loader.
 */
final class McpJsonDefaults {

    private McpJsonDefaults() {}

    static McpJsonMapper jsonMapper() {
        return loadSupplier(McpJsonMapperSupplier.class, "McpJsonMapper").get();
    }

    static JsonSchemaValidator jsonSchemaValidator() {
        return loadSupplier(JsonSchemaValidatorSupplier.class, "JsonSchemaValidator").get();
    }

    private static <T extends Supplier<?>> T loadSupplier(
            Class<T> serviceType, String implementationType) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        Optional<T> supplier = loadFirst(serviceType, contextClassLoader);

        ClassLoader agentScopeClassLoader = McpJsonDefaults.class.getClassLoader();
        if (supplier.isEmpty() && agentScopeClassLoader != contextClassLoader) {
            supplier = loadFirst(serviceType, agentScopeClassLoader);
        }

        return supplier.orElseThrow(
                () ->
                        new IllegalStateException(
                                "No default " + implementationType + " implementation found"));
    }

    private static <T> Optional<T> loadFirst(Class<T> serviceType, ClassLoader classLoader) {
        if (classLoader == null) {
            return Optional.empty();
        }
        return ServiceLoader.load(serviceType, classLoader).findFirst();
    }
}
