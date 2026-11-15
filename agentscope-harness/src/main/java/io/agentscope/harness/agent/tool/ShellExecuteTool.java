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
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolErrorMetadata;
import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;

/**
 * Shell execution tool backed by a {@link AbstractSandboxFilesystem}.
 */
public class ShellExecuteTool {

    /**
     * Registered tool name (derived from the {@link #execute} method name).
     */
    public static final String NAME = "execute";

    private final AbstractSandboxFilesystem sandbox;

    public ShellExecuteTool(AbstractSandboxFilesystem sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * @param runtimeContext per-call agent runtime injected by the framework (not an LLM argument);
     *                       may be {@code null} when no merged context is available
     */
    @Tool(
            description =
                    "Execute a command using the configured execution environment's shell. Use for"
                        + " git, builds, tests, and other terminal operations. Returns combined"
                        + " output and exit code. Each call starts a new shell; working directory"
                        + " and environment changes are scoped to that call. Prefer dedicated file"
                        + " tools when they support the operation; use shell for other operations.")
    public ToolResultBlock execute(
            RuntimeContext runtimeContext,
            @ToolParam(name = "command", description = "Command in the environment's shell syntax")
                    String command,
            @ToolParam(
                            name = "working_directory",
                            description =
                                    "Working directory for this command only, relative to the"
                                            + " shell's starting directory (optional)",
                            required = false)
                    String workingDirectory,
            @ToolParam(
                            name = "timeout",
                            description =
                                    "Positive timeout in seconds (default: 30). Unsupported values"
                                            + " are rejected before execution.",
                            required = false)
                    Integer timeout) {
        String effectiveCommand = command;
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            String wd = workingDirectory.strip();
            if (wd.startsWith("/") || wd.startsWith("~") || wd.contains("..")) {
                return ToolResultBlock.error(
                                "working_directory must be a relative path within the workspace"
                                        + " (absolute paths, '~', and '..' are not allowed).")
                        .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
            }
            effectiveCommand =
                    commandWithWorkingDirectory(
                            wd,
                            command,
                            usesWindowsShell(
                                    sandbox,
                                    System.getProperty("os.name").toLowerCase().contains("win")));
        }

        if (timeout != null && timeout <= 0) {
            return ToolResultBlock.error("timeout must be a positive number of seconds.")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }
        int timeoutSeconds = timeout != null ? timeout : 30;
        ExecuteResponse result = sandbox.execute(runtimeContext, effectiveCommand, timeoutSeconds);

        StringBuilder sb = new StringBuilder();
        sb.append("Exit code: ").append(result.exitCode()).append("\n");
        if (result.timedOut()) {
            sb.append("Timed out: true\n");
        }
        if (result.output() != null && !result.output().isBlank()) {
            sb.append("\n").append(result.output());
        }
        if (result.truncated()) {
            sb.append("\n(output was truncated)");
        }
        ToolExecutionDetails.Outcome outcome =
                result.exitCode() == null
                        ? ToolExecutionDetails.Outcome.UNKNOWN
                        : result.isSuccess()
                                ? ToolExecutionDetails.Outcome.SUCCEEDED
                                : ToolExecutionDetails.Outcome.FAILED;
        ToolResultBlock block =
                ToolResultBlock.text(sb.toString())
                        .withExecutionDetails(
                                new ToolExecutionDetails(
                                        "shell", outcome, result.exitCode(), result.truncated()))
                        .withState(
                                result.isSuccess()
                                        ? ToolResultState.SUCCESS
                                        : ToolResultState.ERROR);
        if (result.isSuccess()) {
            return block;
        }
        ToolErrorKind kind =
                result.timedOut()
                        ? ToolErrorKind.TIMEOUT
                        : result.exitCode() == null
                                ? ToolErrorKind.INTERNAL_ERROR
                                : ToolErrorKind.COMMAND_FAILED;
        return block.withMetadata(
                ToolErrorMetadata.of(
                        kind, executableScope(command), kind == ToolErrorKind.TIMEOUT));
    }

    /** Scope for a shell failure: the executable basename, never its arguments. */
    static String executableScope(String command) {
        if (command == null) {
            return null;
        }
        String trimmed = command.strip();
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
            end++;
        }
        String token = trimmed.substring(0, end);
        if (token.length() >= 2
                && ((token.startsWith("\"") && token.endsWith("\""))
                        || (token.startsWith("'") && token.endsWith("'")))) {
            token = token.substring(1, token.length() - 1);
        }
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        String base = slash >= 0 ? token.substring(slash + 1) : token;
        return base.isBlank() ? null : base;
    }

    static boolean usesWindowsShell(AbstractFilesystem filesystem, boolean windowsHost) {
        if (filesystem instanceof OverlayFilesystem overlay) {
            return usesWindowsShell(overlay.getUpper(), windowsHost);
        }
        if (filesystem instanceof RoutedSandboxFilesystem routed) {
            return usesWindowsShell(routed.primary(), windowsHost);
        }
        return windowsHost && filesystem instanceof LocalFilesystemWithShell;
    }

    static String commandWithWorkingDirectory(
            String workingDirectory, String command, boolean windows) {
        if (windows) {
            return "cd /d \"" + workingDirectory.replace("\"", "\"\"") + "\" && " + command;
        }
        return "cd '" + workingDirectory.replace("'", "'\\''") + "' && " + command;
    }
}
