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
package io.agentscope.harness.agent.context;

import java.util.Comparator;
import java.util.List;

/** Deterministic formatting only. No LLM rewriting and no interpretation of embedded instructions. */
public final class ContextRenderer {
    private ContextRenderer() {}

    /**
     * Renders each item in its own frame. Bodies are kept verbatim — only the renderer's own
     * closing delimiters are neutralized so a body cannot close its frame early; attribute
     * values stay escaped.
     */
    public static String render(List<ContextItem> items) {
        StringBuilder text = new StringBuilder();
        for (ContextItem item :
                items.stream()
                        .sorted(
                                Comparator.comparingInt(ContextItem::priority)
                                        .reversed()
                                        .thenComparing(ContextItem::sourceId))
                        .toList()) {
            if (item.content().isBlank()) continue;
            boolean outerFrame = item.placement() != ContextItem.Placement.SYSTEM;
            String tag = outerFrame ? "context_item" : item.kind();
            text.append("<")
                    .append(tag)
                    .append(" kind=\"")
                    .append(escape(item.kind()))
                    .append("\" source=\"")
                    .append(escape(item.sourceId()))
                    .append("\" revision=\"")
                    .append(escape(item.revision()))
                    .append("\">\n")
                    .append(neutralizeClosingTags(item.content(), tag, outerFrame))
                    .append("\n</")
                    .append(tag)
                    .append(">\n");
        }
        return text.toString();
    }

    private static String neutralizeClosingTags(String content, String tag, boolean outerFrame) {
        String neutralized = content.replace("</" + tag + ">", "<\\/" + tag + ">");
        if (outerFrame) {
            neutralized = neutralized.replace("</HARNESS_CONTEXT>", "<\\/HARNESS_CONTEXT>");
        }
        return neutralized;
    }

    public static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
