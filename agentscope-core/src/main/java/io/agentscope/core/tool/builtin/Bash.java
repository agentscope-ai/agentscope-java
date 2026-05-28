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
package io.agentscope.core.tool.builtin;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContext;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolDangerousPathConstants;
import io.agentscope.core.tool.builtin.bash.BashCommandParser;
import io.agentscope.core.tool.builtin.bash.BashConstants;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Executes shell commands with AST-driven safety checks.
 *
 * <p>{@link #checkPermissions(Map, PermissionContext)} runs a fixed sequence of inspections — in
 * order: injection risk, read-only allow, dangerous-pattern detection, sed constraints, dangerous
 * file paths, critical-removal targets, {@code accept_edits} filesystem allow, and a final
 * {@code PASSTHROUGH}. Safety-driven rejections carry a {@code decisionReason} containing
 * {@code "safety"} so the permission engine treats them as bypass-immune.
 *
 * <p>Execution is performed via {@code /bin/bash -c} (or {@code cmd /c} on Windows) inside a
 * bounded-elastic scheduler so the calling reactive chain is never blocked.
 */
public final class Bash extends ToolBase {

    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    public static final long MAX_TIMEOUT_MS = 600_000L;

    private static final String NAME = "Bash";

    private static final String DESCRIPTION =
            "Execute a shell command after AST-level safety analysis. Read-only commands run"
                    + " without prompting; mutations and patterns matching the dangerous-command"
                    + " list require explicit approval. Output is captured as combined stdout and"
                    + " stderr.";

    private final BashCommandParser parser;

    public Bash() {
        this(List.of(), List.of());
    }

    /**
     * @param additionalDangerousFiles extra sensitive filenames appended to the defaults
     * @param additionalDangerousDirectories extra sensitive directory names appended to the
     *     defaults
     */
    public Bash(
            List<String> additionalDangerousFiles, List<String> additionalDangerousDirectories) {
        super(
                NAME,
                DESCRIPTION,
                inputSchema(),
                /* isReadOnly */ false,
                /* isConcurrencySafe */ false,
                /* isMcp */ false,
                /* mcpName */ null,
                /* isExternalTool */ false,
                /* isStateInjected */ false);
        this.dangerousFiles =
                mergeLists(
                        ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES,
                        additionalDangerousFiles);
        this.dangerousDirectories =
                mergeLists(
                        ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES,
                        additionalDangerousDirectories);
        this.parser = new BashCommandParser();
    }

    private static List<String> mergeLists(List<String> base, List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return base;
        }
        List<String> merged = new ArrayList<>(base.size() + extra.size());
        merged.addAll(base);
        merged.addAll(extra);
        return List.copyOf(merged);
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "string");
        command.put("description", "Shell command to execute.");

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("type", "string");
        description.put(
                "description",
                "Optional short description shown to reviewers when permission is required.");

        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "integer");
        timeout.put("minimum", 1);
        timeout.put("maximum", MAX_TIMEOUT_MS);
        timeout.put("default", DEFAULT_TIMEOUT_MS);
        timeout.put("description", "Timeout in milliseconds; defaults to 120000, max 600000.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", command);
        properties.put("description", description);
        properties.put("timeout", timeout);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("command"));
        return schema;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContext ctx) {
        Object commandRaw = toolInput == null ? null : toolInput.get("command");
        if (!(commandRaw instanceof String command) || command.isBlank()) {
            return Mono.just(PermissionDecision.passthrough("Bash missing command"));
        }
        return Mono.fromCallable(() -> decide(command, ctx))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private PermissionDecision decide(String command, PermissionContext ctx) {
        String injection = parser.checkInjectionRisk(command);
        if (injection != null) {
            return PermissionDecision.builder()
                    .behavior(PermissionBehavior.ASK)
                    .message("Permission required: " + injection)
                    .decisionReason("Safety check: command contains dynamic expansion")
                    .build();
        }
        String dangerous = parser.checkDangerousCommand(command);
        if (dangerous != null) {
            return PermissionDecision.builder()
                    .behavior(PermissionBehavior.ASK)
                    .message("Permission required: dangerous pattern: " + dangerous)
                    .decisionReason("Safety check: dangerous command pattern detected")
                    .build();
        }
        String sedError = parser.checkSedConstraints(command, dangerousFiles);
        if (sedError != null) {
            return PermissionDecision.builder()
                    .behavior(PermissionBehavior.ASK)
                    .message("Permission required: " + sedError)
                    .decisionReason("Safety check: sed in-place modification of sensitive file")
                    .build();
        }
        List<String> hits = collectDangerousPaths(command);
        if (!hits.isEmpty()) {
            return PermissionDecision.builder()
                    .behavior(PermissionBehavior.ASK)
                    .message(
                            "Permission required: bash operates on sensitive paths: "
                                    + String.join(", ", hits))
                    .decisionReason("Safety check: dangerous file or directory in bash command")
                    .build();
        }
        String removalTarget = findCriticalRemoval(command);
        if (removalTarget != null) {
            return PermissionDecision.builder()
                    .behavior(PermissionBehavior.ASK)
                    .message("Dangerous removal operation detected: '" + removalTarget + "'")
                    .decisionReason("Safety check: dangerous removal of critical system path")
                    .build();
        }
        if (parser.isReadOnlyCommand(command)) {
            return PermissionDecision.allow("Permission granted for read-only command");
        }
        if (ctx != null
                && ctx.getMode() == PermissionMode.ACCEPT_EDITS
                && isAcceptEditsFilesystemCommand(command)) {
            return PermissionDecision.allow(
                    "Permission granted for filesystem command (accept edits mode)");
        }
        return PermissionDecision.passthrough("Execute bash: " + command);
    }

    private List<String> collectDangerousPaths(String command) {
        Set<String> hits = new LinkedHashSet<>();
        for (BashCommandParser.FilePathRef ref : parser.extractFilePaths(command)) {
            if (isDangerousPath(ref.filePath())) {
                hits.add(ref.filePath());
            }
        }
        return List.copyOf(hits);
    }

    private static String findCriticalRemoval(String command) {
        String normalized = command.replaceAll("\\s+", " ").trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!(lower.contains("rm ") || lower.contains("rmdir "))) {
            return null;
        }
        String[] tokens = normalized.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (!(token.equals("rm") || token.equals("rmdir"))) {
                continue;
            }
            for (int j = i + 1; j < tokens.length; j++) {
                String arg = tokens[j];
                if (arg.startsWith("-")) {
                    continue;
                }
                String stripped = stripTrailingSlash(arg);
                if (BashConstants.CRITICAL_REMOVAL_PATHS.contains(stripped)) {
                    return arg;
                }
                if (BashConstants.CRITICAL_REMOVAL_PATHS.contains(stripped + "/*")) {
                    return arg;
                }
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String value) {
        if (value.length() > 1 && value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean isAcceptEditsFilesystemCommand(String command) {
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String firstToken = trimmed.split("\\s+", 2)[0];
        return BashConstants.ACCEPT_EDITS_FILESYSTEM_COMMANDS.contains(firstToken);
    }

    @Override
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        if (ruleContent == null) {
            return true;
        }
        Object commandRaw = toolInput == null ? null : toolInput.get("command");
        if (!(commandRaw instanceof String command) || command.isBlank()) {
            return false;
        }
        return BashRulePatternMatcher.matches(ruleContent, command.trim());
    }

    @Override
    public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
        Object commandRaw = toolInput == null ? null : toolInput.get("command");
        if (!(commandRaw instanceof String command) || command.isBlank()) {
            return List.of();
        }
        List<String> prefixes = parser.extractCommandPrefixes(command, 5);
        if (prefixes.isEmpty()) {
            return List.of();
        }
        List<PermissionRule> suggestions = new ArrayList<>(prefixes.size());
        for (String prefix : prefixes) {
            suggestions.add(
                    new PermissionRule(
                            getName(), prefix + ":*", PermissionBehavior.ALLOW, "suggested"));
        }
        return List.copyOf(suggestions);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param == null ? Map.of() : param.getInput();
        Object commandRaw = input.get("command");
        if (!(commandRaw instanceof String command) || command.isBlank()) {
            return Mono.just(errorResult(param, "command is required"));
        }
        long timeoutMs = readLong(input.get("timeout"), DEFAULT_TIMEOUT_MS);
        if (timeoutMs < 1) {
            return Mono.just(errorResult(param, "timeout must be >= 1"));
        }
        if (timeoutMs > MAX_TIMEOUT_MS) {
            timeoutMs = MAX_TIMEOUT_MS;
        }
        final long effectiveTimeout = timeoutMs;
        return Mono.fromCallable(() -> runShell(param, command, effectiveTimeout))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock runShell(ToolCallParam param, String command, long timeoutMs) {
        ProcessBuilder builder;
        boolean isWindows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (isWindows) {
            builder = new ProcessBuilder("cmd", "/c", command);
        } else {
            builder = new ProcessBuilder("/bin/bash", "-c", command);
        }
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return errorResult(param, "Failed to launch shell: " + e.getMessage());
        }
        List<String> stdout = new ArrayList<>();
        try {
            try (BufferedReader reader = newReader(process.getInputStream())) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.add(line);
                }
            }
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return errorResult(param, "Bash command timed out after " + timeoutMs + "ms");
            }
            int exit = process.exitValue();
            String body = String.join("\n", stdout);
            if (exit != 0) {
                return errorResult(
                        param,
                        "Command exited with code " + exit + (body.isEmpty() ? "" : ":\n" + body));
            }
            return successResult(param, body.isEmpty() ? "(no output)" : body);
        } catch (IOException e) {
            return errorResult(param, "Failed reading shell output: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return errorResult(param, "Bash command was interrupted");
        }
    }

    private static BufferedReader newReader(InputStream stream) {
        return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    private static long readLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @SuppressWarnings("unused")
    private static boolean isUnderAnyWorkingDirectory(String filePath, PermissionContext ctx) {
        Path target = Path.of(filePath).toAbsolutePath().normalize();
        for (AdditionalWorkingDirectory wd : ctx.getWorkingDirectories().values()) {
            Path dir = Path.of(wd.path()).toAbsolutePath().normalize();
            if (target.startsWith(dir)) {
                return true;
            }
        }
        return false;
    }

    private ToolResultBlock successResult(ToolCallParam param, String text) {
        String id =
                param == null || param.getToolUseBlock() == null
                        ? null
                        : param.getToolUseBlock().getId();
        return new ToolResultBlock(id, getName(), TextBlock.builder().text(text).build());
    }

    private ToolResultBlock errorResult(ToolCallParam param, String message) {
        String id =
                param == null || param.getToolUseBlock() == null
                        ? null
                        : param.getToolUseBlock().getId();
        return new ToolResultBlock(
                id, getName(), TextBlock.builder().text("Error: " + message).build());
    }

    /**
     * Wildcard matcher for Bash rule patterns. Supports {@code "prefix:*"} (matches commands
     * starting with {@code prefix }), trailing-{@code *} wildcards (matches the literal prefix
     * exactly too), and embedded {@code *} sequences via regex. {@code \*} matches a literal
     * asterisk.
     */
    static final class BashRulePatternMatcher {

        private BashRulePatternMatcher() {
            // utility
        }

        static boolean matches(String pattern, String command) {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(command, "command");
            if (pattern.endsWith(":*")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (prefix.isEmpty()) {
                    return true;
                }
                return command.equals(prefix) || command.startsWith(prefix + " ");
            }
            if (pattern.endsWith(" *")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (command.equals(prefix) || command.startsWith(prefix + " ")) {
                    return true;
                }
                // fall through to general regex semantics
            }
            if (pattern.contains("*")) {
                try {
                    return command.matches(globToRegex(pattern));
                } catch (RuntimeException e) {
                    return command.contains(pattern.replace("*", ""));
                }
            }
            return command.equals(pattern);
        }

        private static String globToRegex(String pattern) {
            StringBuilder sb = new StringBuilder("^");
            int i = 0;
            while (i < pattern.length()) {
                char c = pattern.charAt(i);
                if (c == '\\' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    sb.append("\\*");
                    i += 2;
                    continue;
                }
                switch (c) {
                    case '*' -> sb.append(".*");
                    case '.', '(', ')', '+', '{', '}', '^', '$', '|', '?', '[', ']' ->
                            sb.append('\\').append(c);
                    default -> sb.append(c);
                }
                i++;
            }
            sb.append("$");
            return sb.toString();
        }
    }
}
