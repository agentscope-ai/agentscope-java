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
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Lists filesystem entries that match a glob {@code pattern}, ordered by most-recent modification
 * time.
 *
 * <p>The pattern is split on {@code '/'}; each segment is converted to a regular expression where
 * {@code *} matches any sequence of non-{@code /} characters, {@code ?} matches a single non-{@code
 * /} character, and bracket classes such as {@code [abc]} pass through. A {@code **} segment
 * recurses into all subdirectories. Hidden entries (filename beginning with {@code .}) are skipped
 * unless the segment explicitly starts with a literal dot.
 */
public final class Glob extends ToolBase {

    private static final String NAME = "Glob";

    private static final String DESCRIPTION =
            "Find filesystem entries matching a glob pattern. Supports ** for recursive descent and"
                    + " ?, *, [...] segment wildcards. Results are returned newest-first by file"
                    + " modification time.";

    public Glob() {
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
        pattern.put(
                "description", "Glob pattern; supports **, *, ?, [...] segments separated by '/'.");

        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put(
                "description",
                "Base directory to search; defaults to the current working directory.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", pattern);
        properties.put("path", path);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("pattern"));
        return schema;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContext ctx) {
        return Mono.just(PermissionDecision.passthrough("Glob"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param == null ? Map.of() : param.getInput();
        Object patternRaw = input.get("pattern");
        if (!(patternRaw instanceof String pattern) || pattern.isBlank()) {
            return Mono.just(errorResult(param, "pattern is required"));
        }
        Object pathRaw = input.get("path");
        String baseDirRaw =
                pathRaw instanceof String s && !s.isBlank() ? s : System.getProperty("user.dir");
        Path baseDir;
        try {
            baseDir = Paths.get(baseDirRaw).toAbsolutePath().normalize();
        } catch (IllegalArgumentException e) {
            return Mono.just(errorResult(param, "Invalid path: " + baseDirRaw));
        }

        return Mono.fromCallable(
                        () -> {
                            if (!Files.isDirectory(baseDir)) {
                                return errorResult(
                                        param, "Search path is not a directory: " + baseDir);
                            }
                            List<String> segments = splitPattern(pattern);
                            List<Path> matches = new ArrayList<>();
                            matchSegments(baseDir, segments, 0, matches);
                            if (matches.isEmpty()) {
                                return successResult(param, "No files found");
                            }
                            matches.sort(byModifiedTimeDescending());
                            StringBuilder sb = new StringBuilder();
                            for (Path p : matches) {
                                sb.append(p).append('\n');
                            }
                            if (sb.length() > 0) {
                                sb.setLength(sb.length() - 1);
                            }
                            return successResult(param, sb.toString());
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(
                        IOException.class,
                        e -> Mono.just(errorResult(param, "Glob failed: " + e.getMessage())));
    }

    private static List<String> splitPattern(String pattern) {
        String trimmed = pattern;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(trimmed.split("/")).filter(s -> !s.isEmpty()).toList();
    }

    private static void matchSegments(
            Path current, List<String> segments, int index, List<Path> matches) throws IOException {
        if (index >= segments.size()) {
            matches.add(current);
            return;
        }
        String segment = segments.get(index);
        boolean isLast = index == segments.size() - 1;

        if ("**".equals(segment)) {
            int nextIndex = index + 1;
            if (nextIndex >= segments.size()) {
                collectRecursively(current, matches);
                return;
            }
            matchSegments(current, segments, nextIndex, matches);
            walkDirectories(
                    current,
                    dir -> {
                        try {
                            matchSegments(dir, segments, nextIndex, matches);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            return;
        }

        Pattern regex = globSegmentToRegex(segment);
        if (!Files.isDirectory(current)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (name.startsWith(".") && !segment.startsWith(".")) {
                    continue;
                }
                if (!regex.matcher(name).matches()) {
                    continue;
                }
                if (isLast) {
                    matches.add(child);
                } else if (Files.isDirectory(child)) {
                    matchSegments(child, segments, index + 1, matches);
                }
            }
        }
    }

    private static void collectRecursively(Path root, List<Path> matches) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                matches.add(child);
                if (Files.isDirectory(child)) {
                    collectRecursively(child, matches);
                }
            }
        }
    }

    private static void walkDirectories(Path root, java.util.function.Consumer<Path> visitor)
            throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(child)) {
                    visitor.accept(child);
                    walkDirectories(child, visitor);
                }
            }
        }
    }

    private static Pattern globSegmentToRegex(String segment) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < segment.length()) {
            char c = segment.charAt(i);
            switch (c) {
                case '*' -> sb.append("[^/]*");
                case '?' -> sb.append("[^/]");
                case '[' -> {
                    int closing = segment.indexOf(']', i + 1);
                    if (closing < 0) {
                        sb.append(Pattern.quote("["));
                    } else {
                        sb.append('[').append(segment, i + 1, closing).append(']');
                        i = closing;
                    }
                }
                case '.', '(', ')', '+', '{', '}', '^', '$', '|', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
            i++;
        }
        sb.append("$");
        return Pattern.compile(sb.toString(), Pattern.UNICODE_CASE | Pattern.CANON_EQ);
    }

    private static Comparator<Path> byModifiedTimeDescending() {
        return (a, b) -> {
            FileTime ta = safeMtime(a);
            FileTime tb = safeMtime(b);
            int cmp = tb.compareTo(ta);
            if (cmp != 0) {
                return cmp;
            }
            return a.toString().compareTo(b.toString());
        };
    }

    private static FileTime safeMtime(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
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
