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
package io.agentscope.harness.agent.sandbox.layout;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Layout entry that creates a directory, optionally with nested child entries.
 *
 * <p>Child entries are a map of filename to {@link WorkspaceEntry} and may themselves be
 * nested {@code DirEntry} instances, enabling recursive tree-like workspace structures.
 */
public class DirEntry extends WorkspaceEntry {

    private Map<String, WorkspaceEntry> children = new LinkedHashMap<>();

    /** Creates an empty directory entry. */
    public DirEntry() {}

    /**
     * Creates a directory entry with the given children.
     *
     * @param children map of child name to child entry
     */
    public DirEntry(Map<String, WorkspaceEntry> children) {
        this.children = new LinkedHashMap<>(children);
    }

    /**
     * Returns the child entries in this directory.
     *
     * @return mutable map of child name to child entry
     */
    public Map<String, WorkspaceEntry> getChildren() {
        return children;
    }

    /**
     * Sets the child entries for this directory.
     *
     * @param children map of child name to child entry
     */
    public void setChildren(Map<String, WorkspaceEntry> children) {
        this.children = children != null ? children : new LinkedHashMap<>();
    }

    /**
     * Adds a child entry to this directory.
     *
     * @param name child filename
     * @param entry child entry
     * @return this instance for chaining
     */
    public DirEntry child(String name, WorkspaceEntry entry) {
        this.children.put(name, entry);
        return this;
    }
}
