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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Abstract base sandbox implementation with {@link #execute} as the core abstract method.
 *
 * <p>This class provides default implementations for all {@link AbstractFilesystem} methods by
 * delegating
 * to shell commands via {@link #execute}. File listing, grep, and glob use standard Unix
 * commands. Read uses server-side commands for paginated access. Write and edit delegate content
 * transfer to {@link #uploadFiles} and {@link #downloadFiles}.
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
                        + "  if [ -d \"$f\" ]; then "
                        + "    mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0); "
                        + "    printf 'DIR:%s\\t%s\\n' \"$f\" \"$mtime\"; "
                        + "  elif [ -f \"$f\" ]; then "
                        + "    size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0); "
                        + "    mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0); "
                        + "    printf 'FILE:%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; "
                        + "  fi; "
                        + "done 2>/dev/null";

        ExecuteResponse result = execute(runtimeContext, cmd, null);
        List<FileInfo> entries = new ArrayList<>();

        if (result.output() != null && !result.output().isBlank()) {
            for (String line : result.output().strip().split("\n")) {
                if (line.startsWith("DIR:")) {
                    String payload = line.substring(4);
                    String[] parts = payload.split("\t", 2);
                    String dirPath = parts[0];
                    long mtimeMs = parts.length > 1 ? parseEpochSeconds(parts[1]) : 0L;
                    entries.add(FileInfo.ofDir(dirPath, mtimeMs));
                } else if (line.startsWith("FILE:")) {
                    String payload = line.substring(5);
                    String[] parts = payload.split("\t", 3);
                    String filePath = parts[0];
                    long size = parts.length > 1 ? parseLongSafe(parts[1]) : 0L;
                    long mtimeMs = parts.length > 2 ? parseEpochSeconds(parts[2]) : 0L;
                    entries.add(FileInfo.ofFile(filePath, size, mtimeMs));
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
        String escapedParent = FilesystemUtils.shellQuote(parentDirectory(filePath));
        String checkCmd =
                "if [ -e "
                        + escapedPath
                        + " ]; then echo 'EXISTS'; exit 1; fi; "
                        + "mkdir -p "
                        + escapedParent
                        + " 2>&1";

        ExecuteResponse checkResult = execute(runtimeContext, checkCmd, null);
        if (checkResult.exitCode() != null && checkResult.exitCode() != 0) {
            if (checkResult.output() != null && checkResult.output().contains("EXISTS")) {
                return WriteResult.fail(
                        "Cannot write to "
                                + filePath
                                + " because it already exists. Read and then make an"
                                + " edit, or write to a new path.");
            }
            String detail = checkResult.output() != null ? checkResult.output().strip() : "";
            return WriteResult.fail(
                    "Failed to write file '"
                            + filePath
                            + "'"
                            + (detail.isEmpty() ? "" : ": " + detail));
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
        List<FileDownloadResponse> downloads = downloadFiles(runtimeContext, List.of(filePath));
        if (downloads.isEmpty()) {
            return EditResult.fail(
                    "Error editing file '" + filePath + "': download returned no response");
        }

        FileDownloadResponse download = downloads.get(0);
        if (!download.isSuccess() || download.content() == null) {
            if ("file_not_found".equals(download.error())) {
                return EditResult.fail("Error: File '" + filePath + "' not found");
            }
            return EditResult.fail("Error editing file '" + filePath + "': " + download.error());
        }

        String content =
                normalizeLineEndings(new String(download.content(), StandardCharsets.UTF_8));
        String normalizedOld = normalizeLineEndings(oldString);
        String normalizedNew = normalizeLineEndings(newString);
        Object[] replacement =
                FilesystemUtils.performStringReplacement(
                        content, normalizedOld, normalizedNew, replaceAll);
        if (replacement.length == 1) {
            return EditResult.fail((String) replacement[0]);
        }

        String updated = (String) replacement[0];
        int occurrences = (int) replacement[1];
        List<FileUploadResponse> uploads =
                uploadFiles(
                        runtimeContext,
                        List.of(Map.entry(filePath, updated.getBytes(StandardCharsets.UTF_8))));
        if (uploads.isEmpty() || !uploads.get(0).isSuccess()) {
            String error =
                    uploads.isEmpty() ? "upload returned no response" : uploads.get(0).error();
            return EditResult.fail("Error editing file '" + filePath + "': " + error);
        }
        return EditResult.ok(filePath, occurrences);
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        String searchPath = FilesystemUtils.shellQuote(path != null ? path : ".");
        String grepOpts = "-rHnF";
        String globPattern = "";
        if (glob != null && !glob.isBlank()) {
            globPattern = "--include=" + FilesystemUtils.shellQuote(stripRecursivePrefix(glob));
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
        String escapedPattern = FilesystemUtils.shellQuote(stripRecursivePrefix(pattern));

        String cmd =
                "find "
                        + escapedPath
                        + " -type f -name "
                        + escapedPattern
                        + " 2>/dev/null | sort | while IFS= read -r f; do "
                        + "  size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0); "
                        + "  mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0); "
                        + "  printf '%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; "
                        + "done";

        ExecuteResponse result = execute(runtimeContext, cmd, null);
        String output = result.output() != null ? result.output().strip() : "";

        if (output.isEmpty()) {
            return GlobResult.success(List.of());
        }

        List<FileInfo> entries = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length >= 3) {
                String filePath = parts[0].trim();
                long size = parseLongSafe(parts[1]);
                long mtimeMs = parseEpochSeconds(parts[2]);
                entries.add(FileInfo.ofFile(filePath, size, mtimeMs));
            } else {
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

    /**
     * Strips the recursive glob prefix {@code **&#47;} from a pattern so it can be passed to
     * tools like {@code find -name} or {@code grep --include=} that match only the filename
     * portion. For example, {@code **&#47;*.java} becomes {@code *.java}.
     *
     * @param pattern the glob pattern, may be {@code null}
     * @return the pattern with any leading {@code **&#47;} removed, or the original value if absent
     */
    private static String stripRecursivePrefix(String pattern) {
        if (pattern != null && pattern.startsWith("**/")) {
            return pattern.substring(3);
        }
        return pattern;
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long parseEpochSeconds(String s) {
        long epochSec = parseLongSafe(s);
        return epochSec * 1000;
    }

    protected static String parentDirectory(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return ".";
        }
        return slash == 0 ? "/" : path.substring(0, slash);
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace("\r", "\n");
    }
}
