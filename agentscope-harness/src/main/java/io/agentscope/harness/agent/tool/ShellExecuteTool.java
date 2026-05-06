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
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;

/**
 * Shell execution tool backed by a {@link AbstractSandboxFilesystem}.
 */
public class ShellExecuteTool {

    private final AbstractSandboxFilesystem sandbox;

    public ShellExecuteTool(AbstractSandboxFilesystem sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * @param runtimeContext per-call agent runtime injected by the framework (not an LLM argument);
     *     may be {@code null} when no merged context is available
     */
    @Tool(
            description =
                    "Execute a shell command. Use for git, npm, build, test, and other terminal"
                            + " operations. Returns combined output and exit code.")
    public String execute(
            RuntimeContext runtimeContext,
            @ToolParam(name = "command", description = "Shell command to execute") String command,
            @ToolParam(
                            name = "working_directory",
                            description =
                                    "Working directory (relative to workspace root, optional)")
                    String workingDirectory,
            @ToolParam(name = "timeout", description = "Timeout in seconds (default: 30)")
                    int timeout) {
        String effectiveCommand = command;
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            effectiveCommand = "cd " + workingDirectory + " && " + command;
        }

        ExecuteResponse result = sandbox.execute(effectiveCommand, timeout > 0 ? timeout : 30);

        StringBuilder sb = new StringBuilder();
        sb.append("Exit code: ").append(result.exitCode()).append("\n");
        if (result.output() != null && !result.output().isBlank()) {
            sb.append("\n").append(result.output());
        }
        if (result.truncated()) {
            sb.append("\n(output was truncated)");
        }
        return sb.toString();
    }
}
