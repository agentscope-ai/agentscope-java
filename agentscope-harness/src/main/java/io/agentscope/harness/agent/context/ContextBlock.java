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

import java.util.Objects;

/** Dynamic data, never system instructions. Instances are immutable; fluent methods return copies. */
public final class ContextBlock {
    private final ContextItem item;

    private ContextBlock(ContextItem item) {
        this.item = item;
    }

    public static ContextBlock runtime(String id, String content) {
        return create("runtime", id, content, ContextItem.Placement.RUNTIME);
    }

    public static ContextBlock reference(String id, String content) {
        return create("reference", id, content, ContextItem.Placement.REFERENCE);
    }

    private static ContextBlock create(
            String kind, String id, String content, ContextItem.Placement placement) {
        Objects.requireNonNull(content);
        if (Objects.requireNonNull(id).isBlank())
            throw new IllegalArgumentException("Block ID must not be blank");
        ContextItem initial = new ContextItem(kind, id, content);
        return new ContextBlock(
                new ContextItem(kind, id, initial.revision(), placement, false, 0, content));
    }

    /** A business revision is optional; the default revision is a content hash, not acceptance evidence. */
    public ContextBlock withRevision(String revision) {
        if (Objects.requireNonNull(revision).isBlank())
            throw new IllegalArgumentException("Revision must not be blank");
        return new ContextBlock(
                new ContextItem(
                        item.kind(),
                        item.sourceId(),
                        revision,
                        item.placement(),
                        item.required(),
                        item.priority(),
                        item.content()));
    }

    /** Prevents budget eviction; cannot be used with an OMIT source. */
    public ContextBlock required() {
        return new ContextBlock(
                new ContextItem(
                        item.kind(),
                        item.sourceId(),
                        item.revision(),
                        item.placement(),
                        true,
                        item.priority(),
                        item.content()));
    }

    /** Lower priorities are considered for eviction first within the same material category. */
    public ContextBlock withPriority(int priority) {
        return new ContextBlock(
                new ContextItem(
                        item.kind(),
                        item.sourceId(),
                        item.revision(),
                        item.placement(),
                        item.required(),
                        priority,
                        item.content()));
    }

    ContextItem material() {
        return item;
    }
}
