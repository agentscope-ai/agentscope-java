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
package io.agentscope.harness.agent.filesystem;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.util.List;
import java.util.Map;

/**
 * Abstract filesystem API for agents: list, read, write, edit, grep, glob, upload, download.
 *
 * <p>Implementations may target the local disk, a sandbox, a key-value store, or other storage.
 * Host-rooted types {@link LocalFilesystem} and {@link LocalFilesystemWithShell} also expose
 * constructors that take the workspace root as a {@link String} path (same semantics as
 * {@link java.nio.file.Path}).
 *
 * <p>Every operation accepts a {@link RuntimeContext} so backends can scope work to the current
 * session, user, or sandbox. Callers that are not inside a tool/agent call with a merged context
 * should pass {@link RuntimeContext#empty()}.
 */
public interface AbstractFilesystem {

    /**
     * List all files in a directory with metadata.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param path absolute path to the directory to list (must start with '/')
     * @return LsResult with directory entries or error
     */
    LsResult ls(RuntimeContext runtimeContext, String path);

    /**
     * Read file content with optional line-based pagination.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param filePath absolute path to the file to read (must start with '/')
     * @param offset line number to start reading from (0-indexed). Default: 0
     * @param limit maximum number of lines to read. Default: 2000
     * @return ReadResult with file data on success or error on failure
     */
    ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit);

    /**
     * Write content to a new file, error if file already exists.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param filePath absolute path where the file should be created
     * @param content string content to write to the file
     * @return WriteResult with path on success, or error if the file already exists or write fails
     */
    WriteResult write(RuntimeContext runtimeContext, String filePath, String content);

    /**
     * Perform exact string replacements in an existing file.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param filePath absolute path to the file to edit
     * @param oldString exact string to search for and replace
     * @param newString string to replace oldString with (must be different from oldString)
     * @param replaceAll if true, replace all occurrences; if false, oldString must be unique
     * @return EditResult with path and occurrence count on success, or error on failure
     */
    EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll);

    /**
     * Search for a literal text pattern in files.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param pattern literal string to search for (not regex)
     * @param path optional directory path to search in (null searches current working directory)
     * @param glob optional glob pattern to filter which files to search (e.g., "*.java")
     * @return GrepResult with matches or error
     */
    GrepResult grep(RuntimeContext runtimeContext, String pattern, String path, String glob);

    /**
     * Find files matching a glob pattern.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param pattern glob pattern with wildcards to match file paths
     * @param path base directory to search from (default: "/")
     * @return GlobResult with matching files or error
     */
    GlobResult glob(RuntimeContext runtimeContext, String pattern, String path);

    /**
     * Upload multiple files.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param files list of path-to-content mappings to upload
     * @return list of FileUploadResponse objects, one per input file (order matches input order)
     */
    List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files);

    /**
     * Download multiple files.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param paths list of file paths to download
     * @return list of FileDownloadResponse objects, one per input path (order matches input order)
     */
    List<FileDownloadResponse> downloadFiles(RuntimeContext runtimeContext, List<String> paths);

    /**
     * Delete a file or directory (recursive for directories).
     *
     * <p>Idempotent: deleting a path that does not exist is treated as success.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param path absolute path to the file or directory to delete
     * @return WriteResult success when deleted (or already absent), failure on I/O error
     */
    WriteResult delete(RuntimeContext runtimeContext, String path);

    /**
     * Move (rename) a file or directory from {@code fromPath} to {@code toPath}.
     *
     * <p>Implementations that span multiple backends (e.g. {@code CompositeFilesystem}) may
     * fall back to a read + write + delete sequence when source and destination live in
     * different backend filesystems.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param fromPath absolute source path
     * @param toPath absolute destination path
     * @return WriteResult success on completion, failure on I/O error or missing source
     */
    WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath);

    /**
     * Check whether a file or directory exists.
     *
     * <p>Implementations may approximate this with a lightweight read probe where a dedicated
     * {@code exists} API is unavailable, but should avoid reading full file content.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param path absolute path to check
     * @return {@code true} if the path exists, {@code false} otherwise
     */
    boolean exists(RuntimeContext runtimeContext, String path);

    // ==================== Path validation utility ====================

    /**
     * Validates that {@code path} is safe (non-null, non-blank, no {@code ..} traversal).
     *
     * @param path the path to validate
     * @throws IllegalArgumentException if the path is invalid
     */
    static void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be null or blank");
        }
        if (path.contains("..")) {
            throw new IllegalArgumentException("Path traversal ('..') not allowed: " + path);
        }
    }
}
