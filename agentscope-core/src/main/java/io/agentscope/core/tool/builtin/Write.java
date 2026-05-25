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
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.permission.ToolBase;
import io.agentscope.core.tool.permission.ToolDangerousPathConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
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
 * Creates or replaces a UTF-8 text file at {@code file_path}.
 *
 * <p>For files that already exist on disk, the agent must have read the file via {@link Read}
 * first; the shared {@link ToolContext} cache hit gates the write. New files (path does not yet
 * exist) bypass this requirement.
 *
 * <p>{@link #checkPermissions} performs three steps:
 *
 * <ol>
 *   <li>If the path matches the configured dangerous file/directory lists, return an
 *       {@code ASK} decision with a {@code "Safety check:"} reason so the engine treats it as
 *       bypass-immune.
 *   <li>Under {@link PermissionMode#ACCEPT_EDITS}, allow writes that resolve inside any
 *       configured working directory.
 *   <li>Otherwise return {@code PASSTHROUGH} so the engine consults its rule tables.
 * </ol>
 */
public final class Write extends ToolBase {

    private static final String NAME = "Write";

    private static final String DESCRIPTION =
            "Create or overwrite a UTF-8 text file. The file's parent directory is created if "
                    + "missing. Existing files must first be read via the Read tool so the cache "
                    + "can detect external modifications.";

    private final ToolContext context;

    public Write(ToolContext context) {
        this(context, List.of(), List.of());
    }

    /**
     * @param context backing cache used to enforce the Read-before-Write invariant
     * @param additionalDangerousFiles extra sensitive filenames appended to the defaults
     * @param additionalDangerousDirectories extra sensitive directory names appended to the
     *     defaults
     */
    public Write(
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
        filePath.put("description", "Absolute path to the file to create or overwrite.");

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "string");
        content.put("description", "UTF-8 text content to write to the file.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", filePath);
        properties.put("content", content);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("file_path", "content"));
        return schema;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContext ctx) {
        Object filePathRaw = toolInput == null ? null : toolInput.get("file_path");
        if (!(filePathRaw instanceof String filePath) || filePath.isBlank()) {
            return Mono.just(PermissionDecision.passthrough("Write missing file_path"));
        }
        if (isDangerousPath(filePath)) {
            return Mono.just(
                    PermissionDecision.builder()
                            .behavior(PermissionBehavior.ASK)
                            .message(
                                    "Permission required: Write operates on sensitive path: "
                                            + filePath)
                            .decisionReason("Safety check: dangerous file or directory")
                            .build());
        }
        if (ctx != null
                && ctx.getMode() == PermissionMode.ACCEPT_EDITS
                && isUnderAnyWorkingDirectory(filePath, ctx)) {
            return Mono.just(
                    PermissionDecision.allow("Permission granted for Write (accept edits mode)"));
        }
        return Mono.just(PermissionDecision.passthrough("Write " + filePath));
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
                suggestions.add(
                        new PermissionRule(
                                getName(),
                                parent.toString() + "/*",
                                PermissionBehavior.ALLOW,
                                "suggested"));
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
        Object contentRaw = input.get("content");
        if (!(contentRaw instanceof String contentString)) {
            return Mono.just(errorResult(param, "content is required"));
        }

        Path path = Path.of(filePath);
        Mono<Void> cacheCheck =
                Mono.fromCallable(() -> Files.exists(path))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(
                                exists -> {
                                    if (!exists) {
                                        return Mono.empty();
                                    }
                                    return context.getCache(filePath)
                                            .flatMap(
                                                    opt ->
                                                            opt.isPresent()
                                                                    ? Mono.empty()
                                                                    : Mono.error(
                                                                            new ReadCacheMissException(
                                                                                    "File "
                                                                                            + filePath
                                                                                            + " must"
                                                                                            + " be read"
                                                                                            + " via the"
                                                                                            + " Read"
                                                                                            + " tool"
                                                                                            + " before"
                                                                                            + " writing.")));
                                });

        return cacheCheck
                .then(
                        Mono.fromCallable(
                                        () -> {
                                            Path parent = path.toAbsolutePath().getParent();
                                            if (parent != null) {
                                                Files.createDirectories(parent);
                                            }
                                            Files.writeString(
                                                    path, contentString, StandardCharsets.UTF_8);
                                            return splitLines(contentString);
                                        })
                                .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(
                        lines ->
                                context.cacheFile(filePath, lines)
                                        .thenReturn(
                                                successResult(
                                                        param,
                                                        "File written: "
                                                                + filePath
                                                                + " ("
                                                                + lines.size()
                                                                + " lines, "
                                                                + contentString.length()
                                                                + " characters)")))
                .onErrorResume(
                        ReadCacheMissException.class,
                        e -> Mono.just(errorResult(param, e.getMessage())))
                .onErrorResume(
                        IOException.class,
                        e ->
                                Mono.just(
                                        errorResult(
                                                param,
                                                "Failed to write "
                                                        + filePath
                                                        + ": "
                                                        + e.getMessage())));
    }

    private static List<String> splitLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(content.split("\\R", -1));
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

    private static final class ReadCacheMissException extends RuntimeException {
        ReadCacheMissException(String message) {
            super(message);
        }
    }
}
