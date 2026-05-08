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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base class for entries in a {@link io.agentscope.harness.agent.sandbox.WorkspaceSpec}.
 *
 * <p>Each entry describes a single file or directory that should be present in the sandbox
 * workspace at startup. Entries can be materialized from inline content, local host files, or
 * remote sources.
 *
 * <p>When {@code ephemeral} is {@code true}, this entry is always re-applied on every sandbox
 * start, even when a snapshot exists. Non-ephemeral entries are persisted inside the snapshot
 * and restored with the workspace.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = FileEntry.class, name = "file"),
    @JsonSubTypes.Type(value = DirEntry.class, name = "dir"),
    @JsonSubTypes.Type(value = LocalFileEntry.class, name = "local_file"),
    @JsonSubTypes.Type(value = LocalDirEntry.class, name = "local_dir"),
    @JsonSubTypes.Type(value = GitRepoEntry.class, name = "git_repo"),
    @JsonSubTypes.Type(value = WorkspaceProjectionEntry.class, name = "workspace_projection"),
})
public abstract class WorkspaceEntry {

    private boolean ephemeral = false;

    /**
     * Returns whether this entry is ephemeral.
     *
     * <p>Ephemeral entries are always re-applied on session resume regardless of whether a
     * snapshot exists. They are suitable for dynamic configuration that should not be snapshotted.
     *
     * @return true if this entry is ephemeral
     */
    public boolean isEphemeral() {
        return ephemeral;
    }

    /**
     * Sets whether this entry is ephemeral.
     *
     * @param ephemeral true to mark as ephemeral
     */
    public void setEphemeral(boolean ephemeral) {
        this.ephemeral = ephemeral;
    }
}
