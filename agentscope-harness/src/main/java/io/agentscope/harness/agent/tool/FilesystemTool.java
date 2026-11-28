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
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolErrorMetadata;
import io.agentscope.core.message.ToolResultBlock;
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

/**
 * File system tools backed by a {@link AbstractFilesystem}, exposing read/write/edit/grep/glob
 * operations as agent-callable tools.
 */
public class FilesystemTool {

    static final int DEFAULT_GREP_LIMIT = 100;
    static final int DEFAULT_GLOB_LIMIT = 200;
    static final int MAX_SEARCH_LIMIT = 1_000;

    /** Default read page size in Unicode code points. */
    public static final int DEFAULT_READ_CHARS = 16_000;

    /** Default application limit for a read page in Unicode code points. */
    public static final int DEFAULT_MAX_READ_CHARS = 32_000;

    private final AbstractFilesystem abstractFilesystem;
    private final WorkspacePathNormalizer pathNormalizer;
    private final int readFileMaxChars;

    public FilesystemTool(AbstractFilesystem abstractFilesystem) {
        this(abstractFilesystem, null);
    }

    public FilesystemTool(
            AbstractFilesystem abstractFilesystem, WorkspacePathNormalizer pathNormalizer) {
        this(abstractFilesystem, pathNormalizer, DEFAULT_MAX_READ_CHARS);
    }

    /**
     * Creates file tools with an application-defined read page limit.
     *
     * @param readFileMaxChars maximum Unicode code points per page, at least {@link
     *     #DEFAULT_READ_CHARS}
     * @throws IllegalArgumentException if the limit is smaller than the default page size
     */
    public FilesystemTool(
            AbstractFilesystem abstractFilesystem,
            WorkspacePathNormalizer pathNormalizer,
            int readFileMaxChars) {
        if (readFileMaxChars < DEFAULT_READ_CHARS) {
            throw new IllegalArgumentException(
                    "readFileMaxChars must be at least " + DEFAULT_READ_CHARS);
        }
        this.abstractFilesystem = abstractFilesystem;
        this.pathNormalizer = pathNormalizer;
        this.readFileMaxChars = readFileMaxChars;
    }

    static final int MAX_LISTING_ENTRIES = 200;
    static final int MAX_LISTING_CHARS = 16000;

    private static String boundedListing(
            java.util.stream.Stream<String> lines, int total, int limit, String resultLabel) {
        StringBuilder out = new StringBuilder();
        var iterator = lines.iterator();
        int count = 0;
        boolean characterLimitReached = false;
        while (count < limit && iterator.hasNext()) {
            String line = iterator.next();
            int separatorLength = count > 0 ? 1 : 0;
            if (out.length() + line.length() + separatorLength > MAX_LISTING_CHARS) {
                characterLimitReached = true;
                break;
            }
            if (count++ > 0) out.append('\n');
            out.append(line);
        }
        if (count == total) {
            return out.toString();
        }
        String guidance =
                characterLimitReached
                        ? "Output character limit of "
                                + MAX_LISTING_CHARS
                                + " reached; narrow the path/pattern."
                        : "entries".equals(resultLabel)
                                ? "Listing limit of " + limit + " reached; narrow the directory."
                                : limit < MAX_SEARCH_LIMIT
                                        ? "Narrow the path/pattern or increase limit (hard maximum:"
                                                + " "
                                                + MAX_SEARCH_LIMIT
                                                + ")."
                                        : "Hard maximum of "
                                                + MAX_SEARCH_LIMIT
                                                + " reached; narrow the path/pattern to retrieve"
                                                + " more targeted results.";
        return out
                + "\n[Results truncated: showing "
                + count
                + " of "
                + total
                + " "
                + resultLabel
                + ". "
                + guidance
                + "]";
    }

    private String norm(String path, RuntimeContext runtimeContext) {
        return pathNormalizer != null ? pathNormalizer.normalize(path, runtimeContext) : path;
    }

    /**
     * Classifies a backend error at the tool boundary. Filesystem backends report a message and,
     * for writes, an explicit already-exists flag; the tool adds the machine-readable kind without
     * changing the text or the backend contract.
     */
    private static ToolResultBlock filesystemError(String message, String scope) {
        return ToolResultBlock.error(message)
                .withMetadata(ToolErrorMetadata.of(classifyFilesystemError(message, scope), scope));
    }

    private static ToolErrorKind classifyFilesystemError(String message, String scope) {
        String error = message == null ? "" : message;
        if (error.startsWith("Error: ")) error = error.substring(7);
        // Strip only a known operation envelope with this exact path. Never scan arbitrary
        // path names, replacement text, or backend diagnostics for error keywords.
        for (String operation : List.of("reading file", "writing file", "editing file")) {
            String prefix = "Error " + operation + " '" + scope + "': ";
            if (error.startsWith(prefix)) {
                error = error.substring(prefix.length());
                break;
            }
        }
        String lower = error.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("string not found in file:")
                || lower.startsWith("oldstring must ")
                || lower.startsWith("newstring must ")
                || lower.matches(
                        "(?s)string '.*' appears (?:[0-9]+ times in file|multiple times)\\. use"
                                + " replaceall=true .*")) {
            return ToolErrorKind.INVALID_ARGUMENT;
        }
        if (error.equals("File '" + scope + "' not found")
                || lower.startsWith("path does not exist:")
                || lower.startsWith("source does not exist:")
                || lower.equals("file_not_found")
                || lower.equals("no such file")
                || lower.equals("no such file or directory")
                || lower.startsWith("file not found:")) {
            return ToolErrorKind.NOT_FOUND;
        }
        if (lower.equals("already exists")
                || lower.startsWith("file already exists:")
                || error.startsWith("Edit conflict on '" + scope + "' after ")) {
            return ToolErrorKind.CONFLICT;
        }
        if (lower.equals("permission denied")
                || lower.startsWith("permission denied:")
                || lower.equals("access denied")
                || lower.startsWith("access denied:")
                || lower.startsWith("cannot delete shared ")
                || lower.startsWith("cannot read shared ")
                || lower.startsWith("read-only filesystem:")) {
            return ToolErrorKind.PERMISSION_DENIED;
        }
        if (lower.startsWith("not a directory:")
                || lower.startsWith("invalid file data")
                || lower.startsWith("line offset ")
                || error.equals("File '" + scope + "' is empty")
                || lower.equals("file is not valid utf-8")) {
            return ToolErrorKind.INVALID_ARGUMENT;
        }
        return ToolErrorKind.IO_ERROR;
    }

    @Tool(
            name = "read_file",
            readOnly = true,
            evictable = false,
            prunable = false,
            description =
                    "Read a file's content. Binary files come back base64-encoded. Paginate large"
                        + " files with offset (0-indexed start line) and limit (max lines). Output"
                        + " is also bounded by max_chars (Unicode code points). Continue with"
                        + " char_offset, keeping path, offset and limit unchanged, instead of"
                        + " reading everything at once; before editing, read the target region with"
                        + " enough surrounding context so your edit matches the current content.")
    public ToolResultBlock readFile(
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
                    Integer limit,
            @ToolParam(
                            name = "char_offset",
                            description =
                                    "Unicode code point offset within the selected line range."
                                            + " Default: 0",
                            required = false)
                    Integer charOffset,
            @ToolParam(
                            name = "max_chars",
                            description =
                                    "Maximum Unicode code points per page. Default: 16000; clamped"
                                        + " to the application-configured maximum (default: 32000)",
                            required = false)
                    Integer maxChars) {
        int start = charOffset != null ? charOffset : 0;
        int size = maxChars != null ? Math.min(maxChars, readFileMaxChars) : DEFAULT_READ_CHARS;
        String normalizedPath = norm(path, runtimeContext);
        if (start < 0 || size < 1) {
            return ToolResultBlock.error(
                            "char_offset must be non-negative and max_chars must be greater than 0")
                    .withMetadata(
                            ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT, normalizedPath));
        }
        int off = offset != null ? offset : 0;
        int lim = limit != null ? limit : 0;
        ReadResult r = abstractFilesystem.read(runtimeContext, normalizedPath, off, lim);
        if (!r.isSuccess()) {
            return filesystemError(r.error(), normalizedPath);
        }
        String content = r.fileData() != null ? r.fileData().content() : "";
        int total = content.codePointCount(0, content.length());
        if (start > total) {
            return ToolResultBlock.error(
                            "char_offset exceeds the selected content length: " + total)
                    .withMetadata(
                            ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT, normalizedPath));
        }
        int end = start + Math.min(size, total - start);
        String page =
                content.substring(
                        content.offsetByCodePoints(0, start), content.offsetByCodePoints(0, end));
        if (end < total) {
            page +=
                    "\n[Output truncated. Continue with char_offset="
                            + end
                            + " and the same path, offset and limit. Offsets count Unicode code"
                            + " points within the selected line range.]";
        }
        return ToolResultBlock.success(page);
    }

    /** Read using the default character page size. */
    public ToolResultBlock readFile(
            RuntimeContext runtimeContext, String path, Integer offset, Integer limit) {
        return readFile(runtimeContext, path, offset, limit, null, null);
    }

    @Tool(
            name = "write_file",
            evictable = false,
            description =
                    "Create a new file with the given full content (parent directories are"
                            + " created). Fails if the path already exists — read_file the"
                            + " existing file and use edit_file to change it instead of"
                            + " overwriting.")
    public ToolResultBlock writeFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Target file path") String path,
            @ToolParam(name = "content", description = "File content to write") String content) {
        String normalizedPath = norm(path, runtimeContext);
        WriteResult r = abstractFilesystem.write(runtimeContext, normalizedPath, content);
        if (r.isSuccess()) {
            return ToolResultBlock.success("Written to " + r.path());
        }
        return ToolResultBlock.error(r.error())
                .withMetadata(
                        ToolErrorMetadata.of(
                                r.isAlreadyExists()
                                        ? ToolErrorKind.CONFLICT
                                        : classifyFilesystemError(r.error(), normalizedPath),
                                normalizedPath));
    }

    @Tool(
            name = "edit_file",
            evictable = false,
            description =
                    "Replace an exact text string in an existing file after line-ending"
                            + " normalization; whitespace and indentation within lines must match."
                            + " There is no fuzzy matching. If the string is not"
                            + " found, read_file the region again and copy the exact text (with"
                            + " surrounding context) instead of guessing variants; if it appears"
                            + " more than once, add surrounding context to make it unique or set"
                            + " replace_all=true.")
    public ToolResultBlock editFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "File to edit") String path,
            @ToolParam(
                            name = "old_string",
                            description =
                                    "Text to find. Must match exactly once unless replace_all is"
                                            + " true; otherwise the edit fails.")
                    String oldString,
            @ToolParam(name = "new_string", description = "Replacement text") String newString,
            @ToolParam(
                            name = "replace_all",
                            description = "Replace all occurrences (default: false)",
                            required = false)
                    Boolean replaceAll) {
        boolean shouldReplaceAll = Boolean.TRUE.equals(replaceAll);
        String normalizedPath = norm(path, runtimeContext);
        EditResult r =
                abstractFilesystem.edit(
                        runtimeContext, normalizedPath, oldString, newString, shouldReplaceAll);
        return r.isSuccess()
                ? ToolResultBlock.success(
                        "Edited " + r.path() + " (" + r.occurrences() + " replacement(s))")
                : filesystemError(r.error(), normalizedPath);
    }

    @Tool(
            name = "grep_files",
            readOnly = true,
            description =
                    "Search file contents for literal text, not a regular expression. Returns"
                            + " path:line:text matches, at most 100 by default. Use glob_files for"
                            + " file path/name patterns and read_file on a matched file for"
                            + " surrounding context.")
    public ToolResultBlock grepFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "pattern", description = "Literal text pattern to search for")
                    String pattern,
            @ToolParam(name = "path", description = "Directory or file to search", required = false)
                    String path,
            @ToolParam(
                            name = "glob",
                            description =
                                    "Optional file glob filter matched against the file name only"
                                            + " (e.g., *.java). Path-based patterns such as"
                                            + " **/*.java are not supported here; the search"
                                            + " already recurses into subdirectories.",
                            required = false)
                    String glob,
            @ToolParam(
                            name = "limit",
                            description =
                                    "Maximum matches to return (default/recommended: 100; hard"
                                            + " maximum: 1000)",
                            required = false)
                    Integer limit) {
        int effectiveLimit = effectiveLimit(limit, DEFAULT_GREP_LIMIT);
        if (effectiveLimit < 1) {
            return ToolResultBlock.error("limit must be greater than 0")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }
        String normalizedPath = norm(path, runtimeContext);
        GrepResult r = abstractFilesystem.grep(runtimeContext, pattern, normalizedPath, glob);
        if (!r.isSuccess()) {
            return filesystemError(
                    r.error(),
                    normalizedPath == null || normalizedPath.isBlank() ? "." : normalizedPath);
        }
        List<GrepMatch> matches = r.matches();
        if (matches == null || matches.isEmpty()) {
            return ToolResultBlock.success("No matches found");
        }
        return ToolResultBlock.success(
                boundedListing(
                        matches.stream().map(m -> m.path() + ":" + m.line() + ":" + m.text()),
                        matches.size(),
                        effectiveLimit,
                        "matches"));
    }

    /** Search using the default result limit. */
    public ToolResultBlock grepFiles(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        return grepFiles(runtimeContext, pattern, path, glob, null);
    }

    @Tool(
            name = "glob_files",
            readOnly = true,
            description =
                    "Find files by name using a glob pattern (e.g., **/*.java). Use this to locate"
                            + " files by path/name; use grep_files instead to search file"
                            + " contents. Returns at most 200 files by default to keep tool"
                            + " output bounded.")
    public ToolResultBlock globFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "pattern", description = "Glob pattern (e.g., **/*.java)")
                    String pattern,
            @ToolParam(
                            name = "path",
                            description = "Base directory to search from",
                            required = false)
                    String path,
            @ToolParam(
                            name = "limit",
                            description =
                                    "Maximum files to return (default/recommended: 200; hard"
                                            + " maximum: 1000)",
                            required = false)
                    Integer limit) {
        int effectiveLimit = effectiveLimit(limit, DEFAULT_GLOB_LIMIT);
        if (effectiveLimit < 1) {
            return ToolResultBlock.error("limit must be greater than 0")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }
        String normalizedPath = norm(path, runtimeContext);
        GlobResult r = abstractFilesystem.glob(runtimeContext, pattern, normalizedPath);
        if (!r.isSuccess()) {
            return filesystemError(
                    r.error(),
                    normalizedPath == null || normalizedPath.isBlank() ? "." : normalizedPath);
        }
        List<FileInfo> files = r.matches();
        if (files == null || files.isEmpty()) {
            return ToolResultBlock.success("No matching files found");
        }
        return ToolResultBlock.success(
                boundedListing(
                        files.stream()
                                .map(
                                        f ->
                                                f.path()
                                                        + (f.isDirectory()
                                                                ? "/"
                                                                : " (" + f.size() + " bytes)")),
                        files.size(),
                        effectiveLimit,
                        "files"));
    }

    /** Search using the default result limit. */
    public ToolResultBlock globFiles(RuntimeContext runtimeContext, String pattern, String path) {
        return globFiles(runtimeContext, pattern, path, null);
    }

    private static int effectiveLimit(Integer requestedLimit, int defaultLimit) {
        if (requestedLimit == null) {
            return defaultLimit;
        }
        return Math.min(requestedLimit, MAX_SEARCH_LIMIT);
    }

    @Tool(
            name = "list_files",
            readOnly = true,
            description = "List files and directories at the given path.")
    public ToolResultBlock listFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Directory path to list") String path) {
        String normalizedPath = norm(path, runtimeContext);
        LsResult r = abstractFilesystem.ls(runtimeContext, normalizedPath);
        if (!r.isSuccess()) {
            return filesystemError(r.error(), normalizedPath);
        }
        List<FileInfo> infos = r.entries();
        if (infos == null || infos.isEmpty()) {
            return ToolResultBlock.success("Empty directory: " + path);
        }
        return ToolResultBlock.success(
                boundedListing(
                        infos.stream()
                                .map(
                                        f ->
                                                (f.isDirectory() ? "[DIR]  " : "[FILE] ")
                                                        + f.path()
                                                        + (f.isDirectory()
                                                                ? ""
                                                                : " (" + f.size() + " bytes)")),
                        infos.size(),
                        MAX_LISTING_ENTRIES,
                        "entries"));
    }
}
