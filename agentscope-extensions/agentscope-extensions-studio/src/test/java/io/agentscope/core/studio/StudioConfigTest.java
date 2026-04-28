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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StudioConfig Tests")
class StudioConfigTest {

    @Test
    @DisplayName("Builder with minimal parameters should create valid config")
    void testBuilderMinimal() {
        StudioConfig config =
                StudioConfig.builder()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .build();

        assertNotNull(config);
        assertEquals("http://localhost:3000", config.getStudioUrl());
        assertEquals("TestProject", config.getProject());
        assertEquals("test_run", config.getRunName());
        assertNotNull(config.getRunId(), "Run ID should be auto-generated");
        assertTrue(config.getRunId().length() > 0);
    }

    @Test
    @DisplayName("Builder with all parameters should create valid config")
    void testBuilderComplete() {
        StudioConfig config =
                StudioConfig.builder()
                        .studioUrl("http://localhost:4000")
                        .project("MyProject")
                        .runName("my_run")
                        .runId("custom-run-id-123")
                        .maxRetries(5)
                        .reconnectAttempts(3)
                        .reconnectDelay(Duration.ofSeconds(2))
                        .reconnectMaxDelay(Duration.ofSeconds(30))
                        .build();

        assertNotNull(config);
        assertEquals("http://localhost:4000", config.getStudioUrl());
        assertEquals("MyProject", config.getProject());
        assertEquals("my_run", config.getRunName());
        assertEquals("custom-run-id-123", config.getRunId());
        assertEquals(5, config.getMaxRetries());
        assertEquals(3, config.getReconnectAttempts());
        assertEquals(Duration.ofSeconds(2), config.getReconnectDelay());
        assertEquals(Duration.ofSeconds(30), config.getReconnectMaxDelay());
    }

    @Test
    @DisplayName("Default values should be applied correctly")
    void testDefaultValues() {
        StudioConfig config =
                StudioConfig.builder()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .build();

        assertEquals(3, config.getMaxRetries());
        assertEquals(3, config.getReconnectAttempts());
        assertEquals(Duration.ofSeconds(1), config.getReconnectDelay());
        assertEquals(Duration.ofSeconds(5), config.getReconnectMaxDelay());
    }

    @Test
    @DisplayName("Custom run ID should override auto-generated ID")
    void testCustomRunId() {
        StudioConfig config =
                StudioConfig.builder()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .runId("my-custom-id")
                        .build();

        assertEquals("my-custom-id", config.getRunId());
    }

    @Test
    @DisplayName("Builder should handle edge cases")
    void testBuilderEdgeCases() {
        // Test with zero retries
        StudioConfig config1 =
                StudioConfig.builder()
                        .studioUrl("http://localhost:3000")
                        .project("Project")
                        .runName("run")
                        .maxRetries(0)
                        .reconnectAttempts(0)
                        .build();

        assertEquals(0, config1.getMaxRetries());
        assertEquals(0, config1.getReconnectAttempts());

        // Test with very long durations
        StudioConfig config2 =
                StudioConfig.builder()
                        .studioUrl("http://localhost:3000")
                        .project("Project")
                        .runName("run")
                        .reconnectDelay(Duration.ofHours(1))
                        .reconnectMaxDelay(Duration.ofHours(24))
                        .build();

        assertEquals(Duration.ofHours(1), config2.getReconnectDelay());
        assertEquals(Duration.ofHours(24), config2.getReconnectMaxDelay());
    }

    @Test
    @DisplayName("Builder with addOtlpHeader should create config with single header")
    void testBuilderWithAddOtlpHeader() {
        StudioConfig config =
                StudioConfig.builder()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .addOtlpHeader("Authorization", "Basic dGVzdDp0ZXN0")
                        .build();

        assertNotNull(config.getOtlpHeaders());
        assertEquals(1, config.getOtlpHeaders().size());
        assertEquals("Basic dGVzdDp0ZXN0", config.getOtlpHeaders().get("Authorization"));
    }

    @Test
    @DisplayName("Builder with multiple addOtlpHeader calls should accumulate headers")
    void testBuilderWithMultipleAddOtlpHeaders() {
        StudioConfig config =
                StudioConfig.builder()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .addOtlpHeader("Authorization", "Basic dGVzdDp0ZXN0")
                        .addOtlpHeader("X-Custom-Header", "custom-value")
                        .addOtlpHeader("X-Trace-Id", "trace-123")
                        .build();

        assertNotNull(config.getOtlpHeaders());
        assertEquals(3, config.getOtlpHeaders().size());
        assertEquals("Basic dGVzdDp0ZXN0", config.getOtlpHeaders().get("Authorization"));
        assertEquals("custom-value", config.getOtlpHeaders().get("X-Custom-Header"));
        assertEquals("trace-123", config.getOtlpHeaders().get("X-Trace-Id"));
    }

    @Test
    @DisplayName("Builder with otlpHeaders map should create config with all headers")
    void testBuilderWithOtlpHeadersMap() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token123");
        headers.put("X-Api-Key", "api-key-value");

        StudioConfig config =
                StudioConfig.builder()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .otlpHeaders(headers)
                        .build();

        assertNotNull(config.getOtlpHeaders());
        assertEquals(2, config.getOtlpHeaders().size());
        assertEquals("Bearer token123", config.getOtlpHeaders().get("Authorization"));
        assertEquals("api-key-value", config.getOtlpHeaders().get("X-Api-Key"));
    }

    @Test
    @DisplayName("Default config should have empty OTLP headers")
    void testDefaultOtlpHeadersEmpty() {
        StudioConfig config =
                StudioConfig.builder()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .build();

        assertNotNull(config.getOtlpHeaders());
        assertTrue(config.getOtlpHeaders().isEmpty());
    }

    @Test
    @DisplayName("OTLP headers should be immutable after build")
    void testOtlpHeadersImmutability() {
        StudioConfig config =
                StudioConfig.builder()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .addOtlpHeader("Authorization", "Basic dGVzdDp0ZXN0")
                        .build();

        Map<String, String> headers = config.getOtlpHeaders();

        try {
            headers.put("X-New-Header", "should-fail");
            // If we reach here, the map is mutable (which is wrong)
            assertTrue(false, "Headers map should be immutable");
        } catch (UnsupportedOperationException e) {
            // Expected behavior - map is immutable
            assertTrue(true);
        }
    }

    @Test
    @DisplayName("Combining otlpHeaders and addOtlpHeader should work correctly")
    void testCombiningHeaderMethods() {
        Map<String, String> initialHeaders = new HashMap<>();
        initialHeaders.put("X-Initial", "initial-value");

        StudioConfig config =
                StudioConfig.builder()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .otlpHeaders(initialHeaders)
                        .addOtlpHeader("X-Additional", "additional-value")
                        .build();

        assertNotNull(config.getOtlpHeaders());
        assertEquals(2, config.getOtlpHeaders().size());
        assertEquals("initial-value", config.getOtlpHeaders().get("X-Initial"));
        assertEquals("additional-value", config.getOtlpHeaders().get("X-Additional"));
    }

    @Test
    @DisplayName("Langfuse-style authentication should work correctly")
    void testLangfuseStyleAuthentication() {
        String publicKey = "pk-lf-test";
        String secretKey = "sk-lf-test";
        String base64Credentials =
                java.util.Base64.getEncoder()
                        .encodeToString((publicKey + ":" + secretKey).getBytes());

        StudioConfig config =
                StudioConfig.builder()
                        .studioUrl("https://cloud.langfuse.com")
                        .tracingUrl("https://cloud.langfuse.com/api/public/otel/v1/traces")
                        .project("LangfuseProject")
                        .runName("langfuse_run")
                        .addOtlpHeader("Authorization", "Basic " + base64Credentials)
                        .build();

        assertNotNull(config.getOtlpHeaders());
        assertEquals(1, config.getOtlpHeaders().size());
        assertTrue(config.getOtlpHeaders().get("Authorization").startsWith("Basic "));
        assertEquals(
                "https://cloud.langfuse.com/api/public/otel/v1/traces", config.getTracingUrl());
    }
}
