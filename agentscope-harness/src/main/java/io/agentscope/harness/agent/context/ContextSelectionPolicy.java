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

/** Returns optional source IDs in eviction order. Cannot rewrite content or remove required items. */
@FunctionalInterface
public interface ContextSelectionPolicy {
    List<String> omissionOrder(List<ContextItem> materials);

    static ContextSelectionPolicy defaults() {
        return materials ->
                materials.stream()
                        .filter(
                                item ->
                                        !item.required()
                                                && item.placement() != ContextItem.Placement.SYSTEM)
                        .sorted(
                                Comparator.comparingInt(
                                                (ContextItem item) ->
                                                        switch (item.kind()) {
                                                            case "memory" -> 0;
                                                            case "knowledge" -> 1;
                                                            default -> 2;
                                                        })
                                        .thenComparingInt(ContextItem::priority)
                                        .thenComparing(ContextItem::sourceId))
                        .map(ContextItem::sourceId)
                        .toList();
    }
}
