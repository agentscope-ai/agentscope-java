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

/**
 * Layout entry that recursively copies a directory from the host filesystem into the sandbox
 * workspace.
 *
 * <p>The {@code sourcePath} is an absolute path to a directory on the host machine. All files
 * within that directory are copied recursively to the destination path in the workspace.
 */
public class LocalDirEntry extends WorkspaceEntry {

    private String sourcePath;

    /** Creates an empty local directory entry. */
    public LocalDirEntry() {}

    /**
     * Creates a local directory entry with the given host source path.
     *
     * @param sourcePath absolute path to a directory on the host filesystem
     */
    public LocalDirEntry(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    /**
     * Returns the host-side source directory path.
     *
     * @return absolute host directory path as a string
     */
    public String getSourcePath() {
        return sourcePath;
    }

    /**
     * Sets the host-side source directory path.
     *
     * @param sourcePath absolute host directory path as a string
     */
    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }
}
