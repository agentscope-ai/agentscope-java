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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
            sb.append(
                    "\n"
                            + "(Output was truncated. Without a saved-output path above, omitted"
                            + " content is unavailable.)");
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
        if (result.outputDigest() != null && result.outputDigest().matches("[0-9a-fA-F]{64}")) {
            block =
                    block.withMetadata(
                            Map.of(
                                    "agentscope_output_sha256",
                                    result.outputDigest().toLowerCase(java.util.Locale.ROOT)));
        }
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

    /**
     * Best-effort failure classification, not a shell parser or a security boundary. Recognizes
     * simple cd/assignment/launcher prefixes; ambiguous shell syntax has no scope.
     */
    static String executableScope(String command) {
        List<ShellToken> tokens = shellTokens(command);
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        int index = 0;
        while (index < tokens.size()) {
            while (index < tokens.size() && tokens.get(index).assignment()) {
                index++;
            }
            if (index == tokens.size() || tokens.get(index).separator()) {
                return null;
            }
            if (!tokens.get(index).text().equals("cd")) {
                break;
            }
            int separator = index + 1;
            while (separator < tokens.size() && !tokens.get(separator).separator()) {
                separator++;
            }
            if (separator == tokens.size()) {
                return "cd";
            }
            int directory = index + 1;
            if (directory < separator
                    && List.of("/d", "--", "-P", "-L").contains(tokens.get(directory).text())) {
                directory++;
            }
            if (directory + 1 != separator
                    || tokens.get(directory).text().isBlank()
                    || (tokens.get(directory).text().startsWith("-")
                            && !tokens.get(directory).text().equals("-"))) {
                return null;
            }
            index = separator + 1;
        }
        // Other command chains/pipelines cannot be attributed to a single executable.
        for (int i = index; i < tokens.size(); i++) {
            if (tokens.get(i).separator()) {
                return null;
            }
        }
        while (index < tokens.size()) {
            String executable = executableBasename(tokens.get(index).text());
            if (executable == null) {
                return null;
            }
            if (!List.of("env", "command", "exec").contains(executable)) {
                return executable;
            }
            index = skipLauncher(tokens, index + 1, executable);
            if (index < 0) {
                return null;
            }
        }
        return null;
    }

    private static int skipLauncher(List<ShellToken> tokens, int index, String launcher) {
        boolean options = true;
        while (index < tokens.size()) {
            String text = tokens.get(index).text();
            if (launcher.equals("env") && isAssignment(text)) {
                // env options precede assignments; after an assignment, a leading '-' is
                // the executable, not an option whose argument may be exposed as scope.
                options = false;
                index++;
            } else if (options && text.equals("--")) {
                options = false;
                index++;
            } else if (options
                    && ((launcher.equals("env")
                                    && List.of("-i", "--ignore-environment").contains(text))
                            || (launcher.equals("command") && text.equals("-p"))
                            || (launcher.equals("exec") && List.of("-c", "-l").contains(text)))) {
                index++;
            } else if (options
                    && ((launcher.equals("env") && List.of("-u", "--unset").contains(text))
                            || (launcher.equals("exec") && text.equals("-a")))) {
                if (index + 1 >= tokens.size() || tokens.get(index + 1).text().isEmpty()) {
                    return -1;
                }
                index += 2;
            } else if (options
                    && launcher.equals("env")
                    && ((text.startsWith("-u") && text.length() > 2)
                            || (text.startsWith("--unset=") && text.length() > 8))) {
                index++;
            } else {
                return text.startsWith("-") ? -1 : index;
            }
        }
        return index;
    }

    private static String executableBasename(String token) {
        if (token.isBlank()
                || token.startsWith("-")
                || token.chars().anyMatch(c -> "=$`*?[]{}%!".indexOf(c) >= 0)
                || List.of(
                                "if",
                                "then",
                                "else",
                                "elif",
                                "fi",
                                "for",
                                "while",
                                "until",
                                "do",
                                "done",
                                "case",
                                "esac",
                                "function",
                                "select",
                                "time",
                                "!")
                        .contains(token)) {
            return null;
        }
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        String base = token.substring(slash + 1);
        return base.isBlank() ? null : base;
    }

    private static boolean isAssignment(String token) {
        int equals = token.indexOf('=');
        return equals > 0 && token.substring(0, equals).matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private record ShellToken(String text, boolean separator, boolean assignment) {}

    private static List<ShellToken> shellTokens(String command) {
        if (command == null) {
            return null;
        }
        List<ShellToken> tokens = new ArrayList<>();
        int index = 0;
        while (index < command.length()) {
            char c = command.charAt(index);
            if (c == '\n' || c == '\r') {
                return null;
            }
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (c == ';' || (c == '&' && command.startsWith("&&", index))) {
                String separator = c == ';' ? ";" : "&&";
                tokens.add(new ShellToken(separator, true, false));
                index += separator.length();
                continue;
            }
            int start = index;
            char quote = 0;
            StringBuilder text = new StringBuilder();
            while (index < command.length()) {
                c = command.charAt(index);
                if (quote == 0 && (Character.isWhitespace(c) || c == ';' || c == '&')) {
                    break;
                }
                if ((c == '\'' || c == '"') && (quote == 0 || quote == c)) {
                    quote = quote == 0 ? c : 0;
                    index++;
                    continue;
                }
                if (c == '\\' && quote != '\'' && index + 1 < command.length()) {
                    char next = command.charAt(index + 1);
                    if ((quote == 0
                                    && (next == '\''
                                            || next == '"'
                                            || Character.isWhitespace(next)))
                            || (quote == '"' && next == '"')) {
                        text.append(next);
                        index += 2;
                        continue;
                    }
                }
                if ((quote == 0 && "|&<>(){}#".indexOf(c) >= 0)
                        || (quote != '\'' && (c == '$' || c == '`'))) {
                    return null;
                }
                text.append(c);
                index++;
            }
            if (quote != 0 || index == start) {
                return null;
            }
            tokens.add(
                    new ShellToken(
                            text.toString(), false, isAssignment(command.substring(start, index))));
        }
        return tokens;
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
