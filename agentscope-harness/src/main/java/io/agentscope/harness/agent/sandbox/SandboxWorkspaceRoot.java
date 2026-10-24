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

import io.agentscope.core.agent.RuntimeContext;

/** Resolves workspace paths from the current call, never a shared filesystem binding. */
public final class SandboxWorkspaceRoot {
    private SandboxWorkspaceRoot() {}

    /**
     * Returns the active sandbox's root, or the configured fallback before acquisition.
     * Restored sandbox state takes precedence over new-call configuration.
     *
     * @param context current call context, or null before acquisition
     * @param fallback root to use when no active workspace specification is available
     * @return the active workspace root or fallback
     */
    public static String resolve(RuntimeContext context, String fallback) {
        SandboxAcquireResult acquired =
                context != null ? context.get(SandboxAcquireResult.class) : null;
        if (acquired != null) {
            if (acquired.isReleased()) {
                throw new SandboxException.SandboxConfigurationException(
                        "Sandbox acquisition has been released for this call");
            }
            SandboxState state = acquired.getSandbox().getState();
            WorkspaceSpec spec = state != null ? state.getWorkspaceSpec() : null;
            if (spec != null && spec.getRoot() != null && !spec.getRoot().isBlank()) {
                return spec.getRoot();
            }
        }
        return fallback;
    }
}
