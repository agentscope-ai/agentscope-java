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
            String tag =
                    item.placement() == ContextItem.Placement.SYSTEM ? item.kind() : "context_item";
            text.append("<")
                    .append(tag)
                    .append(" kind=\"")
                    .append(escape(item.kind()))
                    .append("\" source=\"")
                    .append(escape(item.sourceId()))
                    .append("\" revision=\"")
                    .append(escape(item.revision()))
                    .append("\">\n")
                    .append(escape(item.content()))
                    .append("\n</")
                    .append(tag)
                    .append(">\n");
        }
        return text.toString();
    }

    public static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
