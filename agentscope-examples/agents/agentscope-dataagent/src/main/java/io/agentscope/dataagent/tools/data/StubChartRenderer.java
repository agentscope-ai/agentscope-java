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
package io.agentscope.dataagent.tools.data;

/**
 * No-op {@link ChartRenderer} returned by default. Echoes the spec back so the SPA can pick it up
 * and render it client-side (the v1 frontend already understands Vega-Lite specs). Operators
 * wanting server-side rendering should register their own bean implementing this SPI.
 */
public final class StubChartRenderer implements ChartRenderer {

    @Override
    public String render(String chartType, String vegaLiteSpec) {
        if (vegaLiteSpec == null || vegaLiteSpec.isBlank()) {
            return "error: vega-lite spec is empty";
        }
        return "ok: chart spec accepted (type="
                + chartType
                + "); the UI will render it client-side";
    }
}
