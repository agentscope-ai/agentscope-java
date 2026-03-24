/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.subagent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AgentScope tool: search files by glob pattern under a workspace path.
 * Register via {@link io.agentscope.core.tool.Toolkit#registerTool(Object)}.
 */
public class GlobSearchTool {

    private final Path workspacePath;

    public GlobSearchTool(Path workspacePath) {
        this.workspacePath = workspacePath.toAbsolutePath().normalize();
    }

    @Tool(
            name = "glob_search",
            description =
                    "Search for files matching a glob pattern (e.g. **/*.java, src/**/*.ts) under"
                            + " the workspace.")
    public String globSearch(
            @ToolParam(name = "pattern", description = "Glob pattern (e.g. **/*.java)")
                    String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return "Error: pattern is required.";
        }
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            try (Stream<Path> walk = Files.walk(workspacePath)) {
                String result =
                        walk.filter(Files::isRegularFile)
                                .filter(p -> matcher.matches(workspacePath.relativize(p)))
                                .limit(200)
                                .map(p -> workspacePath.relativize(p).toString())
                                .collect(Collectors.joining("\n"));
                return result.isEmpty() ? "No files matched " + pattern : result;
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public static GlobSearchTool create(Path workspacePath) {
        return new GlobSearchTool(workspacePath);
    }
}
