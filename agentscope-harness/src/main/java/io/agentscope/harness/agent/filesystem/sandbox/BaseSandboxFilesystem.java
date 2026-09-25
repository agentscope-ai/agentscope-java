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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base sandbox implementation with {@link #execute} as the core abstract method.
 *
 * <p>This class provides default implementations for all {@link AbstractFilesystem} methods by
 * delegating
 * to shell commands via {@link #execute}. File listing, grep, and glob use standard Unix
 * commands. Read uses server-side commands for paginated access. Write delegates content
 * transfer to {@link #uploadFiles}. Edit runs a static inline Python script inside the sandbox
 * ({@code old}/{@code new} cross the boundary as files, the command line carries paths only),
 * falling back to download via {@link #downloadFiles}, Java replacement via
 * {@link io.agentscope.harness.agent.filesystem.util.FilesystemUtils}, and re-upload via
 * {@link #uploadFiles} when {@code python3} is unavailable.
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

    private static final Logger log = LoggerFactory.getLogger(BaseSandboxFilesystem.class);

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
                "if [ ! -e "
                        + escapedPath
                        + " ]; then echo '__NOT_EXISTS__'; "
                        + "elif [ ! -d "
                        + escapedPath
                        + " ]; then echo '__NOT_A_DIR__'; "
                        + "else for f in "
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
                        + "done; fi";

        ExecuteResponse result = execute(runtimeContext, cmd, null);
        if (!result.isSuccess()) {
            return LsResult.fail(executeFailureMessage(result, "listing", path));
        }
        String output = result.output() != null ? result.output().strip() : "";

        if ("__NOT_EXISTS__".equals(output)) {
            return LsResult.fail("Path does not exist: " + path);
        }
        if ("__NOT_A_DIR__".equals(output)) {
            return LsResult.fail("Not a directory: " + path);
        }

        List<FileInfo> entries = new ArrayList<>();

        if (!output.isBlank()) {
            for (String line : output.split("\n")) {
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
            if (!result.isSuccess()) {
                // Positive exit codes other than 124 (the timeout(1) convention — the command
                // did not complete) mean the command ran and base64 could not read the file;
                // stderr is discarded, so report the designed file_not_found signal instead of
                // an empty error.
                boolean commandRanAndFailedToRead =
                        result.exitCode() != null
                                && result.exitCode() > 0
                                && result.exitCode() != 124;
                return commandRanAndFailedToRead
                        ? ReadResult.fail("File '" + filePath + "': file_not_found")
                        : ReadResult.fail(executeFailureMessage(result, "reading", filePath));
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
        if (!result.isSuccess()) {
            return ReadResult.fail(executeFailureMessage(result, "reading", filePath));
        }
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
                        List.of(Map.entry(filePath, content.getBytes(StandardCharsets.UTF_8))));
        if (responses.isEmpty() || !responses.get(0).isSuccess()) {
            String err =
                    responses.isEmpty() ? "upload returned no response" : responses.get(0).error();
            return WriteResult.fail("Failed to write file '" + filePath + "': " + err);
        }

        return WriteResult.ok(filePath);
    }

    /**
     * Static Python helper executed via heredoc. User content never enters the command string:
     * {@code old}/{@code new} arrive as files, {@code sys.argv} carries paths only. Output is a
     * single {@code __RESULT__{json}} line so parsing never touches user content.
     */
    private static final String EDIT_SCRIPT =
            """
            import os, sys, json
            target, old_file, new_file = sys.argv[1], sys.argv[2], sys.argv[3]
            replace_all = sys.argv[4] == "true"
            def result(obj):
                print("__RESULT__" + json.dumps(obj))
            if not os.path.isfile(target):
                result({"error": "file_not_found"})
                sys.exit(0)
            try:
                with open(old_file, "rb") as f:
                    old = f.read().decode("utf-8")
                with open(new_file, "rb") as f:
                    new = f.read().decode("utf-8")
            except Exception as e:
                result({"error": "read_failed", "detail": str(e)})
                sys.exit(0)
            if old == "":
                result({"error": "string_not_found"})
                sys.exit(0)
            try:
                with open(target, "rb") as f:
                    text = f.read().decode("utf-8")
            except Exception as e:
                result({"error": "read_failed", "detail": str(e)})
                sys.exit(0)
            if len(text) == 0:
                result({"error": "empty"})
                sys.exit(0)
            count = text.count(old)
            if count == 0:
                result({"error": "string_not_found"})
                sys.exit(0)
            if count > 1 and not replace_all:
                result({"error": "multiple_occurrences", "count": count})
                sys.exit(0)
            out = text.replace(old, new) if replace_all else text.replace(old, new, 1)
            out_bytes = out.encode("utf-8")
            # realpath so os.replace writes through symlinks (replacing the link
            # itself would break workspace symlinks) and stays on target's mount.
            real = os.path.realpath(target)
            st = os.stat(real)
            tmp_out = real + ".agentscope-edit-tmp-" + str(os.getpid())
            try:
                with open(tmp_out, "wb") as f:
                    f.write(out_bytes)
                os.chmod(tmp_out, st.st_mode)
                try:
                    os.replace(tmp_out, real)
                except OSError:
                    import shutil
                    shutil.copyfile(tmp_out, real)
                    os.remove(tmp_out)
            except OSError:
                # Directory not writable for a new temp file — fall back to
                # writing the existing file in place (previous behavior).
                try:
                    if os.path.exists(tmp_out):
                        os.remove(tmp_out)
                except Exception:
                    pass
                try:
                    with open(real, "wb") as f:
                        f.write(out_bytes)
                except Exception as e:
                    result({"error": "write_failed", "detail": str(e)})
                    sys.exit(0)
            except Exception as e:
                try:
                    if os.path.exists(tmp_out):
                        os.remove(tmp_out)
                except Exception:
                    pass
                result({"error": "write_failed", "detail": str(e)})
                sys.exit(0)
            result({"count": count})
            """;

    private static final String EDIT_HEREDOC_DELIMITER = "__AGENTSCOPE_EDIT_PY__";

    private static final String EDIT_RESULT_MARKER = "__RESULT__";

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        if (oldString == null || oldString.isEmpty()) {
            return EditResult.fail("Error: String not found in file: '" + oldString + "'");
        }
        // A null newString means deletion: fallback path inserts "" (previously replaceAll
        // threw NPE and non-replaceAll inserted the literal "null"). Documented behavior.
        String safeNew = newString == null ? "" : newString;

        String tmpDir = "/tmp/agentscope-edit-" + UUID.randomUUID().toString().substring(0, 8);
        String oldPath = tmpDir + "/old.bin";
        String newPath = tmpDir + "/new.bin";

        List<FileUploadResponse> params =
                uploadFiles(
                        runtimeContext,
                        List.of(
                                Map.entry(oldPath, oldString.getBytes(StandardCharsets.UTF_8)),
                                Map.entry(newPath, safeNew.getBytes(StandardCharsets.UTF_8))));
        if (params.size() < 2 || !params.get(0).isSuccess() || !params.get(1).isSuccess()) {
            String err = "upload returned no response";
            for (FileUploadResponse r : params) {
                if (!r.isSuccess()) {
                    err = r.error();
                    break;
                }
            }
            log.warn("[sandbox-fs] edit param upload failed ({}), falling back to transfer", err);
            executeCleanup(runtimeContext, tmpDir);
            return editViaTransfer(runtimeContext, filePath, oldString, safeNew, replaceAll);
        }

        String cmd =
                "python3 - "
                        + FilesystemUtils.shellQuote(filePath)
                        + " "
                        + FilesystemUtils.shellQuote(oldPath)
                        + " "
                        + FilesystemUtils.shellQuote(newPath)
                        + " "
                        + (replaceAll ? "true" : "false")
                        + " <<'"
                        + EDIT_HEREDOC_DELIMITER
                        + "'\n"
                        + EDIT_SCRIPT
                        + EDIT_HEREDOC_DELIMITER
                        + "\n__ec=$?; rm -rf "
                        + FilesystemUtils.shellQuote(tmpDir)
                        + "; exit $__ec";

        ExecuteResponse execResult;
        try {
            execResult = execute(runtimeContext, cmd, null);
        } catch (Exception e) {
            log.warn("[sandbox-fs] native edit execute failed, falling back to transfer", e);
            executeCleanup(runtimeContext, tmpDir);
            return editViaTransfer(runtimeContext, filePath, oldString, safeNew, replaceAll);
        }
        String output = execResult.output() != null ? execResult.output() : "";
        int marker = output.indexOf(EDIT_RESULT_MARKER);
        if (marker >= 0) {
            return mapNativeResult(
                    filePath, oldString, output.substring(marker + EDIT_RESULT_MARKER.length()));
        }
        if (isPythonMissing(execResult)) {
            executeCleanup(runtimeContext, tmpDir);
            return editViaTransfer(runtimeContext, filePath, oldString, safeNew, replaceAll);
        }
        executeCleanup(runtimeContext, tmpDir);
        String stripped = output.strip();
        String excerpt = stripped.substring(0, Math.min(200, stripped.length()));
        return EditResult.fail(
                "Error editing file '"
                        + filePath
                        + "' (exitCode="
                        + execResult.exitCode()
                        + "): unexpected server response: "
                        + excerpt);
    }

    private void executeCleanup(RuntimeContext runtimeContext, String tmpDir) {
        try {
            execute(runtimeContext, "rm -rf " + FilesystemUtils.shellQuote(tmpDir), null);
        } catch (Exception ignored) {
            // best effort only
        }
    }

    private static boolean isPythonMissing(ExecuteResponse response) {
        if (response.exitCode() != null && response.exitCode() == 127) {
            return true;
        }
        String out = response.output() != null ? response.output().toLowerCase() : "";
        return out.contains("python3") && (out.contains("not found") || out.contains("no such"));
    }

    private static EditResult mapNativeResult(String filePath, String oldString, String json) {
        // Parse the "error" field exactly: the "detail" value embeds exception text that may
        // itself contain user-controlled paths, so substring matching on the whole payload can
        // misclassify (e.g. a path containing "string_not_found").
        String error = parseErrorToken(json);
        if ("multiple_occurrences".equals(error)) {
            int count = parseCount(json);
            if (count > 1) {
                return EditResult.fail(
                        "Error: String '"
                                + oldString
                                + "' appears "
                                + count
                                + " times in file. "
                                + "Use replaceAll=true to replace all instances, or provide a more"
                                + " specific string with surrounding context.");
            }
            return EditResult.fail(
                    "Error: String '"
                            + oldString
                            + "' appears multiple times. Use replaceAll=true to replace all"
                            + " occurrences.");
        }
        if ("string_not_found".equals(error)) {
            return EditResult.fail("Error: String not found in file: '" + oldString + "'");
        }
        if ("file_not_found".equals(error)) {
            return EditResult.fail("Error: File '" + filePath + "' not found");
        }
        if ("empty".equals(error)) {
            return EditResult.fail("Error: File '" + filePath + "' is empty");
        }
        if ("read_failed".equals(error) || "write_failed".equals(error)) {
            return EditResult.fail("Error editing file '" + filePath + "': " + json.trim());
        }
        if (error != null) {
            return EditResult.fail("Error editing file '" + filePath + "': " + json.trim());
        }
        if (json.contains("\"count\"")) {
            return EditResult.ok(filePath, parseCount(json));
        }
        return EditResult.fail("Error editing file '" + filePath + "': " + json.trim());
    }

    /** Extract the value of the top-level {@code "error"} field, or {@code null} if absent. */
    private static String parseErrorToken(String json) {
        int key = json.indexOf("\"error\"");
        if (key < 0) {
            return null;
        }
        int colon = json.indexOf(':', key);
        if (colon < 0) {
            return null;
        }
        int open = json.indexOf('"', colon);
        if (open < 0) {
            return null;
        }
        int close = json.indexOf('"', open + 1);
        if (close < 0) {
            return null;
        }
        return json.substring(open + 1, close);
    }

    private static int parseCount(String json) {
        int idx = json.indexOf("\"count\"");
        if (idx < 0) {
            return 1;
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return 1;
        }
        int start = colon + 1;
        while (start < json.length() && !Character.isDigit(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (start >= end) {
            return 1;
        }
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Fallback when {@code python3} is unavailable: download, replace in Java, re-upload. */
    private EditResult editViaTransfer(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        List<FileDownloadResponse> downloaded = downloadFiles(runtimeContext, List.of(filePath));
        if (downloaded.isEmpty() || !downloaded.get(0).isSuccess()) {
            return EditResult.fail("Error: File '" + filePath + "' not found");
        }
        byte[] contentBytes = downloaded.get(0).content();
        if (contentBytes == null || contentBytes.length == 0) {
            return EditResult.fail("Error: File '" + filePath + "' is empty");
        }
        String content = new String(contentBytes, StandardCharsets.UTF_8);

        FilesystemUtils.ReplacementResult result =
                FilesystemUtils.performStringReplacement(content, oldString, newString, replaceAll);

        if (!result.isSuccess()) {
            return EditResult.fail(result.error());
        }

        String newContent = result.content();
        int occurrences = result.occurrences();

        List<FileUploadResponse> uploaded =
                uploadFiles(
                        runtimeContext,
                        List.of(Map.entry(filePath, newContent.getBytes(StandardCharsets.UTF_8))));
        if (uploaded.isEmpty() || !uploaded.get(0).isSuccess()) {
            String err =
                    uploaded.isEmpty() ? "upload returned no response" : uploaded.get(0).error();
            return EditResult.fail("Error writing edited file '" + filePath + "': " + err);
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
        if (!result.isSuccess()) {
            return GrepResult.fail(
                    executeFailureMessage(result, "searching", path != null ? path : "."));
        }
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
        if (!result.isSuccess()) {
            return GlobResult.fail(
                    executeFailureMessage(result, "globbing", path != null ? path : "/"));
        }
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
     * Builds the failure message for a non-successful {@link #execute} response, prefixing the
     * operation context and falling back to the exit code when the response carries no
     * diagnostic output.
     */
    private static String executeFailureMessage(
            ExecuteResponse result, String operation, String target) {
        String detail =
                result.output() != null && !result.output().isBlank()
                        ? result.output()
                        : "exit code " + result.exitCode();
        return "Error " + operation + " '" + target + "': " + detail;
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
}
