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
 */
public interface AbstractFilesystem {

    /**
     * List all files in a directory with metadata.
     *
     * @param path absolute path to the directory to list (must start with '/')
     * @return LsResult with directory entries or error
     */
    LsResult ls(String path);

    /**
     * Read file content with optional line-based pagination.
     *
     * @param filePath absolute path to the file to read (must start with '/')
     * @param offset line number to start reading from (0-indexed). Default: 0
     * @param limit maximum number of lines to read. Default: 2000
     * @return ReadResult with file data on success or error on failure
     */
    ReadResult read(String filePath, int offset, int limit);

    /**
     * Write content to a new file, error if file already exists.
     *
     * @param filePath absolute path where the file should be created
     * @param content string content to write to the file
     * @return WriteResult with path on success, or error if the file already exists or write fails
     */
    WriteResult write(String filePath, String content);

    /**
     * Perform exact string replacements in an existing file.
     *
     * @param filePath absolute path to the file to edit
     * @param oldString exact string to search for and replace
     * @param newString string to replace oldString with (must be different from oldString)
     * @param replaceAll if true, replace all occurrences; if false, oldString must be unique
     * @return EditResult with path and occurrence count on success, or error on failure
     */
    EditResult edit(String filePath, String oldString, String newString, boolean replaceAll);

    /**
     * Search for a literal text pattern in files.
     *
     * @param pattern literal string to search for (not regex)
     * @param path optional directory path to search in (null searches current working directory)
     * @param glob optional glob pattern to filter which files to search (e.g., "*.java")
     * @return GrepResult with matches or error
     */
    GrepResult grep(String pattern, String path, String glob);

    /**
     * Find files matching a glob pattern.
     *
     * @param pattern glob pattern with wildcards to match file paths
     * @param path base directory to search from (default: "/")
     * @return GlobResult with matching files or error
     */
    GlobResult glob(String pattern, String path);

    /**
     * Upload multiple files.
     *
     * @param files list of path-to-content mappings to upload
     * @return list of FileUploadResponse objects, one per input file (order matches input order)
     */
    List<FileUploadResponse> uploadFiles(List<Map.Entry<String, byte[]>> files);

    /**
     * Download multiple files.
     *
     * @param paths list of file paths to download
     * @return list of FileDownloadResponse objects, one per input path (order matches input order)
     */
    List<FileDownloadResponse> downloadFiles(List<String> paths);
}
