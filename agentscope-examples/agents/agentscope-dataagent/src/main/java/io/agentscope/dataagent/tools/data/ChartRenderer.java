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
 * SPI for rendering an in-line chart from a small tabular result. v1 ships with a stub
 * implementation; concrete renderers (server-side PNG via XChart, ECharts-spec JSON for the SPA,
 * Mermaid for inline markdown) belong in {@code agentscope-extensions/}.
 */
public interface ChartRenderer {

    /**
     * Renders a chart described by a Vega-Lite-like spec. Returns a short status string that the
     * agent can include in its response — typically either a markdown image link, an inline base64
     * image, or an {@code "ok: rendered to <url>"} pointer.
     *
     * @param chartType one of "line", "bar", "area", "scatter"
     * @param vegaLiteSpec a Vega-Lite-compatible JSON spec, including the inline data
     */
    String render(String chartType, String vegaLiteSpec);
}
