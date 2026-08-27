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
import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * Abstract base class for tools that participate in permission evaluation and ReAct execution.
 *
 * <p>Concrete subclasses describe themselves via the builder (name, description, input schema,
 * safety flags) and may override {@link #checkPermissions(Map, PermissionContextState)} to plug their
 * own self-check into the permission engine. The default implementation returns
 * {@link PermissionDecision#passthrough(String)}, which lets the engine fall back to its rule
 * tables and mode-based defaults; subclasses that declare file-path parameters via {@link
 * Builder#filePathParams(Set)} get a path-aware default check instead (dangerous-path ASK plus
 * the {@code ACCEPT_EDITS} working-directory auto-allow).
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
    private final Set<String> filePathParams;

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
                builder.stateInjected,
                builder.filePathParams);
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
     * Delegates to {@link #ToolBase(String, String, Map, boolean, boolean, boolean, String,
     * boolean, boolean, Set)} with no declared file paths.
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
        this(
                name,
                description,
                inputSchema,
                readOnly,
                concurrencySafe,
                mcp,
                mcpName,
                externalTool,
                stateInjected,
                Set.of());
    }

    /**
     * Positional constructor variant that also declares the parameters carrying file paths; see
     * {@link #checkPermissions(Map, PermissionContextState)} for how the declaration is consumed.
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
            boolean stateInjected,
            Set<String> filePathParams) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema must not be null");
        this.readOnly = readOnly;
        this.concurrencySafe = concurrencySafe;
        this.mcp = mcp;
        this.mcpName = mcpName;
        this.externalTool = externalTool;
        this.stateInjected = stateInjected;
        this.filePathParams =
                Collections.unmodifiableSet(
                        new LinkedHashSet<>(Objects.requireNonNull(filePathParams)));
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
     * Names of the parameters this tool declared as file paths (insertion order preserved,
     * duplicates collapsed). Empty when the tool declares no path semantics.
     *
     * @return immutable view of the declared file-path parameter names
     */
    public final Set<String> getFilePathParams() {
        return filePathParams;
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
     * <p>When the tool declares file-path parameters (see {@link Builder#filePathParams(Set)}),
     * the default implementation evaluates the declared paths on the effective input (including
     * preset parameters):
     *
     * <ol>
     *   <li><b>Extraction</b> — every declared parameter must be present; a missing or blank
     *       value yields PASSTHROUGH (never a subset auto-allow), a non-string or mixed-type
     *       value yields a bypass-immune Safety-ASK.
     *   <li><b>Dangerous path check</b> — if <em>any</em> declared path is dangerous, or cannot
     *       be resolved with certainty, an ASK decision with a {@code "Safety check:"} reason is
     *       returned. The engine treats such decisions as bypass-immune.
     *   <li><b>ACCEPT_EDITS working-directory check</b> — if the mode is {@link
     *       PermissionMode#ACCEPT_EDITS} and <em>all</em> declared paths provably resolve inside
     *       a configured working directory, an ALLOW decision is returned.
     *   <li>Anything else returns PASSTHROUGH so the engine's rule tables and mode defaults
     *       decide.
     * </ol>
     *
     * <p>Unexpected runtime failures are converted into a bypass-immune Safety-ASK (fail
     * closed).
     *
     * <p>Tools without declared file paths return plain {@link
     * PermissionDecision#passthrough(String)}. Tools with fine-grained semantics (Bash, MCP
     * tools) should override this to surface their own ALLOW / ASK / DENY policy.
     *
     * @param toolInput the parsed tool arguments (the effective input, including preset
     *     parameters, as assembled by the caller)
     * @param context current permission evaluation context
     * @return a Mono emitting the decision; never {@code null}
     */
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        if (filePathParams.isEmpty()) {
            return Mono.just(PermissionDecision.passthrough(name));
        }

        try {
            PathExtraction extraction = extractFilePaths(toolInput);
            if (extraction.refused()) {
                if (extraction.kind() == PathExtraction.RefusalKind.UNVERIFIABLE) {
                    return Mono.just(
                            PermissionDecision.builder()
                                    .behavior(PermissionBehavior.ASK)
                                    .message(
                                            "Permission required: unverifiable path parameter: "
                                                    + extraction.reason())
                                    .decisionReason("Safety check: unverifiable path parameter")
                                    .build());
                }
                return Mono.just(PermissionDecision.passthrough(name + " " + extraction.reason()));
            }
            List<String> filePaths = extraction.paths();

            // 1. Any dangerous path (or unresolvable path) -> Safety-ASK (bypass-immune)
            for (String filePath : filePaths) {
                if (isDangerousPath(filePath)) {
                    return Mono.just(
                            PermissionDecision.builder()
                                    .behavior(PermissionBehavior.ASK)
                                    .message(
                                            "Permission required: operation on sensitive path: "
                                                    + filePath)
                                    .decisionReason("Safety check: dangerous file or directory")
                                    .build());
                }
            }

            // 2. ACCEPT_EDITS + every path provably inside a working directory -> ALLOW
            if (context != null
                    && context.getMode() == PermissionMode.ACCEPT_EDITS
                    && filePaths.stream().allMatch(p -> isPathInWorkingScope(p, context))) {
                return Mono.just(
                        PermissionDecision.allow(
                                "Permission granted (accept edits mode, in working directory)"));
            }

            // 3. Defer to the engine's rule tables and mode defaults
            return Mono.just(PermissionDecision.passthrough(name + " path not in working scope"));
        } catch (RuntimeException e) {
            // Fail closed: surface the failure as a bypass-immune Safety-ASK.
            return Mono.just(
                    PermissionDecision.builder()
                            .behavior(PermissionBehavior.ASK)
                            .message(
                                    "Permission required: "
                                            + name
                                            + " path check failed: "
                                            + e.getClass().getSimpleName())
                            .decisionReason("Safety check: path check failed")
                            .build());
        }
    }

    /**
     * Extracts the declared file-path argument values from the tool input, refusing with a
     * reason when any declared parameter is missing, blank, or unverifiable.
     */
    private PathExtraction extractFilePaths(Map<String, Object> toolInput) {
        if (toolInput == null) {
            return PathExtraction.refuse(
                    PathExtraction.RefusalKind.MISSING, "missing filePath param");
        }
        List<String> paths = new ArrayList<>(filePathParams.size());
        for (String param : filePathParams) {
            Object val = toolInput.get(param);
            if (val == null) {
                return PathExtraction.refuse(
                        PathExtraction.RefusalKind.MISSING, "missing filePath param: " + param);
            }
            if (val instanceof String s) {
                if (s.isBlank()) {
                    return PathExtraction.refuse(
                            PathExtraction.RefusalKind.MISSING, "blank filePath param: " + param);
                }
                paths.add(s);
            } else if (val instanceof List<?> list) {
                if (list.isEmpty()) {
                    return PathExtraction.refuse(
                            PathExtraction.RefusalKind.UNVERIFIABLE,
                            "empty list filePath param: " + param);
                }
                for (Object member : list) {
                    if (!(member instanceof String s) || s.isBlank()) {
                        return PathExtraction.refuse(
                                PathExtraction.RefusalKind.UNVERIFIABLE,
                                "non-string member of filePath param: " + param);
                    }
                    paths.add(s);
                }
            } else {
                return PathExtraction.refuse(
                        PathExtraction.RefusalKind.UNVERIFIABLE,
                        "unverifiable filePath param: " + param);
            }
        }
        return PathExtraction.ok(paths);
    }

    /** Outcome of {@link #extractFilePaths(Map)}: the extracted paths or a refusal reason. */
    private record PathExtraction(List<String> paths, RefusalKind kind, String reason) {

        enum RefusalKind {
            NONE,
            MISSING,
            UNVERIFIABLE
        }

        static PathExtraction ok(List<String> paths) {
            return new PathExtraction(List.copyOf(paths), RefusalKind.NONE, null);
        }

        static PathExtraction refuse(RefusalKind kind, String reason) {
            return new PathExtraction(List.of(), kind, reason);
        }

        boolean refused() {
            return kind != RefusalKind.NONE;
        }
    }

    /**
     * Resolves {@code rawPath} to the absolute path the tool's execution would operate on,
     * <em>without side effects</em>. The default implementation expands {@code ~} and accepts
     * absolute paths; relative paths return {@link Optional#empty()} because the execution base
     * (tool-internal root, JVM CWD, harness filesystem root) is not visible at this layer and
     * must not be guessed.
     *
     * <p>Tool classes registered via {@code Toolkit#registerTool(Object)} may implement {@link
     * ToolFilePathResolver} to override this with their exact execution semantics; the reflective
     * wrapper delegates automatically.
     *
     * @return the absolute execution path when it can be proven with certainty; empty when it
     *     cannot (the caller then fails closed — no auto-allow)
     */
    protected Optional<Path> resolveExecutionPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return Optional.empty();
        }
        try {
            Path p = Path.of(expandTilde(rawPath));
            if (!p.isAbsolute()) {
                return Optional.empty();
            }
            return Optional.of(p.normalize());
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether {@code filePath} provably resolves inside one of the context's configured working
     * directories.
     *
     * <p>Only directories explicitly registered on the {@link PermissionContextState} authorise
     * the {@link PermissionMode#ACCEPT_EDITS} auto-allow; the process CWD is never implicit. The
     * execution landing point (see {@link #resolveExecutionPath(String)}) and each working
     * directory are symlink-resolved via {@link #resolveEffective(Path, Set)} on both sides, so a
     * link cannot smuggle a write out of the scope and a path that cannot be resolved with
     * certainty returns {@code false} (fail closed).
     *
     * @param filePath the declared file-path argument value
     * @param context current permission evaluation context
     * @return {@code true} only when the resolved real path provably stays inside a working
     *     directory
     */
    protected boolean isPathInWorkingScope(String filePath, PermissionContextState context) {
        if (filePath == null || filePath.isBlank() || context == null) {
            return false;
        }
        Map<String, AdditionalWorkingDirectory> workingDirs = context.getWorkingDirectories();
        if (workingDirs == null || workingDirs.isEmpty()) {
            return false;
        }

        Path target = resolveEffectivePath(filePath);
        if (target == null) {
            return false;
        }
        for (AdditionalWorkingDirectory wd : workingDirs.values()) {
            Path dir =
                    resolveEffective(
                            Path.of(expandTilde(wd.path())).toAbsolutePath().normalize(),
                            new HashSet<>());
            if (dir != null && target.startsWith(dir)) {
                return true;
            }
        }
        return false;
    }

    /** Resolves the execution landing point and then its real path; {@code null} when unprovable. */
    private Path resolveEffectivePath(String rawPath) {
        Optional<Path> execPath = resolveExecutionPath(rawPath);
        if (execPath.isEmpty()) {
            return null;
        }
        return resolveEffective(execPath.get().toAbsolutePath().normalize(), new HashSet<>());
    }

    /** Upper bound for path-component and symlink-chain resolution; longer chains fail closed. */
    private static final int MAX_PATH_RESOLUTION_STEPS = 256;

    /**
     * Resolves an absolute path to the location the operating system would actually use,
     * following <em>every</em> symlink component (including dangling ones pointing at
     * non-existent targets) <em>without</em> requiring the final file to exist. Mirrors the
     * kernel's path resolution: a dangling symlink pointing at an outside target is expanded to
     * that target instead of being treated as a plain filename.
     *
     * <p>Returns {@code null} when the path cannot be resolved with certainty — a symlink cycle,
     * an unreadable link, or an unreasonably deep component/chain walk — and callers must fail
     * closed on {@code null}.
     *
     * @param absolute an absolute, normalised path (relative paths are rejected)
     * @param linkChain the set of symlinks already expanded on the current resolution branch; a
     *     repeated link signals a cycle
     */
    private static Path resolveEffective(Path absolute, Set<Path> linkChain) {
        if (absolute == null || absolute.getRoot() == null) {
            return null;
        }
        if (linkChain.size() >= MAX_PATH_RESOLUTION_STEPS) {
            return null;
        }
        Path current = absolute.getRoot();
        int steps = 0;
        for (Path segment : absolute) {
            String name = segment.toString();
            if (".".equals(name)) {
                continue;
            }
            if ("..".equals(name)) {
                Path parent = current.getParent();
                if (parent != null) {
                    current = parent;
                }
                continue;
            }
            if (++steps > MAX_PATH_RESOLUTION_STEPS) {
                return null;
            }
            current = current.resolve(name);
            if (!Files.isSymbolicLink(current)) {
                continue;
            }
            Set<Path> extended = new HashSet<>(linkChain);
            if (!extended.add(current)) {
                return null;
            }
            Path target;
            try {
                target = Files.readSymbolicLink(current);
            } catch (IOException e) {
                return null;
            }
            Path resolvedTarget =
                    target.isAbsolute() ? target : current.getParent().resolve(target);
            Path head = resolveEffective(resolvedTarget.toAbsolutePath().normalize(), extended);
            if (head == null) {
                return null;
            }
            current = head;
        }
        return current.normalize();
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
     *     Both the JVM-CWD-anchored lexical form and the tool-resolved execution landing point
     *     (see {@link #resolveExecutionPath(String)}) are evaluated; each candidate is also
     *     symlink-resolved via {@link #resolveEffective(Path, Set)} and re-checked. A candidate
     *     that cannot be resolved with certainty (cycle, unreadable link) is treated as
     *     dangerous (fail closed).
     */
    protected boolean isDangerousPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        try {
            // Anchor 1: JVM-CWD-anchored lexical absolute; the only candidate when the tool's
            // resolver is opaque or absent.
            Path absolute = Path.of(expandTilde(filePath)).toAbsolutePath().normalize();
            if (isDangerousAbsolute(absolute) || isDangerousViaSymlinks(absolute)) {
                return true;
            }
            // Anchor 2: the tool-provided execution landing point, which may differ from the
            // JVM CWD for relative arguments.
            Optional<Path> execPath = resolveExecutionPath(filePath);
            if (execPath.isPresent()) {
                Path execAbsolute = execPath.get().toAbsolutePath().normalize();
                if (!execAbsolute.equals(absolute)
                        && (isDangerousAbsolute(execAbsolute)
                                || isDangerousViaSymlinks(execAbsolute))) {
                    return true;
                }
            }
            return false;
        } catch (InvalidPathException e) {
            return true;
        }
    }

    /**
     * Whether the symlink-resolved form of {@code absolute} is dangerous. A path that cannot be
     * resolved with certainty is treated as dangerous (fail closed).
     */
    private boolean isDangerousViaSymlinks(Path absolute) {
        Path resolved = resolveEffective(absolute, new HashSet<>());
        if (resolved == null) {
            return true;
        }
        return !resolved.equals(absolute) && isDangerousAbsolute(resolved);
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
        private Set<String> filePathParams = Set.of();

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

        /**
         * Declares the parameter names that carry file paths, enabling the default {@code
         * checkPermissions} dangerous-path and ACCEPT_EDITS working-directory checks. Duplicate
         * names are collapsed preserving declaration order.
         *
         * @param filePathParams parameter names whose string values are file paths
         */
        public Builder filePathParams(Set<String> filePathParams) {
            this.filePathParams = filePathParams == null ? Set.of() : filePathParams;
            return this;
        }
    }
}
