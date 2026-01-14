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
package io.agentscope.core.tracing.telemetry;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TelemetryTracer Tests")
class TelemetryTracerTest {

    @Test
    @DisplayName("Builder with endpoint should create valid tracer")
    void testBuilderWithEndpoint() {
        TelemetryTracer tracer =
                TelemetryTracer.builder().endpoint("http://localhost:4318/v1/traces").build();

        assertNotNull(tracer);
    }

    @Test
    @DisplayName("Builder with disabled should create noop tracer")
    void testBuilderDisabled() {
        TelemetryTracer tracer = TelemetryTracer.builder().enabled(false).build();

        assertNotNull(tracer);
    }

    @Test
    @DisplayName("Builder with addHeader should create valid tracer")
    void testBuilderWithAddHeader() {
        TelemetryTracer tracer =
                TelemetryTracer.builder()
                        .endpoint("http://localhost:4318/v1/traces")
                        .addHeader("Authorization", "Basic dGVzdDp0ZXN0")
                        .build();

        assertNotNull(tracer);
    }

    @Test
    @DisplayName("Builder with multiple headers should create valid tracer")
    void testBuilderWithMultipleHeaders() {
        TelemetryTracer tracer =
                TelemetryTracer.builder()
                        .endpoint("http://localhost:4318/v1/traces")
                        .addHeader("Authorization", "Basic dGVzdDp0ZXN0")
                        .addHeader("X-Custom-Header", "custom-value")
                        .build();

        assertNotNull(tracer);
    }

    @Test
    @DisplayName("Builder with headers map should create valid tracer")
    void testBuilderWithHeadersMap() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Basic dGVzdDp0ZXN0");
        headers.put("X-Custom-Header", "custom-value");

        TelemetryTracer tracer =
                TelemetryTracer.builder()
                        .endpoint("http://localhost:4318/v1/traces")
                        .headers(headers)
                        .build();

        assertNotNull(tracer);
    }

    @Test
    @DisplayName("Builder with empty headers should create valid tracer")
    void testBuilderWithEmptyHeaders() {
        TelemetryTracer tracer =
                TelemetryTracer.builder()
                        .endpoint("http://localhost:4318/v1/traces")
                        .headers(new HashMap<>())
                        .build();

        assertNotNull(tracer);
    }

    @Test
    @DisplayName("Builder combining addHeader and headers should work correctly")
    void testBuilderCombiningHeaderMethods() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Initial-Header", "initial-value");

        TelemetryTracer tracer =
                TelemetryTracer.builder()
                        .endpoint("http://localhost:4318/v1/traces")
                        .headers(headers)
                        .addHeader("X-Additional-Header", "additional-value")
                        .build();

        assertNotNull(tracer);
    }
}
