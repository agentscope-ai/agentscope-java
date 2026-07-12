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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

class McpJsonDefaultsTest {

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
}
