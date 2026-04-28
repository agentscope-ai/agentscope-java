/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.studio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StudioManager Tests")
class StudioManagerTest {

    @AfterEach
    void tearDown() {
        // Always clean up after each test
        StudioManager.shutdown();
    }

    @Test
    @DisplayName("init() should return a new builder")
    void testInit() {
        StudioManager.Builder builder = StudioManager.init();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("getClient() should return null before initialization")
    void testGetClientBeforeInit() {
        assertNull(StudioManager.getClient());
    }

    @Test
    @DisplayName("getWebSocketClient() should return null before initialization")
    void testGetWebSocketClientBeforeInit() {
        assertNull(StudioManager.getWebSocketClient());
    }

    @Test
    @DisplayName("getConfig() should return null before initialization")
    void testGetConfigBeforeInit() {
        assertNull(StudioManager.getConfig());
    }

    @Test
    @DisplayName("isInitialized() should return false before initialization")
    void testIsInitializedBeforeInit() {
        assertFalse(StudioManager.isInitialized());
    }

    @Test
    @DisplayName("shutdown() should be safe to call before initialization")
    void testShutdownBeforeInit() {
        // Should not throw
        StudioManager.shutdown();

        assertNull(StudioManager.getClient());
        assertNull(StudioManager.getWebSocketClient());
        assertNull(StudioManager.getConfig());
        assertFalse(StudioManager.isInitialized());
    }

    @Test
    @DisplayName("shutdown() should clear all state")
    void testShutdownClearsState() {
        // This test doesn't actually initialize (to avoid network calls)
        // Just tests that shutdown resets state
        StudioManager.shutdown();

        assertNull(StudioManager.getClient());
        assertNull(StudioManager.getWebSocketClient());
        assertNull(StudioManager.getConfig());
        assertFalse(StudioManager.isInitialized());
    }

    @Test
    @DisplayName("Builder should support method chaining")
    void testBuilderChaining() {
        StudioManager.Builder builder =
                StudioManager.init()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .maxRetries(5)
                        .reconnectAttempts(3);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Multiple init() calls should return independent builders")
    void testMultipleInitCalls() {
        StudioManager.Builder builder1 = StudioManager.init();
        StudioManager.Builder builder2 = StudioManager.init();

        assertNotNull(builder1);
        assertNotNull(builder2);
        // They should be different instances
        assertFalse(builder1 == builder2);
    }

    @Test
    @DisplayName("shutdown() should be idempotent")
    void testShutdownIdempotent() {
        // Multiple shutdown calls should be safe
        StudioManager.shutdown();
        StudioManager.shutdown();
        StudioManager.shutdown();

        assertNull(StudioManager.getClient());
        assertFalse(StudioManager.isInitialized());
    }

    @Test
    @DisplayName("Builder should support addOtlpHeader method")
    void testBuilderAddOtlpHeader() {
        StudioManager.Builder builder =
                StudioManager.init()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .addOtlpHeader("Authorization", "Basic dGVzdDp0ZXN0");

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Builder should support otlpHeaders method with map")
    void testBuilderOtlpHeadersMap() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token123");
        headers.put("X-Api-Key", "api-key-value");

        StudioManager.Builder builder =
                StudioManager.init()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .otlpHeaders(headers);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Builder should support combining header methods with other configuration")
    void testBuilderCombiningHeadersWithOtherConfig() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Initial", "initial");

        StudioManager.Builder builder =
                StudioManager.init()
                        .studioUrl("https://cloud.langfuse.com")
                        .tracingUrl("https://cloud.langfuse.com/api/public/otel/v1/traces")
                        .project("LangfuseProject")
                        .runName("langfuse_run")
                        .maxRetries(5)
                        .reconnectAttempts(3)
                        .otlpHeaders(headers)
                        .addOtlpHeader("Authorization", "Basic credentials");

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Builder should support Langfuse-style configuration")
    void testBuilderLangfuseConfiguration() {
        String publicKey = "pk-lf-test";
        String secretKey = "sk-lf-test";
        String base64Credentials =
                java.util.Base64.getEncoder()
                        .encodeToString((publicKey + ":" + secretKey).getBytes());

        StudioManager.Builder builder =
                StudioManager.init()
                        .studioUrl("https://cloud.langfuse.com")
                        .tracingUrl("https://cloud.langfuse.com/api/public/otel/v1/traces")
                        .project("LangfuseProject")
                        .runName("langfuse_run")
                        .addOtlpHeader("Authorization", "Basic " + base64Credentials);

        assertNotNull(builder);
    }
}
