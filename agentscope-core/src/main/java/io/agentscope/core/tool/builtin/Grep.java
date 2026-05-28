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
import io.agentscope.core.permission.PermissionContext;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Searches file contents using the external {@code rg} (ripgrep) executable.
 *
 * <p>Three output modes are supported via {@code output_mode}:
 *
 * <ul>
 *   <li>{@code "content"} (default) — each matching line, prefixed with line number
 *   <li>{@code "files_with_matches"} — one matching file path per line
 *   <li>{@code "count"} — {@code path:match_count} per file
 * </ul>
 *
 * <p>The tool fails fast with an actionable error message when {@code rg} is not on {@code PATH}.
 * Long-running searches abort after {@value #DEFAULT_TIMEOUT_SECONDS} seconds.
 */
public final class Grep extends ToolBase {

    public static final int DEFAULT_HEAD_LIMIT = 250;

    public static final long DEFAULT_TIMEOUT_SECONDS = 60L;

    private static final String NAME = "Grep";

    private static final String DESCRIPTION =
            "Search file contents using ripgrep. Supports content/files_with_matches/count output "
                    + "modes, context lines (-A/-B/-C), case insensitivity, type and glob filters, "
                    + "multiline matching, and result pagination via head_limit/offset.";

    /** Directories the search skips by default; commonly produce noise or large binary indexes. */
    public static final List<String> DEFAULT_EXCLUDED_DIRS =
            List.of(
                    ".git",
                    ".hg",
                    ".svn",
                    "node_modules",
                    "__pycache__",
                    ".pytest_cache",
                    ".mypy_cache",
                    ".tox",
                    "target",
                    "build",
                    "dist",
                    ".gradle",
                    ".idea",
                    ".vscode");

    public Grep() {
        super(
                NAME,
                DESCRIPTION,
                inputSchema(),
                /* isReadOnly */ true,
                /* isConcurrencySafe */ true,
                /* isMcp */ false,
                /* mcpName */ null,
                /* isExternalTool */ false,
                /* isStateInjected */ false);
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("type", "string");
        pattern.put("description", "Regular expression to search for (ripgrep PCRE2-compatible).");

        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "File or directory to search; defaults to current directory.");

        Map<String, Object> outputMode = new LinkedHashMap<>();
        outputMode.put("type", "string");
        outputMode.put("enum", List.of("content", "files_with_matches", "count"));
        outputMode.put("default", "content");
        outputMode.put(
                "description",
                "content: print matching lines; files_with_matches: only matching paths; count: "
                        + "per-file match counts.");

        Map<String, Object> caseInsensitive = new LinkedHashMap<>();
        caseInsensitive.put("type", "boolean");
        caseInsensitive.put("default", false);
        caseInsensitive.put("description", "Match case-insensitively.");

        Map<String, Object> multiline = new LinkedHashMap<>();
        multiline.put("type", "boolean");
        multiline.put("default", false);
        multiline.put("description", "Allow patterns to match across line boundaries.");

        Map<String, Object> contextBefore = new LinkedHashMap<>();
        contextBefore.put("type", "integer");
        contextBefore.put("minimum", 0);
        contextBefore.put(
                "description", "Number of leading context lines per match (content mode).");

        Map<String, Object> contextAfter = new LinkedHashMap<>();
        contextAfter.put("type", "integer");
        contextAfter.put("minimum", 0);
        contextAfter.put(
                "description", "Number of trailing context lines per match (content mode).");

        Map<String, Object> contextAround = new LinkedHashMap<>();
        contextAround.put("type", "integer");
        contextAround.put("minimum", 0);
        contextAround.put(
                "description",
                "Symmetric context lines per match (content mode); shorthand for -A and -B.");

        Map<String, Object> glob = new LinkedHashMap<>();
        glob.put("type", "string");
        glob.put("description", "Restrict the search to paths matching this glob.");

        Map<String, Object> type = new LinkedHashMap<>();
        type.put("type", "string");
        type.put("description", "Restrict search to a ripgrep file-type alias (e.g. py, java).");

        Map<String, Object> headLimit = new LinkedHashMap<>();
        headLimit.put("type", "integer");
        headLimit.put("minimum", 1);
        headLimit.put("default", DEFAULT_HEAD_LIMIT);
        headLimit.put(
                "description",
                "Maximum number of result lines to retain after rendering (content mode).");

        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("type", "integer");
        offset.put("minimum", 0);
        offset.put("default", 0);
        offset.put("description", "Number of result lines to drop before applying head_limit.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", pattern);
        properties.put("path", path);
        properties.put("output_mode", outputMode);
        properties.put("-i", caseInsensitive);
        properties.put("multiline", multiline);
        properties.put("-A", contextAfter);
        properties.put("-B", contextBefore);
        properties.put("-C", contextAround);
        properties.put("glob", glob);
        properties.put("type", type);
        properties.put("head_limit", headLimit);
        properties.put("offset", offset);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("pattern"));
        return schema;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContext ctx) {
        return Mono.just(PermissionDecision.passthrough("Grep"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param == null ? Map.of() : param.getInput();
        Object patternRaw = input.get("pattern");
        if (!(patternRaw instanceof String pattern) || pattern.isEmpty()) {
            return Mono.just(errorResult(param, "pattern is required"));
        }

        String mode = stringOrDefault(input.get("output_mode"), "content");
        if (!(mode.equals("content")
                || mode.equals("files_with_matches")
                || mode.equals("count"))) {
            return Mono.just(
                    errorResult(
                            param,
                            "output_mode must be one of content, files_with_matches, count"));
        }

        Object pathRaw = input.get("path");
        String searchPath =
                pathRaw instanceof String s && !s.isBlank() ? s : System.getProperty("user.dir");
        Path resolvedPath;
        try {
            resolvedPath = Paths.get(searchPath).toAbsolutePath().normalize();
        } catch (IllegalArgumentException e) {
            return Mono.just(errorResult(param, "Invalid path: " + searchPath));
        }

        boolean caseInsensitive = booleanOrDefault(input.get("-i"), false);
        boolean multiline = booleanOrDefault(input.get("multiline"), false);
        Integer contextAfter = intOrNull(input.get("-A"));
        Integer contextBefore = intOrNull(input.get("-B"));
        Integer contextAround = intOrNull(input.get("-C"));
        String glob = stringOrNull(input.get("glob"));
        String typeAlias = stringOrNull(input.get("type"));
        int headLimit = intOrDefault(input.get("head_limit"), DEFAULT_HEAD_LIMIT);
        if (headLimit < 1) {
            return Mono.just(errorResult(param, "head_limit must be >= 1"));
        }
        int offset = intOrDefault(input.get("offset"), 0);
        if (offset < 0) {
            return Mono.just(errorResult(param, "offset must be >= 0"));
        }

        List<String> command = new ArrayList<>();
        command.add("rg");
        command.add("--color=never");
        if (caseInsensitive) {
            command.add("-i");
        }
        if (multiline) {
            command.add("-U");
            command.add("--multiline-dotall");
        }
        if (mode.equals("files_with_matches")) {
            command.add("-l");
        } else if (mode.equals("count")) {
            command.add("-c");
        } else {
            command.add("-n");
            if (contextAround != null && contextAround > 0) {
                command.add("-C");
                command.add(Integer.toString(contextAround));
            } else {
                if (contextBefore != null && contextBefore > 0) {
                    command.add("-B");
                    command.add(Integer.toString(contextBefore));
                }
                if (contextAfter != null && contextAfter > 0) {
                    command.add("-A");
                    command.add(Integer.toString(contextAfter));
                }
            }
        }
        if (glob != null) {
            command.add("--glob");
            command.add(glob);
        }
        if (typeAlias != null) {
            command.add("--type");
            command.add(typeAlias);
        }
        for (String excluded : DEFAULT_EXCLUDED_DIRS) {
            command.add("--glob");
            command.add("!**/" + excluded + "/**");
        }
        command.add("--");
        command.add(pattern);
        command.add(resolvedPath.toString());

        return Mono.fromCallable(() -> runRipgrep(param, command, mode, headLimit, offset))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock runRipgrep(
            ToolCallParam param, List<String> command, String mode, int headLimit, int offset) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("no such file")
                    || msg.contains("cannot run program")
                    || msg.contains("not found")) {
                return errorResult(
                        param,
                        "ripgrep (rg) is not installed or not on PATH. Install with"
                                + " 'brew install ripgrep' (macOS) or 'apt-get install ripgrep'"
                                + " (Debian/Ubuntu).");
            }
            return errorResult(param, "Failed to launch rg: " + e.getMessage());
        }

        List<String> stdoutLines = new ArrayList<>();
        List<String> stderrLines = new ArrayList<>();
        try {
            Thread stderrPump = drainStream(process.getErrorStream(), stderrLines);
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdoutLines.add(line);
                }
            }
            stderrPump.join();
            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return errorResult(
                        param,
                        "Grep timed out after "
                                + Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS).getSeconds()
                                + "s");
            }
            int exit = process.exitValue();
            if (exit == 1) {
                return successResult(param, "No matches found");
            }
            if (exit != 0) {
                String stderr = String.join("\n", stderrLines).trim();
                return errorResult(
                        param,
                        "rg exited with code " + exit + (stderr.isEmpty() ? "" : ": " + stderr));
            }
        } catch (IOException e) {
            return errorResult(param, "Failed to read rg output: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return errorResult(param, "Grep was interrupted");
        }

        if (stdoutLines.isEmpty()) {
            return successResult(param, "No matches found");
        }
        if (!"content".equals(mode)) {
            return successResult(param, String.join("\n", stdoutLines));
        }
        int from = Math.min(offset, stdoutLines.size());
        int to = Math.min(from + headLimit, stdoutLines.size());
        List<String> sliced = stdoutLines.subList(from, to);
        String body = String.join("\n", sliced);
        if (to < stdoutLines.size()) {
            body =
                    body
                            + "\n... ("
                            + (stdoutLines.size() - to)
                            + " more lines truncated; increase head_limit or use offset)";
        }
        return successResult(param, body);
    }

    private static Thread drainStream(InputStream stream, List<String> sink) {
        Thread t =
                new Thread(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    stream, StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    synchronized (sink) {
                                        sink.add(line);
                                    }
                                }
                            } catch (IOException ignored) {
                                // stream closed; nothing to do
                            }
                        },
                        "grep-stderr-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String stringOrDefault(Object value, String def) {
        return value instanceof String s && !s.isBlank() ? s : def;
    }

    private static String stringOrNull(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static boolean booleanOrDefault(Object value, boolean def) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return def;
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static int intOrDefault(Object value, int def) {
        Integer parsed = intOrNull(value);
        return parsed == null ? def : parsed;
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
}
