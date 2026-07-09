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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * Abstract base class for tools that participate in permission evaluation and ReAct execution.
 *
 * <p>Concrete subclasses describe themselves via the builder (name, description, input schema,
 * safety flags) and may override {@link #checkPermissions(Map, PermissionContextState)} to plug their
 * own self-check into the permission engine. The default implementation returns
 * {@link PermissionDecision#passthrough(String)}, which lets the engine fall back to its rule
 * tables and mode-based defaults.
 *
 * <p>{@code ToolBase} implements {@link AgentTool} so instances plug directly into the existing
 * {@code Toolkit} dispatch. Tools that produce results outside the framework (external tools)
 * should mark {@code externalTool=true}; the {@code ToolExecutor} surfaces the call through a
 * {@link ToolSuspendException} instead of invoking it locally.
 *
 * <p>Builder usage:
 *
 * <pre>{@code
 * super(ToolBase.builder()
 *         .name("read")
 *         .description("Read a file")
 *         .inputSchema(schema)
 *         .readOnly(true)
 *         .concurrencySafe(true)
 *         .build());
 * }</pre>
 */
public abstract class ToolBase implements AgentTool {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final boolean concurrencySafe;
    private final boolean readOnly;
    private final boolean externalTool;
    private final boolean stateInjected;
    private final boolean mcp;
    private final String mcpName;

    /** Sensitive files; subclasses may replace this list to widen or narrow protection. */
    protected List<String> dangerousFiles = ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES;

    /** Sensitive directory names; segment-level matching applies to absolute paths. */
    protected List<String> dangerousDirectories =
            ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES;

    /** Builder-based constructor (preferred). */
    protected ToolBase(Builder builder) {
        this(
                builder.name,
                builder.description,
                builder.inputSchema,
                builder.readOnly,
                builder.concurrencySafe,
                builder.mcp,
                builder.mcpName,
                builder.externalTool,
                builder.stateInjected);
        if (builder.dangerousFiles != null) {
            this.dangerousFiles = List.copyOf(builder.dangerousFiles);
        }
        if (builder.dangerousDirectories != null) {
            this.dangerousDirectories = List.copyOf(builder.dangerousDirectories);
        }
    }

    /**
     * Positional constructor used by built-in tools and any subclass that prefers explicit
     * arguments over {@link #builder()}. New code is encouraged to use the builder for clarity.
     */
    protected ToolBase(
            String name,
            String description,
            Map<String, Object> inputSchema,
            boolean readOnly,
            boolean concurrencySafe,
            boolean mcp,
            String mcpName,
            boolean externalTool,
            boolean stateInjected) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema must not be null");
        this.readOnly = readOnly;
        this.concurrencySafe = concurrencySafe;
        this.mcp = mcp;
        this.mcpName = mcpName;
        this.externalTool = externalTool;
        this.stateInjected = stateInjected;
        if (mcp && (mcpName == null || mcpName.isBlank())) {
            throw new IllegalArgumentException("mcpName is required when mcp is true");
        }
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getDescription() {
        return description;
    }

    @Override
    public final Map<String, Object> getParameters() {
        return inputSchema;
    }

    public final boolean isConcurrencySafe() {
        return concurrencySafe;
    }

    @Override
    public final boolean isReadOnly() {
        return readOnly;
    }

    public final boolean isExternalTool() {
        return externalTool;
    }

    public final boolean isStateInjected() {
        return stateInjected;
    }

    public final boolean isMcp() {
        return mcp;
    }

    public final String getMcpName() {
        return mcpName;
    }

    /**
     * Default tool invocation. External tools must not be invoked locally; non-external subclasses
     * must override this method.
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        if (externalTool) {
            return Mono.error(
                    new IllegalStateException(
                            getClass().getSimpleName()
                                    + " is an external tool and must not be invoked locally"));
        }
        return Mono.error(
                new UnsupportedOperationException(
                        getClass().getSimpleName() + " does not implement callAsync"));
    }

    /**
     * Tool self-check invoked by the permission engine when {@code allowRules}/{@code askRules}
     * neither allow nor reject the call outright.
     *
     * <p>The default implementation returns {@link PermissionDecision#passthrough(String)},
     * delegating the decision to the engine's rule tables and mode defaults. Tools with
     * fine-grained semantics (Bash, file writers, MCP tools) should override this to surface their
     * own ALLOW / ASK / DENY policy.
     *
     * @param toolInput the parsed tool arguments
     * @param context current permission evaluation context
     * @return a Mono emitting the decision; never {@code null}
     */
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        return Mono.just(PermissionDecision.passthrough(name));
    }

    /**
     * Default rule matcher: a {@code null} {@code ruleContent} matches every invocation; any
     * non-null pattern is rejected so subclasses can layer their own semantics on top.
     */
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        return ruleContent == null;
    }

    /**
     * Default suggestion: a single tool-name-level {@link PermissionBehavior#ALLOW} rule sourced
     * from {@code "suggested"}. Subclasses with finer-grained context (file paths, command
     * prefixes) override this to produce more specific patterns.
     */
    public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
        return List.of(new PermissionRule(name, null, PermissionBehavior.ALLOW, "suggested"));
    }

    /**
     * @return {@code true} when {@code filePath}'s filename matches one of {@link #dangerousFiles}
     *     (case-insensitive), or when any segment matches one of {@link #dangerousDirectories}.
     *     Also resolves symlinks and re-checks the real path to prevent bypass via symlink
     *     pointing at a dangerous target.
     */
    protected boolean isDangerousPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        Path absolute = Path.of(expandTilde(filePath)).toAbsolutePath().normalize();
        if (isDangerousAbsolute(absolute)) {
            return true;
        }
        try {
            Path resolved = Files.exists(absolute) ? absolute.toRealPath() : absolute;
            if (!resolved.equals(absolute) && isDangerousAbsolute(resolved)) {
                return true;
            }
        } catch (IOException ignored) {
            // If we can't resolve, stick with the logical-path result
        }
        return false;
    }

    private boolean isDangerousAbsolute(Path absolute) {
        Path fileNamePath = absolute.getFileName();
        String fileNameLower =
                fileNamePath == null ? "" : fileNamePath.toString().toLowerCase(Locale.ROOT);
        for (String dangerousFile : dangerousFiles) {
            if (fileNameLower.equals(dangerousFile.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        Set<String> segmentsLower = new HashSet<>();
        absolute.forEach(segment -> segmentsLower.add(segment.toString().toLowerCase(Locale.ROOT)));
        for (String dangerousDir : dangerousDirectories) {
            if (segmentsLower.contains(dangerousDir.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String expandTilde(String path) {
        if (path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link ToolBase} subclasses. */
    public static final class Builder {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;
        private boolean readOnly = false;
        private boolean concurrencySafe = true;
        private boolean externalTool = false;
        private boolean stateInjected = false;
        private boolean mcp = false;
        private String mcpName;
        private List<String> dangerousFiles;
        private List<String> dangerousDirectories;

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder inputSchema(Map<String, Object> inputSchema) {
            this.inputSchema = inputSchema;
            return this;
        }

        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        public Builder concurrencySafe(boolean concurrencySafe) {
            this.concurrencySafe = concurrencySafe;
            return this;
        }

        public Builder externalTool(boolean externalTool) {
            this.externalTool = externalTool;
            return this;
        }

        public Builder stateInjected(boolean stateInjected) {
            this.stateInjected = stateInjected;
            return this;
        }

        /** Marks the tool as an MCP tool and records the MCP server name. */
        public Builder mcp(String mcpName) {
            this.mcp = true;
            this.mcpName = mcpName;
            return this;
        }

        public Builder dangerousFiles(List<String> dangerousFiles) {
            this.dangerousFiles = dangerousFiles;
            return this;
        }

        public Builder dangerousDirectories(List<String> dangerousDirectories) {
            this.dangerousDirectories = dangerousDirectories;
            return this;
        }
    }
}
