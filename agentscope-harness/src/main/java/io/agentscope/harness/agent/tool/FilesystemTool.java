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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.util.List;
import java.util.stream.Collectors;

/**
 * File system tools backed by a {@link AbstractFilesystem}, exposing read/write/edit/grep/glob
 * operations as agent-callable tools.
 */
public class FilesystemTool {

    private final AbstractFilesystem abstractFilesystem;
    private final WorkspacePathNormalizer pathNormalizer;

    public FilesystemTool(AbstractFilesystem abstractFilesystem) {
        this(abstractFilesystem, null);
    }

    public FilesystemTool(
            AbstractFilesystem abstractFilesystem, WorkspacePathNormalizer pathNormalizer) {
        this.abstractFilesystem = abstractFilesystem;
        this.pathNormalizer = pathNormalizer;
    }

    private String norm(String path) {
        return pathNormalizer != null ? pathNormalizer.normalize(path) : path;
    }

    @Tool(
            name = "read_file",
            readOnly = true,
            description =
                    "Read file content with line numbers. Supports pagination via offset and"
                            + " limit.")
    public String readFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "File path to read") String path,
            @ToolParam(
                            name = "offset",
                            description = "Start line (0-indexed). Default: 0 (from beginning)",
                            required = false)
                    Integer offset,
            @ToolParam(
                            name = "limit",
                            description = "Max lines to return. Default: 0 (all lines)",
                            required = false)
                    Integer limit) {
        int off = offset != null ? offset : 0;
        int lim = limit != null ? limit : 0;
        ReadResult r = abstractFilesystem.read(runtimeContext, norm(path), off, lim);
        if (!r.isSuccess()) {
            return "Error: " + r.error();
        }
        return r.fileData() != null ? r.fileData().content() : "";
    }

    @Tool(
            name = "write_file",
            description = "Write content to a new file, creating parent directories if needed.")
    public String writeFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Target file path") String path,
            @ToolParam(name = "content", description = "File content to write") String content) {
        WriteResult r = abstractFilesystem.write(runtimeContext, norm(path), content);
        return r.isSuccess() ? "Written to " + r.path() : "Error: " + r.error();
    }

    @Tool(
            name = "edit_file",
            description =
                    "Perform exact string replacement in a file. The old_string must be unique"
                            + " unless replace_all is true.")
    public String editFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "File to edit") String path,
            @ToolParam(name = "old_string", description = "Text to find") String oldString,
            @ToolParam(name = "new_string", description = "Replacement text") String newString,
            @ToolParam(
                            name = "replace_all",
                            description = "Replace all occurrences (default: false)",
                            required = false)
                    Boolean replaceAll) {
        boolean shouldReplaceAll = Boolean.TRUE.equals(replaceAll);
        EditResult r =
                abstractFilesystem.edit(
                        runtimeContext, norm(path), oldString, newString, shouldReplaceAll);
        return r.isSuccess()
                ? "Edited " + r.path() + " (" + r.occurrences() + " replacement(s))"
                : "Error: " + r.error();
    }

    @Tool(
            name = "grep_files",
            readOnly = true,
            description = "Search file contents for a literal text pattern.")
    public String grepFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "pattern", description = "Literal text pattern to search for")
                    String pattern,
            @ToolParam(name = "path", description = "Directory or file to search", required = false)
                    String path,
            @ToolParam(
                            name = "glob",
                            description = "Optional file glob filter (e.g., *.java)",
                            required = false)
                    String glob) {
        GrepResult r = abstractFilesystem.grep(runtimeContext, pattern, norm(path), glob);
        if (!r.isSuccess()) {
            return "Error: " + r.error();
        }
        List<GrepMatch> matches = r.matches();
        if (matches == null || matches.isEmpty()) {
            return "No matches found";
        }
        return matches.stream()
                .map(m -> m.path() + ":" + m.line() + ":" + m.text())
                .collect(Collectors.joining("\n"));
    }

    @Tool(name = "glob_files", readOnly = true, description = "Find files matching a glob pattern.")
    public String globFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "pattern", description = "Glob pattern (e.g., **/*.java)")
                    String pattern,
            @ToolParam(
                            name = "path",
                            description = "Base directory to search from",
                            required = false)
                    String path) {
        GlobResult r = abstractFilesystem.glob(runtimeContext, pattern, norm(path));
        if (!r.isSuccess()) {
            return "Error: " + r.error();
        }
        List<FileInfo> files = r.matches();
        if (files == null || files.isEmpty()) {
            return "No matching files found";
        }
        return files.stream()
                .map(f -> f.path() + (f.isDirectory() ? "/" : " (" + f.size() + " bytes)"))
                .collect(Collectors.joining("\n"));
    }

    @Tool(
            name = "list_files",
            readOnly = true,
            description = "List files and directories at the given path.")
    public String listFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Directory path to list") String path) {
        LsResult r = abstractFilesystem.ls(runtimeContext, norm(path));
        if (!r.isSuccess()) {
            return "Error: " + r.error();
        }
        List<FileInfo> infos = r.entries();
        if (infos == null || infos.isEmpty()) {
            return "Empty or not a directory: " + path;
        }
        return infos.stream()
                .map(
                        f ->
                                (f.isDirectory() ? "[DIR]  " : "[FILE] ")
                                        + f.path()
                                        + (f.isDirectory() ? "" : " (" + f.size() + " bytes)"))
                .collect(Collectors.joining("\n"));
    }
}
