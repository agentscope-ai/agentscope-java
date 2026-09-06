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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.permission.PermissionEscalation;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;

/**
 * Escalation-aware variant of {@link ShellExecuteTool}, registered in its place when {@code
 * permissionEscalation(true)} is configured: identical execution semantics (shared body in
 * {@link ShellExecuteTool#executeAndFormat}), plus the optional {@code sandbox_permissions} +
 * {@code justification} arguments a model may use to request a strictly-wider permission mode
 * for one call. The schema advertises only the closed target vocabulary; validity is checked at
 * execution time by {@link PermissionEscalation} (never baked into the schema), and an approved
 * request runs through the normal user-confirmation flow before anything executes.
 *
 * <p>The registered tool name ({@code execute}, derived from the method name) intentionally
 * matches {@link ShellExecuteTool#NAME} — the model sees one tool either way.
 */
public class EscalatingShellExecuteTool {

    private final AbstractSandboxFilesystem sandbox;

    public EscalatingShellExecuteTool(AbstractSandboxFilesystem sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * @param runtimeContext per-call agent runtime injected by the framework (not an LLM argument);
     *                       may be {@code null} when no merged context is available
     */
    @Tool(
            description =
                    "Execute a shell command. Use for git, npm, build, test, and other terminal"
                        + " operations. Returns combined output and exit code. If a dedicated tool"
                        + " exists (e.g., read_file, write_file), you MUST use it instead of shell"
                        + " commands. When the current permission mode blocks this command, you may"
                        + " request a strictly wider mode for this single call via"
                        + " sandbox_permissions (one of: read-only, workspace-write,"
                        + " danger-full-access) together with a justification sentence; the user"
                        + " approves or denies the request before anything executes.")
    public String execute(
            RuntimeContext runtimeContext,
            @ToolParam(name = "command", description = "Shell command to execute") String command,
            @ToolParam(
                            name = "working_directory",
                            description =
                                    "Working directory (relative to workspace root, optional)",
                            required = false)
                    String workingDirectory,
            @ToolParam(
                            name = "timeout",
                            description = "Timeout in seconds (default: 30)",
                            required = false)
                    Integer timeout,
            @ToolParam(
                            name = PermissionEscalation.ARG_PERMISSIONS,
                            description =
                                    "Optional: request a strictly wider permission mode for this"
                                            + " single call. One of: read-only, workspace-write,"
                                            + " danger-full-access. Must be paired with a"
                                            + " justification.",
                            required = false)
                    String sandboxPermissions,
            @ToolParam(
                            name = PermissionEscalation.ARG_JUSTIFICATION,
                            description =
                                    "Optional: a non-empty sentence explaining why this call"
                                            + " needs wider permissions. Only valid together with"
                                            + " sandbox_permissions.",
                            required = false)
                    String justification) {
        return ShellExecuteTool.executeAndFormat(
                sandbox, runtimeContext, command, workingDirectory, timeout);
    }
}
