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

import java.util.ArrayList;
import java.util.List;

/**
 * Layout entry that projects selected files/directories from host workspace into sandbox
 * workspace when the {@link io.agentscope.harness.agent.sandbox.Sandbox} starts.
 *
 * <p>This entry is sandbox-specific and is ignored by {@link
 * io.agentscope.harness.agent.sandbox.WorkspaceSpecApplier}'s regular file materialization
 * logic. Instead, projection is applied inside {@link
 * io.agentscope.harness.agent.sandbox.Sandbox#start()} via archive hydration.
 */
public class WorkspaceProjectionEntry extends WorkspaceEntry {

    private String sourceRoot;
    private List<String> includeRoots = new ArrayList<>();

    /**
     * Absolute host-side workspace root used as source for projection.
     */
    public String getSourceRoot() {
        return sourceRoot;
    }

    public void setSourceRoot(String sourceRoot) {
        this.sourceRoot = sourceRoot;
    }

    /**
     * Relative include roots under {@link #getSourceRoot()}.
     */
    public List<String> getIncludeRoots() {
        return includeRoots;
    }

    public void setIncludeRoots(List<String> includeRoots) {
        this.includeRoots =
                includeRoots != null ? new ArrayList<>(includeRoots) : new ArrayList<>();
    }
}
