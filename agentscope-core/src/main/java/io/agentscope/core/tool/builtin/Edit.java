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
import io.agentscope.core.state.ToolContext;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolDangerousPathConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Replaces {@code old_string} with {@code new_string} in an existing UTF-8 text file.
 *
 * <p>Unless {@code replace_all} is {@code true}, {@code old_string} must occur exactly once in the
 * file: zero matches return an error, multiple matches return an error and require the agent to
 * disambiguate. The file must have been read via {@link Read} first; the cache hit gates the edit.
 * The cache is refreshed with the new contents after a successful write.
 */
public final class Edit extends ToolBase {

    private static final String NAME = "Edit";

    private static final String DESCRIPTION =
            "Replace a substring inside an existing file. By default old_string must match "
                    + "exactly once; set replace_all=true to substitute every occurrence. The "
                    + "file must have been read with the Read tool prior to editing.";

    private final ToolContext context;

    public Edit(ToolContext context) {
        this(context, List.of(), List.of());
    }

    /**
     * @param context backing cache used to enforce the Read-before-Edit invariant
     * @param additionalDangerousFiles extra sensitive filenames appended to the defaults
     * @param additionalDangerousDirectories extra sensitive directory names appended to the
     *     defaults
     */
    public Edit(
            ToolContext context,
            List<String> additionalDangerousFiles,
            List<String> additionalDangerousDirectories) {
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
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.dangerousFiles =
                mergeLists(
                        ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES,
                        additionalDangerousFiles);
        this.dangerousDirectories =
                mergeLists(
                        ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES,
                        additionalDangerousDirectories);
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
        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "Absolute path to the file to edit.");

        Map<String, Object> oldString = new LinkedHashMap<>();
        oldString.put("type", "string");
        oldString.put("description", "Substring to find and replace; must match the file exactly.");

        Map<String, Object> newString = new LinkedHashMap<>();
        newString.put("type", "string");
        newString.put("description", "Replacement substring.");

        Map<String, Object> replaceAll = new LinkedHashMap<>();
        replaceAll.put("type", "boolean");
        replaceAll.put(
                "description",
                "If true, every occurrence is replaced; otherwise old_string must occur once.");
        replaceAll.put("default", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", filePath);
        properties.put("old_string", oldString);
        properties.put("new_string", newString);
        properties.put("replace_all", replaceAll);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("file_path", "old_string", "new_string"));
        return schema;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContext ctx) {
        Object filePathRaw = toolInput == null ? null : toolInput.get("file_path");
        if (!(filePathRaw instanceof String filePath) || filePath.isBlank()) {
            return Mono.just(PermissionDecision.passthrough("Edit missing file_path"));
        }
        if (isDangerousPath(filePath)) {
            return Mono.just(
                    PermissionDecision.builder()
                            .behavior(PermissionBehavior.ASK)
                            .message(
                                    "Permission required: Edit operates on sensitive path: "
                                            + filePath)
                            .decisionReason("Safety check: dangerous file or directory")
                            .build());
        }
        if (ctx != null
                && ctx.getMode() == PermissionMode.ACCEPT_EDITS
                && isUnderAnyWorkingDirectory(filePath, ctx)) {
            return Mono.just(
                    PermissionDecision.allow("Permission granted for Edit (accept edits mode)"));
        }
        return Mono.just(PermissionDecision.passthrough("Edit " + filePath));
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
                // (where Path.toString uses '\\') as on POSIX.
                String parentGlob = parent.toString().replace('\\', '/') + "/*";
                suggestions.add(
                        new PermissionRule(
                                getName(), parentGlob, PermissionBehavior.ALLOW, "suggested"));
            }
        }
        suggestions.add(new PermissionRule(getName(), null, PermissionBehavior.ALLOW, "suggested"));
        return List.copyOf(suggestions);
    }

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

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param == null ? Map.of() : param.getInput();
        Object filePathRaw = input.get("file_path");
        if (!(filePathRaw instanceof String filePath) || filePath.isBlank()) {
            return Mono.just(errorResult(param, "file_path is required"));
        }
        Object oldRaw = input.get("old_string");
        if (!(oldRaw instanceof String oldString)) {
            return Mono.just(errorResult(param, "old_string is required"));
        }
        Object newRaw = input.get("new_string");
        if (!(newRaw instanceof String newString)) {
            return Mono.just(errorResult(param, "new_string is required"));
        }
        boolean replaceAll = readBoolean(input.get("replace_all"), false);

        Path path = Path.of(filePath);

        return context.getCache(filePath)
                .flatMap(
                        opt -> {
                            if (opt.isEmpty()) {
                                return Mono.just(
                                        errorResult(
                                                param,
                                                "File "
                                                        + filePath
                                                        + " must be read via the Read tool before"
                                                        + " editing."));
                            }
                            return Mono.fromCallable(
                                            () -> Files.readString(path, StandardCharsets.UTF_8))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .flatMap(
                                            current -> {
                                                if (oldString.isEmpty()) {
                                                    return Mono.just(
                                                            errorResult(
                                                                    param,
                                                                    "old_string must not be"
                                                                            + " empty"));
                                                }
                                                int occurrences =
                                                        countOccurrences(current, oldString);
                                                if (occurrences == 0) {
                                                    return Mono.just(
                                                            errorResult(
                                                                    param,
                                                                    "old_string not found in "
                                                                            + filePath));
                                                }
                                                if (occurrences > 1 && !replaceAll) {
                                                    return Mono.just(
                                                            errorResult(
                                                                    param,
                                                                    "old_string matched "
                                                                            + occurrences
                                                                            + " times; set"
                                                                            + " replace_all=true or"
                                                                            + " provide more"
                                                                            + " context."));
                                                }
                                                String updated =
                                                        replaceAll
                                                                ? current.replace(
                                                                        oldString, newString)
                                                                : replaceFirst(
                                                                        current, oldString,
                                                                        newString);
                                                return Mono.fromCallable(
                                                                () -> {
                                                                    Files.writeString(
                                                                            path,
                                                                            updated,
                                                                            StandardCharsets.UTF_8);
                                                                    return splitLines(updated);
                                                                })
                                                        .subscribeOn(Schedulers.boundedElastic())
                                                        .flatMap(
                                                                lines ->
                                                                        context.cacheFile(
                                                                                        filePath,
                                                                                        lines)
                                                                                .thenReturn(
                                                                                        successResult(
                                                                                                param,
                                                                                                "Edited"
                                                                                                    + " "
                                                                                                        + filePath
                                                                                                        + ": "
                                                                                                        + (replaceAll
                                                                                                                ? occurrences
                                                                                                                : 1)
                                                                                                        + " replacement(s)")));
                                            });
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
                                                "Failed to edit "
                                                        + filePath
                                                        + ": "
                                                        + e.getMessage())));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String replaceFirst(String haystack, String needle, String replacement) {
        int idx = haystack.indexOf(needle);
        if (idx == -1) {
            return haystack;
        }
        return haystack.substring(0, idx) + replacement + haystack.substring(idx + needle.length());
    }

    private static List<String> splitLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(content.split("\\R", -1));
    }

    private static boolean readBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
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
