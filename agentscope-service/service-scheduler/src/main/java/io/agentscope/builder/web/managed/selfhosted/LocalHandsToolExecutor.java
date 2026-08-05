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
package io.agentscope.builder.web.managed.selfhosted;

import io.agentscope.harness.agent.tool.ShellExecuteTool;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Executes the self-hosted hands toolset against a local workspace directory. Used by both the
 * standalone {@code HandsWorkerMain} and the in-process development worker.
 */
public final class LocalHandsToolExecutor {

    private static final int DEFAULT_EXEC_TIMEOUT_SECONDS = 30;
    private static final int MAX_CAPTURED_BYTES = 200_000;
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final Path workDir;

    public LocalHandsToolExecutor(Path workDir) {
        this.workDir = workDir;
    }

    /** Workspace root for this session. */
    public Path workDir() {
        return workDir;
    }

    /**
     * Runs one tool call and returns a textual result suitable for {@code user.tool_result}
     * content.
     */
    public ToolExecResult execute(String name, Map<String, Object> input) {
        try {
            Files.createDirectories(workDir);
            return switch (name == null ? "" : name) {
                case ShellExecuteTool.NAME, "bash" -> execShell(input);
                case "read_file" -> readFile(input);
                case "write_file" -> writeFile(input);
                case "edit_file" -> editFile(input);
                case "grep_files" -> grepFiles(input);
                case "glob_files" -> globFiles(input);
                case "list_files" -> listFiles(input);
                default -> ToolExecResult.error("Unknown hands tool: " + name);
            };
        } catch (Exception ex) {
            return ToolExecResult.error(
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }

    private ToolExecResult execShell(Map<String, Object> input) throws Exception {
        String command = stringOf(input, "command");
        if (command == null || command.isBlank()) {
            return ToolExecResult.error("Missing required parameter: command");
        }
        String workingDirectory = stringOf(input, "working_directory");
        String effective = command;
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            String wd = workingDirectory.strip();
            if (wd.startsWith("/") || wd.startsWith("~") || wd.contains("..")) {
                return ToolExecResult.error(
                        "working_directory must be a relative path within the workspace");
            }
            if (WINDOWS) {
                if (wd.contains("\"")) {
                    return ToolExecResult.error("working_directory must not contain quotes");
                }
                effective = "cd /d \"" + wd + "\" && " + command;
            } else {
                effective = "cd '" + wd.replace("'", "'\\''") + "' && " + command;
            }
        }
        int timeout = intOf(input, "timeout", DEFAULT_EXEC_TIMEOUT_SECONDS);
        // Shell choice is OS-dependent so the executor also works on Windows hosts.
        ProcessBuilder pb =
                WINDOWS
                        ? new ProcessBuilder("cmd.exe", "/c", effective)
                        : new ProcessBuilder("/bin/bash", "-lc", effective);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain =
                new Thread(
                        () -> {
                            try (InputStream in = process.getInputStream()) {
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = in.read(buf)) >= 0) {
                                    int remaining = MAX_CAPTURED_BYTES - captured.size();
                                    if (remaining <= 0) {
                                        break;
                                    }
                                    captured.write(buf, 0, Math.min(n, remaining));
                                }
                            } catch (IOException ignored) {
                                // best-effort capture
                            }
                        },
                        "hands-exec-drain");
        drain.setDaemon(true);
        drain.start();
        boolean finished = process.waitFor(Math.max(1, timeout), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            drain.join(1000);
            return ToolExecResult.error("Command timed out after " + timeout + "s");
        }
        drain.join(1000);
        String output = captured.toString(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("Exit code: ").append(process.exitValue()).append("\n");
        if (!output.isBlank()) {
            sb.append("\n").append(output);
        }
        if (captured.size() >= MAX_CAPTURED_BYTES) {
            sb.append("\n(output was truncated)");
        }
        boolean error = process.exitValue() != 0;
        return new ToolExecResult(sb.toString(), error);
    }

    private ToolExecResult readFile(Map<String, Object> input) throws IOException {
        Path path = resolve(stringOf(input, "path"));
        if (!Files.exists(path)) {
            return ToolExecResult.error("File not found: " + path);
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int offset = intOf(input, "offset", 0);
        int limit = intOf(input, "limit", 0);
        if (offset < 0) {
            offset = 0;
        }
        if (offset >= lines.size()) {
            return new ToolExecResult("", false);
        }
        int end = limit > 0 ? Math.min(lines.size(), offset + limit) : lines.size();
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < end; i++) {
            sb.append(String.format(Locale.ROOT, "%6d|%s%n", i + 1, lines.get(i)));
        }
        return new ToolExecResult(sb.toString(), false);
    }

    private ToolExecResult writeFile(Map<String, Object> input) throws IOException {
        Path path = resolve(stringOf(input, "path"));
        String content = stringOf(input, "content");
        if (content == null) {
            content = "";
        }
        Files.createDirectories(path.getParent() != null ? path.getParent() : workDir);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return new ToolExecResult("Written to " + relativize(path), false);
    }

    private ToolExecResult editFile(Map<String, Object> input) throws IOException {
        Path path = resolve(stringOf(input, "path"));
        String oldString = stringOf(input, "old_string");
        String newString = stringOf(input, "new_string");
        boolean replaceAll = Boolean.TRUE.equals(input.get("replace_all"));
        if (oldString == null || newString == null) {
            return ToolExecResult.error("old_string and new_string are required");
        }
        if (!Files.exists(path)) {
            return ToolExecResult.error("File not found: " + path);
        }
        String original = Files.readString(path, StandardCharsets.UTF_8);
        int count = countOccurrences(original, oldString);
        if (count == 0) {
            return ToolExecResult.error("old_string not found in " + relativize(path));
        }
        if (!replaceAll && count > 1) {
            return ToolExecResult.error(
                    "old_string is not unique (" + count + " occurrences); set replace_all=true");
        }
        String updated =
                replaceAll
                        ? original.replace(oldString, newString)
                        : original.replaceFirst(
                                java.util.regex.Pattern.quote(oldString),
                                java.util.regex.Matcher.quoteReplacement(newString));
        Files.writeString(path, updated, StandardCharsets.UTF_8);
        return new ToolExecResult(
                "Edited " + relativize(path) + " (" + (replaceAll ? count : 1) + " replacement(s))",
                false);
    }

    private ToolExecResult grepFiles(Map<String, Object> input) throws IOException {
        String pattern = stringOf(input, "pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolExecResult.error("pattern is required");
        }
        Path root = resolveOptional(stringOf(input, "path"));
        String glob = stringOf(input, "glob");
        List<String> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> glob == null || glob.isBlank() || matchesGlob(p, glob))
                    .forEach(
                            p -> {
                                try {
                                    List<String> lines =
                                            Files.readAllLines(p, StandardCharsets.UTF_8);
                                    for (int i = 0; i < lines.size(); i++) {
                                        if (lines.get(i).contains(pattern)) {
                                            matches.add(
                                                    relativize(p)
                                                            + ":"
                                                            + (i + 1)
                                                            + ":"
                                                            + lines.get(i));
                                        }
                                    }
                                } catch (IOException ignored) {
                                    // skip unreadable files
                                }
                            });
        }
        if (matches.isEmpty()) {
            return new ToolExecResult("No matches found", false);
        }
        return new ToolExecResult(String.join("\n", matches), false);
    }

    private ToolExecResult globFiles(Map<String, Object> input) throws IOException {
        String pattern = stringOf(input, "pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolExecResult.error("pattern is required");
        }
        Path root = resolveOptional(stringOf(input, "path"));
        String normalized = pattern.replace('\\', '/');
        List<String> found = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> matchesGlob(p, normalized))
                    .forEach(
                            p -> {
                                try {
                                    found.add(relativize(p) + " (" + Files.size(p) + " bytes)");
                                } catch (IOException ex) {
                                    found.add(relativize(p));
                                }
                            });
        }
        if (found.isEmpty()) {
            return new ToolExecResult("No matching files found", false);
        }
        return new ToolExecResult(String.join("\n", found), false);
    }

    private ToolExecResult listFiles(Map<String, Object> input) throws IOException {
        Path path = resolve(stringOf(input, "path"));
        if (!Files.isDirectory(path)) {
            return ToolExecResult.error("Empty or not a directory: " + relativize(path));
        }
        try (Stream<Path> stream = Files.list(path)) {
            String listing =
                    stream.sorted()
                            .map(
                                    p -> {
                                        boolean dir = Files.isDirectory(p);
                                        try {
                                            return (dir ? "[DIR]  " : "[FILE] ")
                                                    + relativize(p)
                                                    + (dir ? "" : " (" + Files.size(p) + " bytes)");
                                        } catch (IOException ex) {
                                            return (dir ? "[DIR]  " : "[FILE] ") + relativize(p);
                                        }
                                    })
                            .collect(Collectors.joining("\n"));
            if (listing.isBlank()) {
                return new ToolExecResult("Empty or not a directory: " + relativize(path), false);
            }
            return new ToolExecResult(listing, false);
        }
    }

    private Path resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path candidate = Path.of(raw);
        if (!candidate.isAbsolute()) {
            candidate = workDir.resolve(raw).normalize();
        } else {
            candidate = candidate.normalize();
        }
        if (!candidate.startsWith(workDir.normalize())) {
            throw new IllegalArgumentException("path escapes workspace: " + raw);
        }
        return candidate;
    }

    private Path resolveOptional(String raw) {
        if (raw == null || raw.isBlank()) {
            return workDir;
        }
        return resolve(raw);
    }

    private String relativize(Path path) {
        try {
            return workDir.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            return path.toString();
        }
    }

    private static boolean matchesGlob(Path path, String glob) {
        String name = path.getFileName().toString();
        String full = path.toString().replace('\\', '/');
        String g = glob.replace('\\', '/');
        if (g.startsWith("**/")) {
            g = g.substring(3);
        }
        return java.nio.file.FileSystems.getDefault()
                        .getPathMatcher("glob:" + g)
                        .matches(Path.of(name))
                || java.nio.file.FileSystems.getDefault()
                        .getPathMatcher("glob:" + glob.replace('\\', '/'))
                        .matches(Path.of(full));
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String stringOf(Map<String, Object> input, String key) {
        if (input == null) {
            return null;
        }
        Object v = input.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intOf(Map<String, Object> input, String key, int defaultValue) {
        if (input == null || input.get(key) == null) {
            return defaultValue;
        }
        Object v = input.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /** Result of a local hands tool execution. */
    public record ToolExecResult(String content, boolean error) {
        public static ToolExecResult error(String message) {
            return new ToolExecResult(message, true);
        }
    }
}
