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
package io.agentscope.harness.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@link SubagentDeclaration} instances from Markdown files with YAML front matter placed
 * in the {@code subagents/} directory of a workspace.
 *
 * <p><strong>File naming</strong>: the filename (without the {@code .md} extension) becomes the
 * subagent's {@code name} / agent-id. The front matter must not contain a {@code name} field.
 *
 * <p><strong>Scan strategy</strong>: only <em>direct</em> children of the given directory are
 * scanned (non-recursive) to prevent accidentally loading files that live inside a definition
 * workspace that happens to be stored under the same parent.
 *
 * <p>File format:
 *
 * <pre>
 * ---
 * description: Reviews code for security and performance issues.
 * workspace:
 *   mode: isolated          # isolated | shared  (default: isolated)
 *   path: ./defs/reviewer   # optional; relative to mainWorkspace, or absolute
 * model: qwen3-max          # optional model override
 * maxIters: 12              # optional (default 10)
 * tools: [read_file, grep_files, edit_file]   # optional allowlist
 * ---
 *
 * # Inline body (only when workspace.path is absent)
 * You are a code reviewer...
 * </pre>
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code description} is required.
 *   <li>When {@code workspace.path} is present, the Markdown body must be blank; the subagent's
 *       system prompt is read from {@code &lt;workspace.path&gt;/AGENTS.md} at runtime.
 *   <li>When {@code workspace.path} is absent, the Markdown body (if any) becomes the inline
 *       {@link SubagentDeclaration#getInlineAgentsBody()}.
 * </ul>
 */
public final class AgentSpecLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentSpecLoader.class);
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private AgentSpecLoader() {}

    /**
     * Non-recursively scans {@code subagentsDir} for {@code .md} files and parses each into a
     * {@link SubagentDeclaration}.
     *
     * @param subagentsDir the {@code subagents/} directory to scan
     * @param mainWorkspace the parent workspace used to resolve relative {@code workspace.path}
     *     values; may be {@code null} (relative paths will remain relative)
     * @return list of parsed declarations; never {@code null}
     */
    public static List<SubagentDeclaration> loadFromDirectory(
            Path subagentsDir, Path mainWorkspace) {
        if (subagentsDir == null || !Files.isDirectory(subagentsDir)) {
            return Collections.emptyList();
        }
        List<SubagentDeclaration> decls = new ArrayList<>();
        try (Stream<Path> paths = Files.list(subagentsDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(
                            path -> {
                                try {
                                    SubagentDeclaration decl = loadFromFile(path, mainWorkspace);
                                    if (decl != null) {
                                        decls.add(decl);
                                        log.debug(
                                                "Loaded subagent declaration '{}' from {}",
                                                decl.getName(),
                                                path);
                                    }
                                } catch (Exception e) {
                                    log.warn(
                                            "Failed to load subagent declaration from {}: {}",
                                            path,
                                            e.getMessage());
                                }
                            });
        } catch (IOException e) {
            log.warn("Failed to list subagents directory {}: {}", subagentsDir, e.getMessage());
        }
        return decls;
    }

    /**
     * Loads subagent declarations via the {@link AbstractFilesystem}, respecting namespace
     * isolation. Scans {@code subagents/} for {@code *.md} files using filesystem glob.
     *
     * @param filesystem the filesystem layer (applies namespace transparently)
     * @param mainWorkspace the parent workspace for resolving relative workspace paths; may be
     *     {@code null}
     * @return list of parsed declarations; never {@code null}
     */
    public static List<SubagentDeclaration> loadFromFilesystem(
            AbstractFilesystem filesystem, Path mainWorkspace) {
        if (filesystem == null) {
            return Collections.emptyList();
        }
        RuntimeContext ctx = RuntimeContext.empty();
        GlobResult glob = filesystem.glob(ctx, "*.md", "subagents");
        if (!glob.isSuccess() || glob.matches() == null || glob.matches().isEmpty()) {
            return Collections.emptyList();
        }

        List<SubagentDeclaration> decls = new ArrayList<>();
        List<FileInfo> sorted = new ArrayList<>(glob.matches());
        sorted.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.path(), b.path()));

        for (FileInfo fi : sorted) {
            String path = fi.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                ReadResult rr = filesystem.read(ctx, path, 0, 0);
                if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                    continue;
                }
                String filename =
                        path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                String name = stripMdExtension(filename);
                SubagentDeclaration decl = parse(rr.fileData().content(), name, mainWorkspace);
                if (decl != null) {
                    decls.add(decl);
                    log.debug("Loaded subagent declaration '{}' from filesystem: {}", name, path);
                }
            } catch (Exception e) {
                log.warn("Failed to load subagent declaration from {}: {}", path, e.getMessage());
            }
        }
        return decls;
    }

    /**
     * Parses a single Markdown declaration file.
     *
     * @param filePath the {@code .md} file to parse
     * @param mainWorkspace workspace root used to resolve relative {@code workspace.path} values;
     *     may be {@code null}
     * @return parsed declaration, or {@code null} if the file is malformed / missing required
     *     fields
     */
    public static SubagentDeclaration loadFromFile(Path filePath, Path mainWorkspace)
            throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        String nameFromFile = stripMdExtension(filePath.getFileName().toString());
        return parse(content, nameFromFile, mainWorkspace);
    }

    /**
     * Parses markdown content with YAML front matter into a {@link SubagentDeclaration}.
     *
     * @param markdown the full file content
     * @param name the subagent name (derived from the filename, without {@code .md})
     * @param mainWorkspace workspace root used to resolve relative {@code workspace.path} values;
     *     may be {@code null}
     * @return parsed declaration, or {@code null} if the content is malformed
     */
    @SuppressWarnings("unchecked")
    public static SubagentDeclaration parse(String markdown, String name, Path mainWorkspace) {
        if (markdown == null || markdown.isBlank() || !markdown.startsWith("---")) {
            return null;
        }
        int endIdx = markdown.indexOf("---", 3);
        if (endIdx == -1) {
            log.warn("Agent declaration front matter not closed with --- in '{}'", name);
            return null;
        }

        String frontMatterStr = markdown.substring(3, endIdx).trim();
        String body = markdown.substring(endIdx + 3).trim();

        Map<String, Object> fm;
        try {
            fm = YAML_MAPPER.readValue(frontMatterStr, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse YAML front matter for '{}': {}", name, e.getMessage());
            return null;
        }
        if (fm == null || fm.isEmpty()) {
            return null;
        }

        String description = asString(fm.get("description"));
        if (description == null || description.isBlank()) {
            log.warn(
                    "Subagent declaration '{}' missing required 'description' in front matter",
                    name);
            return null;
        }

        // ---- workspace section ----
        WorkspaceMode mode = WorkspaceMode.ISOLATED;
        Path workspacePath = null;

        Object workspaceObj = fm.get("workspace");
        if (workspaceObj instanceof Map<?, ?> wsMap) {
            String modeStr = asString(wsMap.get("mode"));
            if (modeStr != null) {
                if ("shared".equalsIgnoreCase(modeStr)) {
                    mode = WorkspaceMode.SHARED;
                } else if ("isolated".equalsIgnoreCase(modeStr)) {
                    mode = WorkspaceMode.ISOLATED;
                } else {
                    log.warn(
                            "Unknown workspace.mode '{}' in declaration '{}', defaulting to"
                                    + " isolated",
                            modeStr,
                            name);
                }
            }
            String pathStr = asString(wsMap.get("path"));
            if (pathStr != null && !pathStr.isBlank()) {
                workspacePath = resolvePath(pathStr, mainWorkspace);
            }
        }

        // ---- body / inline sysPrompt validation ----
        if (workspacePath != null && !body.isBlank()) {
            log.warn(
                    "Subagent declaration '{}' has both workspace.path and a non-empty body;"
                            + " body will be ignored — put the system prompt in"
                            + " {}/AGENTS.md instead",
                    name,
                    workspacePath);
            body = "";
        }

        // ---- optional fields ----
        String model = asString(fm.get("model"));

        // steps (preferred) > maxIters (deprecated alias). Both default to 10 via the builder.
        int steps = 10;
        Object stepsObj = fm.get("steps");
        if (stepsObj instanceof Number sn) {
            steps = sn.intValue();
        } else {
            Object maxItersObj = fm.get("maxIters");
            if (maxItersObj instanceof Number n) {
                steps = n.intValue();
            }
        }

        Double temperature = asDouble(fm.get("temperature"));
        Double topP = asDouble(fm.get("top_p"));
        if (topP == null) {
            topP = asDouble(fm.get("topP"));
        }
        String variant = asString(fm.get("variant"));

        SubagentDeclaration.Mode declMode = parseDeclarationMode(asString(fm.get("mode")), name);
        boolean hidden = asBoolean(fm.get("hidden"), false);
        Boolean exposeToUser = asNullableBoolean(fm.get("expose_to_user"));
        if (exposeToUser == null) {
            exposeToUser = asNullableBoolean(fm.get("exposeToUser"));
        }

        List<String> tools = parseToolNames(asString(fm.get("tools")));
        List<String> skills = parseToolNames(asString(fm.get("skills")));

        SubagentDeclaration.Builder builder =
                SubagentDeclaration.builder()
                        .name(name)
                        .description(description)
                        .workspaceMode(mode)
                        .model(model)
                        .steps(steps)
                        .temperature(temperature)
                        .topP(topP)
                        .variant(variant)
                        .mode(declMode)
                        .hidden(hidden)
                        .exposeToUser(exposeToUser)
                        .tools(tools.isEmpty() ? null : tools)
                        .skills(skills.isEmpty() ? null : skills);

        if (workspacePath != null) {
            builder.workspace(workspacePath);
        } else {
            builder.inlineAgentsBody(body.isEmpty() ? null : body);
        }

        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String stripMdExtension(String filename) {
        if (filename.endsWith(".md")) {
            return filename.substring(0, filename.length() - 3);
        }
        return filename;
    }

    private static Path resolvePath(String pathStr, Path mainWorkspace) {
        Path p = Path.of(pathStr);
        if (p.isAbsolute()) {
            return p;
        }
        if (mainWorkspace != null) {
            Path resolved = mainWorkspace.resolve(p).normalize();
            return resolved;
        }
        return p;
    }

    private static String asString(Object v) {
        return v != null ? v.toString().trim() : null;
    }

    private static boolean asBoolean(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim();
        if (s.isEmpty()) return def;
        return Boolean.parseBoolean(s);
    }

    /**
     * Parses a tri-state boolean: {@code null} when the key is absent or blank (no opinion),
     * otherwise the parsed boolean. Used for front-matter flags that distinguish "unset" from
     * an explicit {@code false}.
     */
    private static Boolean asNullableBoolean(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        return Boolean.parseBoolean(s);
    }

    private static SubagentDeclaration.Mode parseDeclarationMode(String s, String name) {
        if (s == null || s.isBlank()) return SubagentDeclaration.Mode.ALL;
        switch (s.toLowerCase()) {
            case "primary":
                return SubagentDeclaration.Mode.PRIMARY;
            case "subagent":
                return SubagentDeclaration.Mode.SUBAGENT;
            case "all":
                return SubagentDeclaration.Mode.ALL;
            default:
                log.warn(
                        "Unknown mode '{}' in subagent declaration '{}', defaulting to all",
                        s,
                        name);
                return SubagentDeclaration.Mode.ALL;
        }
    }

    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse numeric value '{}' — treating as unset", s);
            return null;
        }
    }

    private static List<String> parseToolNames(String toolsStr) {
        if (toolsStr == null || toolsStr.isBlank()) {
            return List.of();
        }
        // Support both comma-separated strings and YAML list representations "[a, b, c]"
        String cleaned = toolsStr.stripLeading();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return Stream.of(cleaned.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
