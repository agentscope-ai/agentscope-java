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
package io.opentelemetry.api.trace;

/**
 * Minimal OpenTelemetry stubs for tests.
 *
 * <p>AgentScope core does not depend on OpenTelemetry directly. JsonlTraceExporter uses reflection
 * to attach trace_id/span_id when OpenTelemetry is present at runtime. These stubs let us cover
 * that branch in unit tests without adding a core dependency.
 */
public final class Span {

    private static volatile Span current = new Span(new SpanContext(false, "", ""));

    private final SpanContext context;

    public Span(SpanContext context) {
        this.context = context;
    }

    public static Span current() {
        return current;
    }

    public static void setCurrent(Span span) {
        current = span;
    }

    public SpanContext getSpanContext() {
        return context;
    }
}
