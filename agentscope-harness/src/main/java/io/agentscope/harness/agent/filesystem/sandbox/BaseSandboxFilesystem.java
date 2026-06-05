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
package io.agentscope.harness.agent.filesystem.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Abstract base sandbox implementation with {@link #execute} as the core abstract method.
 *
 * <p>This class provides default implementations for all {@link AbstractFilesystem} methods by
 * delegating to shell commands via {@link #execute}. File listing, grep, and glob use standard
 * Unix commands. Read uses server-side commands for paginated access. Write delegates content
 * transfer to {@link #uploadFiles}. Edit downloads the file, applies replacement in Java, and
 * uploads the updated content back.
 *
 * <p>Subclasses must implement:
 * <ul>
 *   <li>{@link #execute} - execute a command in the sandbox</li>
 *   <li>{@link #uploadFiles} - upload files to the sandbox</li>
 *   <li>{@link #downloadFiles} - download files from the sandbox</li>
 *   <li>{@link #id()} - unique identifier for the sandbox instance</li>
 * </ul>
 */
public abstract class BaseSandboxFilesystem implements AbstractSandboxFilesystem {

    private final ConcurrentHashMap<String, ReentrantLock> editLocks = new ConcurrentHashMap<>();

    @Override
    public abstract String id();

    @Override
    public abstract ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds);

    @Override
    public abstract List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files);

    @Override
    public abstract List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths);

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        String escapedPath = FilesystemUtils.shellQuote(path);
        String cmd =
                "for f in "
                        + escapedPath
                        + "/*; do "
                        + "  if [ -d \"$f\" ]; then echo \"DIR:$f\"; "
                        + "  elif [ -f \"$f\" ]; then echo \"FILE:$f\"; fi; "
                        + "done 2>/dev/null";

        ExecuteResponse result = execute(runtimeContext, cmd, null);
        List<FileInfo> entries = new ArrayList<>();

        if (result.output() != null && !result.output().isBlank()) {
            for (String line : result.output().strip().split("\n")) {
                if (line.startsWith("DIR:")) {
                    entries.add(FileInfo.ofDir(line.substring(4), ""));
                } else if (line.startsWith("FILE:")) {
                    entries.add(FileInfo.ofFile(line.substring(5), 0, ""));
                }
            }
        }

        return LsResult.success(entries);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        String fileType = FilesystemUtils.getFileType(filePath);
        String escapedPath = FilesystemUtils.shellQuote(filePath);

        if (!"text".equals(fileType)) {
            String cmd = "base64 " + escapedPath + " 2>/dev/null";
            ExecuteResponse result = execute(runtimeContext, cmd, null);
            if (result.exitCode() != null && result.exitCode() != 0) {
                return ReadResult.fail("File '" + filePath + "': file_not_found");
            }
            String encoded = result.output() != null ? result.output().strip() : "";
            return ReadResult.success(new FileData(encoded, "base64"));
        }

        int startLine = offset + 1;
        int endLine = limit > 0 ? offset + limit : Integer.MAX_VALUE;
        String cmd =
                "if [ ! -f "
                        + escapedPath
                        + " ]; then echo '__NOT_FOUND__'; "
                        + "elif [ ! -s "
                        + escapedPath
                        + " ]; then echo '__EMPTY__'; "
                        + "else sed -n '"
                        + startLine
                        + ","
                        + endLine
                        + "p' "
                        + escapedPath
                        + "; fi";

        ExecuteResponse result = execute(runtimeContext, cmd, null);
        String output = result.output() != null ? result.output() : "";

        if (output.strip().equals("__NOT_FOUND__")) {
            return ReadResult.fail("File '" + filePath + "': file_not_found");
        }
        if (output.strip().equals("__EMPTY__")) {
            return ReadResult.success(
                    new FileData("System reminder: File exists but has empty contents", "utf-8"));
        }

        if (output.endsWith("\n")) {
            output = output.substring(0, output.length() - 1);
        }
        return ReadResult.success(new FileData(output, "utf-8"));
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        String escapedPath = FilesystemUtils.shellQuote(filePath);
        String checkCmd =
                "if [ -e "
                        + escapedPath
                        + " ]; then echo 'EXISTS'; exit 1; fi; "
                        + "mkdir -p \"$(dirname "
                        + escapedPath
                        + ")\" 2>&1";

        ExecuteResponse checkResult = execute(runtimeContext, checkCmd, null);
        if (checkResult.exitCode() != null && checkResult.exitCode() != 0) {
            if (checkResult.output() != null && checkResult.output().contains("EXISTS")) {
                return WriteResult.fail(
                        "Cannot write to "
                                + filePath
                                + " because it already exists. Read and then make an"
                                + " edit, or write to a new path.");
            }
            return WriteResult.fail("Failed to write file '" + filePath + "'");
        }

        List<FileUploadResponse> responses =
                uploadFiles(
                        runtimeContext,
                        List.of(
                                Map.entry(
                                        filePath,
                                        content.getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8))));
        if (responses.isEmpty() || !responses.get(0).isSuccess()) {
            String err =
                    responses.isEmpty() ? "upload returned no response" : responses.get(0).error();
            return WriteResult.fail("Failed to write file '" + filePath + "': " + err);
        }

        return WriteResult.ok(filePath);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        ReentrantLock lock = editLocks.computeIfAbsent(filePath, key -> new ReentrantLock());
        lock.lock();
        try {
            List<FileDownloadResponse> downloads = downloadFiles(runtimeContext, List.of(filePath));
            if (downloads.isEmpty()) {
                return EditResult.fail(
                        "Error editing file '" + filePath + "': download returned no response");
            }

            FileDownloadResponse download = downloads.get(0);
            if (!download.isSuccess() || download.content() == null) {
                String error =
                        download.error() != null ? download.error() : "unknown download error";
                if (isMissingFileError(error)) {
                    return EditResult.fail("Error: File '" + filePath + "' not found");
                }
                return EditResult.fail("Error editing file '" + filePath + "': " + error);
            }

            String content;
            try {
                content = decodeUtf8Strict(download.content());
            } catch (CharacterCodingException e) {
                return EditResult.fail(
                        "Error editing file '" + filePath + "': file content is not valid UTF-8");
            }

            Object[] result =
                    FilesystemUtils.performStringReplacement(
                            content, oldString, newString, replaceAll);

            if (result.length == 1) {
                return EditResult.fail((String) result[0]);
            }

            String newContent = (String) result[0];
            int occurrences = (int) result[1];

            List<FileUploadResponse> uploads =
                    uploadFiles(
                            runtimeContext,
                            List.of(
                                    Map.entry(
                                            filePath,
                                            newContent.getBytes(StandardCharsets.UTF_8))));
            if (uploads.isEmpty()) {
                return EditResult.fail(
                        "Error editing file '" + filePath + "': upload returned no response");
            }

            FileUploadResponse upload = uploads.get(0);
            if (!upload.isSuccess()) {
                String error = upload.error() != null ? upload.error() : "unknown upload error";
                return EditResult.fail("Error editing file '" + filePath + "': " + error);
            }

            return EditResult.ok(filePath, occurrences);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        String searchPath = FilesystemUtils.shellQuote(path != null ? path : ".");
        String grepOpts = "-rHnF";
        String globPattern = "";
        if (glob != null && !glob.isBlank()) {
            globPattern = "--include=" + FilesystemUtils.shellQuote(glob);
        }
        String patternEscaped = FilesystemUtils.shellQuote(pattern);

        String cmd =
                "grep "
                        + grepOpts
                        + " "
                        + globPattern
                        + " -e "
                        + patternEscaped
                        + " "
                        + searchPath
                        + " 2>/dev/null || true";

        ExecuteResponse result = execute(runtimeContext, cmd, null);
        String output = result.output() != null ? result.output().strip() : "";

        if (output.isEmpty()) {
            return GrepResult.success(List.of());
        }

        List<GrepMatch> matches = new ArrayList<>();
        for (String line : output.split("\n")) {
            String[] parts = line.split(":", 3);
            if (parts.length >= 3) {
                try {
                    matches.add(new GrepMatch(parts[0], Integer.parseInt(parts[1]), parts[2]));
                } catch (NumberFormatException e) {
                    // skip malformed lines
                }
            }
        }

        return GrepResult.success(matches);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String escapedPath = FilesystemUtils.shellQuote(path != null ? path : "/");
        String escapedPattern = FilesystemUtils.shellQuote(pattern);

        String cmd =
                "find " + escapedPath + " -type f -name " + escapedPattern + " 2>/dev/null | sort";

        ExecuteResponse result = execute(runtimeContext, cmd, null);
        String output = result.output() != null ? result.output().strip() : "";

        if (output.isEmpty()) {
            return GlobResult.success(List.of());
        }

        List<FileInfo> entries = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (!line.isBlank()) {
                entries.add(FileInfo.ofFile(line.trim(), 0, ""));
            }
        }

        return GlobResult.success(entries);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        AbstractFilesystem.validatePath(path);
        String escapedPath = FilesystemUtils.shellQuote(path);
        String cmd = "rm -rf " + escapedPath;
        ExecuteResponse result = execute(runtimeContext, cmd, null);
        if (result.exitCode() != 0) {
            return WriteResult.fail("Error deleting '" + path + "': " + result.output());
        }
        return WriteResult.ok(path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        AbstractFilesystem.validatePath(fromPath);
        AbstractFilesystem.validatePath(toPath);
        String escapedFrom = FilesystemUtils.shellQuote(fromPath);
        String escapedTo = FilesystemUtils.shellQuote(toPath);
        String cmd = "mkdir -p $(dirname " + escapedTo + ") && mv " + escapedFrom + " " + escapedTo;
        ExecuteResponse result = execute(runtimeContext, cmd, null);
        if (result.exitCode() != 0) {
            return WriteResult.fail(
                    "Error moving '" + fromPath + "' to '" + toPath + "': " + result.output());
        }
        return WriteResult.ok(toPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String escapedPath = FilesystemUtils.shellQuote(path);
        ExecuteResponse result =
                execute(runtimeContext, "test -e " + escapedPath + " && echo yes || echo no", null);
        return result.output() != null && result.output().strip().startsWith("yes");
    }

    private String decodeUtf8Strict(byte[] content) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
    }

    private boolean isMissingFileError(String error) {
        if (error == null) {
            return false;
        }
        String normalized = error.toLowerCase();
        return normalized.contains("no such file")
                || normalized.contains("not found")
                || normalized.contains("file_not_found");
    }
}
