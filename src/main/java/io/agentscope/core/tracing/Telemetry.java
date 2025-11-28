/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.core.tracing;

import io.agentscope.core.Version;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

public final class Telemetry {

    private static final String INSTRUMENTATION_NAME = "agentscope";

    private static final Tracer NOOP_TRACER =
            TracerProvider.noop().get(INSTRUMENTATION_NAME, Version.VERSION);

    private static final Tracer DEFAULT_TRACER =
            GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME, Version.VERSION);

    private static boolean enabled = false;

    private static Tracer tracer = null;

    public static void setupTracing(String endpoint) {
        setupTracing(true, endpoint);
    }

    public static void setupTracing(boolean enabled, String endpoint) {
        Telemetry.enabled = enabled;

        TracerProvider tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(
                                BatchSpanProcessor.builder(
                                                OtlpHttpSpanExporter.builder()
                                                        .setEndpoint(endpoint)
                                                        .build())
                                        .build())
                        .setSampler(Sampler.alwaysOn())
                        .build();

        tracer = tracerProvider.get(INSTRUMENTATION_NAME, Version.VERSION);
    }

    public static Tracer getTracer() {
        if (!checkTracingEnabled()) {
            return NOOP_TRACER;
        }

        return tracer == null ? DEFAULT_TRACER : tracer;
    }

    static boolean checkTracingEnabled() {
        return enabled;
    }

    private Telemetry() {}
}
