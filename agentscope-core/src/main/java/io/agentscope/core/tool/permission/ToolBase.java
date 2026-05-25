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
package io.agentscope.core.tool.permission;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContext;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * Abstract base class for tools that participate in permission evaluation.
 *
 * <p>Concrete subclasses describe themselves via the constructor (name, description, input schema,
 * safety flags) and must implement {@link #checkPermissions(Map, PermissionContext)}. The default
 * {@link #matchRule(String, Map)} and {@link #generateSuggestions(Map)} implementations follow the
 * tool-name-level convention; tools with finer-grained semantics (file globs, command prefixes)
 * override them.
 *
 * <p>{@code ToolBase} also implements {@link AgentTool} so instances plug directly into the
 * existing {@code Toolkit} dispatch. Tools that produce results outside the framework (external
 * tools) should set {@code isExternalTool=true} and rely on the agent loop to surface the call.
 */
public abstract class ToolBase implements AgentTool {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final boolean isConcurrencySafe;
    private final boolean isReadOnly;
    private final boolean isExternalTool;
    private final boolean isStateInjected;
    private final boolean isMcp;
    private final String mcpName;

    /** Sensitive files; subclasses may replace this list to widen or narrow protection. */
    protected List<String> dangerousFiles = ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES;

    /** Sensitive directory names; segment-level matching applies to absolute paths. */
    protected List<String> dangerousDirectories =
            ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES;

    /**
     * @param name agent-visible tool name (never {@code null})
     * @param description agent-visible description (never {@code null})
     * @param inputSchema JSON Schema for the tool's arguments (never {@code null})
     * @param isReadOnly tool performs no observable mutation
     * @param isConcurrencySafe multiple invocations may run in parallel without coordination
     * @param isMcp tool wraps an MCP server call
     * @param mcpName MCP server name (required when {@code isMcp == true}, otherwise nullable)
     * @param isExternalTool framework forwards the call out instead of executing it locally
     * @param isStateInjected tool expects the agent state to be injected at call time
     */
    protected ToolBase(
            String name,
            String description,
            Map<String, Object> inputSchema,
            boolean isReadOnly,
            boolean isConcurrencySafe,
            boolean isMcp,
            String mcpName,
            boolean isExternalTool,
            boolean isStateInjected) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema must not be null");
        this.isReadOnly = isReadOnly;
        this.isConcurrencySafe = isConcurrencySafe;
        this.isMcp = isMcp;
        this.mcpName = mcpName;
        this.isExternalTool = isExternalTool;
        this.isStateInjected = isStateInjected;
        if (isMcp && (mcpName == null || mcpName.isBlank())) {
            throw new IllegalArgumentException("mcpName is required when isMcp is true");
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
        return isConcurrencySafe;
    }

    public final boolean isReadOnly() {
        return isReadOnly;
    }

    public final boolean isExternalTool() {
        return isExternalTool;
    }

    public final boolean isStateInjected() {
        return isStateInjected;
    }

    public final boolean isMcp() {
        return isMcp;
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
        if (isExternalTool) {
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
     * @param toolInput the parsed tool arguments
     * @param context current permission evaluation context
     * @return a Mono emitting the decision; never {@code null}
     */
    public abstract Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContext context);

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
     *     (case-insensitive), or when any segment matches one of {@link #dangerousDirectories}
     */
    protected boolean isDangerousPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        Path absolute = Path.of(expandTilde(filePath)).toAbsolutePath().normalize();
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
}
