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
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContext;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.ToolContext;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.permission.ToolBase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reads a slice of a text file and stores the contents in the shared {@link ToolContext} cache so
 * subsequent {@link Write} and {@link Edit} invocations can enforce the Read-before-Write
 * invariant.
 *
 * <p>The tool is read-only and concurrency safe. Each invocation reads at most {@code limit} lines
 * starting at a 1-based {@code offset}; lines longer than {@link #getMaxLineCharacters()} are
 * truncated with a {@code " [truncated]"} suffix. Output is rendered in {@code cat -n} style
 * ({@code "%6d\t%s\n"}).
 */
public final class Read extends ToolBase {

    /** Default per-line character cap; longer lines are truncated. */
    public static final int DEFAULT_MAX_LINE_CHARACTERS = 2000;

    /** Default {@code limit} applied when the agent does not specify one. */
    public static final int DEFAULT_LIMIT = 2000;

    private static final String NAME = "Read";

    private static final String DESCRIPTION =
            "Read a slice of a text file and register its lines with the agent file cache. "
                    + "Use offset (1-based) and limit to page through large files. Lines longer "
                    + "than the configured maximum are truncated. The cache entry is required "
                    + "before Write or Edit can modify the same file.";

    private final ToolContext context;
    private final int maxLineCharacters;

    /** Creates a {@code Read} tool bound to {@code context} with default line/limit caps. */
    public Read(ToolContext context) {
        this(context, DEFAULT_MAX_LINE_CHARACTERS);
    }

    /**
     * @param context backing {@link ToolContext} used to cache file contents
     * @param maxLineCharacters maximum characters preserved per line before truncation; must be
     *     positive
     */
    public Read(ToolContext context, int maxLineCharacters) {
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
        this.context = Objects.requireNonNull(context, "context must not be null");
        if (maxLineCharacters <= 0) {
            throw new IllegalArgumentException("maxLineCharacters must be > 0");
        }
        this.maxLineCharacters = maxLineCharacters;
    }

    public int getMaxLineCharacters() {
        return maxLineCharacters;
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "Absolute path to the file to read.");

        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("type", "integer");
        offset.put("description", "1-based line number to start reading from.");
        offset.put("minimum", 1);
        offset.put("default", 1);

        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("description", "Maximum number of lines to return.");
        limit.put("minimum", 1);
        limit.put("maximum", DEFAULT_LIMIT);
        limit.put("default", DEFAULT_LIMIT);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", filePath);
        properties.put("offset", offset);
        properties.put("limit", limit);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("file_path"));
        return schema;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContext ctx) {
        Object filePath = toolInput == null ? null : toolInput.get("file_path");
        String label = filePath == null ? "" : " " + filePath;
        return Mono.just(PermissionDecision.passthrough("Read" + label));
    }

    @Override
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        if (ruleContent == null) {
            return true;
        }
        Object filePath = toolInput == null ? null : toolInput.get("file_path");
        if (!(filePath instanceof String path) || path.isBlank()) {
            return false;
        }
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + ruleContent);
            return matcher.matches(Path.of(path));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
        Object filePath = toolInput == null ? null : toolInput.get("file_path");
        List<PermissionRule> suggestions = new ArrayList<>();
        if (filePath instanceof String path && !path.isBlank()) {
            Path parent = Path.of(path).getParent();
            if (parent != null) {
                // Normalise to forward slashes so the suggested glob looks the same on Windows
                // (where Path.toString uses '\\') as on POSIX. The matchRule glob matcher and
                // the user-visible rule are both expected to use '/'.
                String parentGlob = parent.toString().replace('\\', '/') + "/*";
                suggestions.add(
                        new PermissionRule(
                                getName(), parentGlob, PermissionBehavior.ALLOW, "suggested"));
            }
        }
        suggestions.add(new PermissionRule(getName(), null, PermissionBehavior.ALLOW, "suggested"));
        return List.copyOf(suggestions);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param == null ? Map.of() : param.getInput();
        Object filePathRaw = input.get("file_path");
        if (!(filePathRaw instanceof String filePath) || filePath.isBlank()) {
            return Mono.just(errorResult(param, "file_path is required"));
        }
        int offset = readInt(input.get("offset"), 1);
        if (offset < 1) {
            return Mono.just(errorResult(param, "offset must be >= 1"));
        }
        int limit = readInt(input.get("limit"), DEFAULT_LIMIT);
        if (limit < 1) {
            return Mono.just(errorResult(param, "limit must be >= 1"));
        }

        return Mono.fromCallable(
                        () -> Files.readAllLines(Path.of(filePath), StandardCharsets.UTF_8))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        rawLines -> {
                            List<String> truncated = truncateLines(rawLines);
                            int total = truncated.size();
                            int from = Math.min(offset - 1, total);
                            int to = Math.min(from + limit, total);
                            List<String> slice = truncated.subList(from, to);
                            String rendered = renderCatN(slice, offset);
                            return context.cacheFile(filePath, truncated)
                                    .thenReturn(successResult(param, rendered));
                        })
                .onErrorResume(
                        NoSuchFileException.class,
                        e -> Mono.just(errorResult(param, "File not found: " + filePath)))
                .onErrorResume(
                        IOException.class,
                        e ->
                                Mono.just(
                                        errorResult(
                                                param,
                                                "Failed to read "
                                                        + filePath
                                                        + ": "
                                                        + e.getMessage())));
    }

    private List<String> truncateLines(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            if (line.length() > maxLineCharacters) {
                out.add(line.substring(0, maxLineCharacters) + " [truncated]");
            } else {
                out.add(line);
            }
        }
        return out;
    }

    private static String renderCatN(List<String> slice, int startLine) {
        StringBuilder sb = new StringBuilder();
        int lineNo = startLine;
        for (String line : slice) {
            sb.append(String.format(Locale.ROOT, "%6d\t%s%n", lineNo, line));
            lineNo++;
        }
        return sb.toString();
    }

    private static int readInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
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
