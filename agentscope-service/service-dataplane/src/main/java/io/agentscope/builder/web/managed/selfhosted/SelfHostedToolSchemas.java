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
package io.agentscope.builder.web.managed.selfhosted;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema definitions for the self-hosted external toolset. Names and parameters mirror
 * {@code FilesystemTool} / {@link ShellExecuteTool} so the model sees the same surface while
 * execution is suspended to an Environment Worker.
 */
public final class SelfHostedToolSchemas {

    private SelfHostedToolSchemas() {}

    /** All externalized hands tools for {@code self_hosted} environments. */
    public static List<ToolSchema> all() {
        return List.of(
                execute(),
                readFile(),
                writeFile(),
                editFile(),
                grepFiles(),
                globFiles(),
                listFiles());
    }

    /** Names of tools that must be executed by a self-hosted worker. */
    public static boolean isHandsTool(String name) {
        if (name == null) {
            return false;
        }
        return ShellExecuteTool.NAME.equals(name)
                || "read_file".equals(name)
                || "write_file".equals(name)
                || "edit_file".equals(name)
                || "grep_files".equals(name)
                || "glob_files".equals(name)
                || "list_files".equals(name);
    }

    public static ToolSchema execute() {
        return ToolSchema.builder()
                .name(ShellExecuteTool.NAME)
                .description(
                        "Execute a shell command. Use for git, npm, build, test, and other"
                                + " terminal operations. Returns combined output and exit code. If"
                                + " a dedicated tool exists (e.g., read_file, write_file), you MUST"
                                + " use it instead of shell commands.")
                .parameters(
                        objectSchema(
                                Map.of(
                                        "command",
                                        prop("string", "Shell command to execute"),
                                        "working_directory",
                                        prop(
                                                "string",
                                                "Working directory (relative to workspace root,"
                                                        + " optional)"),
                                        "timeout",
                                        prop("integer", "Timeout in seconds (default: 30)")),
                                List.of("command")))
                .build();
    }

    public static ToolSchema readFile() {
        return ToolSchema.builder()
                .name("read_file")
                .description(
                        "Read file content with line numbers. Supports pagination via offset and"
                                + " limit.")
                .parameters(
                        objectSchema(
                                Map.of(
                                        "path",
                                        prop("string", "File path to read"),
                                        "offset",
                                        prop(
                                                "integer",
                                                "Start line (0-indexed). Default: 0 (from"
                                                        + " beginning)"),
                                        "limit",
                                        prop(
                                                "integer",
                                                "Max lines to return. Default: 0 (all lines)")),
                                List.of("path")))
                .build();
    }

    public static ToolSchema writeFile() {
        return ToolSchema.builder()
                .name("write_file")
                .description("Write content to a new file, creating parent directories if needed.")
                .parameters(
                        objectSchema(
                                Map.of(
                                        "path",
                                        prop("string", "Target file path"),
                                        "content",
                                        prop("string", "File content to write")),
                                List.of("path", "content")))
                .build();
    }

    public static ToolSchema editFile() {
        return ToolSchema.builder()
                .name("edit_file")
                .description(
                        "Perform exact string replacement in a file. The old_string must be unique"
                                + " unless replace_all is true.")
                .parameters(
                        objectSchema(
                                Map.of(
                                        "path",
                                        prop("string", "File to edit"),
                                        "old_string",
                                        prop("string", "Text to find"),
                                        "new_string",
                                        prop("string", "Replacement text"),
                                        "replace_all",
                                        prop(
                                                "boolean",
                                                "Replace all occurrences (default: false)")),
                                List.of("path", "old_string", "new_string")))
                .build();
    }

    public static ToolSchema grepFiles() {
        return ToolSchema.builder()
                .name("grep_files")
                .description("Search file contents for a literal text pattern.")
                .parameters(
                        objectSchema(
                                Map.of(
                                        "pattern",
                                        prop("string", "Literal text pattern to search for"),
                                        "path",
                                        prop("string", "Directory or file to search"),
                                        "glob",
                                        prop("string", "Optional file glob filter (e.g., *.java)")),
                                List.of("pattern")))
                .build();
    }

    public static ToolSchema globFiles() {
        return ToolSchema.builder()
                .name("glob_files")
                .description("Find files matching a glob pattern.")
                .parameters(
                        objectSchema(
                                Map.of(
                                        "pattern",
                                        prop("string", "Glob pattern (e.g., **/*.java)"),
                                        "path",
                                        prop("string", "Base directory to search from")),
                                List.of("pattern")))
                .build();
    }

    public static ToolSchema listFiles() {
        return ToolSchema.builder()
                .name("list_files")
                .description("List files and directories at the given path.")
                .parameters(
                        objectSchema(
                                Map.of("path", prop("string", "Directory path to list")),
                                List.of("path")))
                .build();
    }

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }
}
