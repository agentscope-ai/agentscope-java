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
 * Loads {@link SubagentSpec} definitions from Markdown files with YAML front matter. Compatible
 * with Spring AI agent spec format.
 *
 * <p>File format:
 *
 * <pre>
 * ---
 * name: Explore
 * description: Fast agent for exploring codebases...
 * tools: Read, Grep, Glob
 * ---
 *
 * # System prompt (markdown body)
 * You are a file search specialist...
 * </pre>
 *
 * <p>Front matter fields:
 *
 * <ul>
 *   <li>{@code name} (required) — maps to {@link SubagentSpec#getName()}
 *   <li>{@code description} (required)
 *   <li>{@code tools} (optional, comma-separated) — maps to {@link SubagentSpec#getTools()}
 *   <li>{@code model} (optional) — override model name, maps to {@link SubagentSpec#getModel()}
 *   <li>{@code maxIters} (optional, default 10)
 * </ul>
 *
 * <p>The Markdown body becomes the system prompt.
 */
public final class AgentSpecLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentSpecLoader.class);
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private AgentSpecLoader() {}

    /** Recursively scans a directory for {@code .md} files and parses each into a SubagentSpec. */
    public static List<SubagentSpec> loadFromDirectory(Path rootPath) {
        if (rootPath == null || !Files.isDirectory(rootPath)) {
            return Collections.emptyList();
        }
        List<SubagentSpec> specs = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .forEach(
                            path -> {
                                try {
                                    SubagentSpec spec = loadFromFile(path);
                                    if (spec != null) {
                                        specs.add(spec);
                                        log.debug(
                                                "Loaded agent spec '{}' from {}",
                                                spec.getName(),
                                                path);
                                    }
                                } catch (Exception e) {
                                    log.warn(
                                            "Failed to load agent spec from {}: {}",
                                            path,
                                            e.getMessage());
                                }
                            });
        } catch (IOException e) {
            log.warn("Failed to walk directory {}: {}", rootPath, e.getMessage());
        }
        return specs;
    }

    public static SubagentSpec loadFromFile(Path filePath) throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        return parse(content);
    }

    /**
     * Parses markdown content with YAML front matter into a {@link SubagentSpec}.
     *
     * @return parsed spec, or null if the content is malformed
     */
    @SuppressWarnings("unchecked")
    public static SubagentSpec parse(String markdown) {
        if (markdown == null || markdown.isBlank() || !markdown.startsWith("---")) {
            return null;
        }
        int endIdx = markdown.indexOf("---", 3);
        if (endIdx == -1) {
            log.warn("Agent spec front matter not closed with ---");
            return null;
        }

        String frontMatterStr = markdown.substring(3, endIdx).trim();
        String body = markdown.substring(endIdx + 3).trim();

        Map<String, Object> frontMatter;
        try {
            frontMatter = YAML_MAPPER.readValue(frontMatterStr, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse YAML front matter: {}", e.getMessage());
            return null;
        }
        if (frontMatter == null || frontMatter.isEmpty()) {
            return null;
        }

        String name = asString(frontMatter.get("name"));
        String description = asString(frontMatter.get("description"));
        if (name == null || name.isBlank()) {
            log.warn("Agent spec missing required 'name' in front matter");
            return null;
        }
        if (description == null || description.isBlank()) {
            log.warn("Agent spec missing required 'description' in front matter");
            return null;
        }

        SubagentSpec spec = new SubagentSpec(name, description);
        spec.setSysPrompt(body.isEmpty() ? null : body);
        spec.setTools(parseToolNames(asString(frontMatter.get("tools"))));
        spec.setModel(asString(frontMatter.get("model")));

        Object maxItersObj = frontMatter.get("maxIters");
        if (maxItersObj instanceof Number n) {
            spec.setMaxIters(n.intValue());
        }

        return spec;
    }

    private static String asString(Object v) {
        return v != null ? v.toString().trim() : null;
    }

    private static List<String> parseToolNames(String toolsStr) {
        if (toolsStr == null || toolsStr.isBlank()) {
            return List.of();
        }
        return Stream.of(toolsStr.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
