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

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
     * Static Python helper executed via heredoc. User content never enters the command string
     * in raw form: {@code old}/{@code new} arrive base64-encoded (an alphabet with no shell
     * metacharacters), {@code sys.argv} carries the target path plus those encoded blobs, and
     * the script body is a constant. Output is a single {@code __RESULT__{json}} line so parsing
     * never touches user content.
     */
    private static final String EDIT_SCRIPT =
            """
            import base64, os, sys, json, uuid
            target, old_b64, new_b64 = sys.argv[1], sys.argv[2], sys.argv[3]
            replace_all = sys.argv[4] == "true"
            def result(obj):
                print("__RESULT__" + json.dumps(obj))
            if not os.path.isfile(target):
                result({"error": "file_not_found"})
                sys.exit(0)
            try:
                old = base64.b64decode(old_b64).decode("utf-8")
                new = base64.b64decode(new_b64).decode("utf-8")
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
            # uuid4 keeps the temp name unique per edit even when concurrent edits
            # share a long-lived shell process (os.getpid() would not be unique).
            real = os.path.realpath(target)
            st = os.stat(real)
            tmp_out = real + ".agentscope-edit-tmp-" + uuid.uuid4().hex
            try:
                # Failure here (including a read-only directory that forbids creating
                # the temp file, or os.replace failing) leaves the target untouched:
                # nothing is written to `real` until the atomic rename succeeds, and a
                # non-atomic copy fallback would reintroduce the truncate-on-failure
                # risk. Java then retries via the transfer path, whose shell
                # redirection needs only file write permission.
                with open(tmp_out, "wb") as f:
                    f.write(out_bytes)
                os.chmod(tmp_out, st.st_mode)
                os.replace(tmp_out, real)
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

    /**
     * Upper bound on the whole command string, in bytes.
     *
     * <p>The command is handed to the backend as a single argv element ({@code sh -c '<cmd>'}), so
     * the kernel's {@code MAX_ARG_STRLEN} (32 pages = 128 KiB on Linux) applies to it in total,
     * not per argument. The script body, the target path and the quoting all draw from the same
     * budget as the two base64 payloads, hence the whole-string check. 112 KiB leaves 16 KiB of
     * headroom below the kernel limit; measured E2BIG starts at 128 KiB.
     */
    private static final int MAX_INLINE_COMMAND_BYTES = 112 * 1024;

    private static final ObjectMapper NATIVE_RESULT_MAPPER = new ObjectMapper();

    /**
     * Whether a {@code __RESULT__} payload reports {@code write_failed}, i.e. the atomic write
     * did not touch the target and the transfer path should retry.
     */
    private static boolean isWriteFailed(String payload) {
        try {
            NativeScriptResult parsed =
                    NATIVE_RESULT_MAPPER.readValue(payload.trim(), NativeScriptResult.class);
            return parsed != null && "write_failed".equals(parsed.error());
        } catch (Exception e) {
            // Unparseable payloads are reported by mapNativeResult instead.
            return false;
        }
    }

    /**
     * Edits the file by replacing {@code oldString} with {@code newString} in the sandbox.
     *
     * <p>Runs a static inline Python script in the sandbox, with {@code old}/{@code new} passed
     * base64-encoded on the command line (the base64 alphabet has no shell metacharacters, so
     * user content cannot break out of its argument). The file itself never crosses the
     * boundary. Falls back to download → Java replacement → re-upload when {@code python3} is
     * missing or the native write fails.
     *
     * <p>Arguments so large that the command would exceed {@link #MAX_INLINE_COMMAND_BYTES} are
     * rejected with a diagnostic instead of being transferred: {@code old}/{@code new} are edit
     * fragments, so an oversized request is a sign it should be split into several edits.
     *
     * <p>Contract: the edit is performed through the resolved target ({@code realpath}), so
     * editing a symlink writes through to the file it points at and the symlink itself is
     * preserved. The native path writes only via a same-directory temp file + atomic rename,
     * so a {@code write_failed} result means the file is unchanged; {@code edit()} then
     * retries through the transfer path, whose shell redirection needs only file write
     * permission.
     *
     * @param runtimeContext per-call agent context; may be {@code null}
     * @param filePath path of the file to edit
     * @param oldString literal string to find; must be non-empty
     * @param newString replacement string; must not be {@code null} — pass an empty string to
     *        delete the matched text (this keeps the normal replacement semantics)
     * @param replaceAll replace every occurrence instead of only the first
     * @return {@link EditResult#ok} with the occurrence count, or {@link EditResult#fail}
     */
    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        EditResult invalid = FilesystemUtils.validateEditArguments(filePath, oldString, newString);
        if (invalid != null) {
            return invalid;
        }

        String cmd = buildNativeEditCommand(filePath, oldString, newString, replaceAll);
        if (cmd == null) {
            int kib =
                    commandSizeBytes(nativeEditCommand(filePath, oldString, newString, replaceAll))
                            / 1024;
            return EditResult.fail(
                    "Error: edit arguments are too large ("
                            + kib
                            + " KiB encoded, limit "
                            + (MAX_INLINE_COMMAND_BYTES / 1024)
                            + " KiB). Split the edit into smaller pieces.");
        }

        ExecuteResponse execResult;
        try {
            execResult = execute(runtimeContext, cmd, null);
        } catch (Exception e) {
            log.warn("[sandbox-fs] native edit execute failed, falling back to transfer", e);
            return editViaTransfer(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        String output = execResult.output() != null ? execResult.output() : "";
        int marker = output.indexOf(EDIT_RESULT_MARKER);
        if (marker >= 0) {
            String payload = output.substring(marker + EDIT_RESULT_MARKER.length());
            if (isWriteFailed(payload)) {
                // The native path writes target only via a same-directory temp + rename,
                // so a failure here means target is untouched. Retry through the transfer
                // path, whose shell redirection needs only file write permission and can
                // succeed where a read-only directory blocked the temp file.
                log.warn(
                        "[sandbox-fs] native edit write failed ({}), falling back to transfer",
                        payload.trim());
                return editViaTransfer(runtimeContext, filePath, oldString, newString, replaceAll);
            }
            return mapNativeResult(filePath, oldString, payload);
        }
        if (isPythonMissing(execResult)) {
            return editViaTransfer(runtimeContext, filePath, oldString, newString, replaceAll);
        }
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

    /**
     * Builds the native edit command, or returns {@code null} when it would exceed
     * {@link #MAX_INLINE_COMMAND_BYTES}. {@code old}/{@code new} travel base64-encoded: the
     * alphabet has no shell metacharacters, so they need no quoting and cannot break out of the
     * single-quoted argument.
     */
    private static String buildNativeEditCommand(
            String filePath, String oldString, String newString, boolean replaceAll) {
        String cmd = nativeEditCommand(filePath, oldString, newString, replaceAll);
        return commandSizeBytes(cmd) > MAX_INLINE_COMMAND_BYTES ? null : cmd;
    }

    private static String nativeEditCommand(
            String filePath, String oldString, String newString, boolean replaceAll) {
        return "python3 - "
                + FilesystemUtils.shellQuote(filePath)
                + " "
                + FilesystemUtils.shellQuote(encodeBase64(oldString))
                + " "
                + FilesystemUtils.shellQuote(encodeBase64(newString))
                + " "
                + (replaceAll ? "true" : "false")
                + " <<'"
                + EDIT_HEREDOC_DELIMITER
                + "'\n"
                + EDIT_SCRIPT
                + EDIT_HEREDOC_DELIMITER
                + "\n__ec=$?; exit $__ec";
    }

    private static String encodeBase64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int commandSizeBytes(String cmd) {
        return cmd.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean isPythonMissing(ExecuteResponse response) {
        // 127 = command not found, 126 = found but not executable (e.g. python3 present but
        // with the execute bit cleared). Both mean the native path cannot run.
        if (response.exitCode() != null
                && (response.exitCode() == 127 || response.exitCode() == 126)) {
            return true;
        }
        String out = response.output() != null ? response.output().toLowerCase() : "";
        return out.contains("python3")
                && (out.contains("not found")
                        || out.contains("no such")
                        || out.contains("permission denied")
                        || out.contains("not executable"));
    }

    private static EditResult mapNativeResult(String filePath, String oldString, String json) {
        // Parse with a real JSON parser rather than substring matching: the "detail" value
        // embeds exception text that may itself contain user-controlled paths, so scanning the
        // raw payload can misclassify (e.g. a path containing "string_not_found").
        NativeScriptResult parsed;
        try {
            parsed = NATIVE_RESULT_MAPPER.readValue(json.trim(), NativeScriptResult.class);
        } catch (Exception e) {
            log.warn("[sandbox-fs] unparseable native edit result: {}", json.trim(), e);
            return EditResult.fail("Error editing file '" + filePath + "': " + json.trim());
        }
        if (parsed == null) {
            return EditResult.fail("Error editing file '" + filePath + "': " + json.trim());
        }
        String error = parsed.error();
        if ("multiple_occurrences".equals(error)) {
            int count = parsed.count() != null ? parsed.count() : 1;
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
        if (error != null) {
            // read_failed / write_failed and any future token, with the script's detail.
            return EditResult.fail("Error editing file '" + filePath + "': " + json.trim());
        }
        if (parsed.count() != null) {
            return EditResult.ok(filePath, parsed.count());
        }
        return EditResult.fail("Error editing file '" + filePath + "': " + json.trim());
    }

    /** The {@code __RESULT__} payload the sandbox script prints on a single line. */
    private record NativeScriptResult(String error, Integer count, String detail) {}

    /** Decode bytes as UTF-8, failing on malformed input instead of substituting. */
    private static String decodeUtf8Strict(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * Fallback when {@code python3} is unavailable: download, replace in Java, re-upload.
     *
     * <p>Unlike the native path, symlink handling here is delegated to {@link #uploadFiles}:
     * this method re-uploads against {@code filePath} as given (it does not resolve
     * {@code realpath}), so a backend whose upload writes through the link preserves it, while
     * a backend that replaces the path may replace the symlink itself. The native path
     * guarantees preservation; this fallback does not.
     */
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
        // Fail on malformed UTF-8 instead of silently replacing bytes: the native Python
        // path decodes strictly, so the fallback must not quietly re-encode a legacy-encoding
        // file it cannot represent.
        String content;
        try {
            content = decodeUtf8Strict(contentBytes);
        } catch (CharacterCodingException e) {
            return EditResult.fail(
                    "Error editing file '" + filePath + "': file is not valid UTF-8");
        }

        FilesystemUtils.ReplacementResult result =
                FilesystemUtils.stringReplacement(content, oldString, newString, replaceAll);

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
