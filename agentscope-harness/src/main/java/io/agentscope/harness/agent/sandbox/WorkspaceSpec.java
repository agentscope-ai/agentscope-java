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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Describes the desired initial state of a sandbox workspace: root path, materialized
 * file/directory entries, and per-exec environment variables.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code root} — workspace path inside the sandbox (default: {@code /workspace})
 *   <li>{@code entries} — files, directories, and optional {@code bind_mount} entries to apply at
 *       startup (bind mounts are enforced by Docker/Kubernetes backends, not materialized as
 *       copied files)
 *   <li>{@code environment} — environment variables to inject into every exec command
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * WorkspaceSpec spec = new WorkspaceSpec();
 * spec.setRoot("/workspace");
 * spec.getEntries().put("README.md", new io.agentscope.harness.agent.sandbox.layout.FileEntry("# My Project"));
 * spec.getEnvironment().put("DEBUG", "true");
 * }</pre>
 */
public class WorkspaceSpec {

    private String root = "/workspace";
    private Map<String, WorkspaceEntry> entries = new LinkedHashMap<>();
    private Map<String, String> environment = new LinkedHashMap<>();

    public WorkspaceSpec() {}

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public Map<String, WorkspaceEntry> getEntries() {
        return entries;
    }

    public void setEntries(Map<String, WorkspaceEntry> entries) {
        this.entries = entries != null ? entries : new LinkedHashMap<>();
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public void setEnvironment(Map<String, String> environment) {
        this.environment = environment != null ? environment : new LinkedHashMap<>();
    }

    /**
     * Creates a deep copy. {@link WorkspaceEntry} values in the entries map are shared
     * (shallow copy); they are treated as immutable once a sandbox has started.
     */
    public WorkspaceSpec copy() {
        WorkspaceSpec copy = new WorkspaceSpec();
        copy.root = this.root;
        copy.entries = new LinkedHashMap<>(this.entries);
        copy.environment = new LinkedHashMap<>(this.environment);
        return copy;
    }
}
