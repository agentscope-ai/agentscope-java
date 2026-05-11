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
import io.agentscope.harness.agent.store.NamespaceFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Abstract base sandbox implementation with {@link #execute} as the core abstract method.
 *
 * <p>This class provides default implementations for all {@link AbstractFilesystem} methods by
 * delegating
 * to shell commands via {@link #execute}. File listing, grep, and glob use standard Unix
 * commands. Read uses server-side commands for paginated access. Write delegates content
 * transfer to {@link #uploadFiles}. Edit uses server-side commands for string replacement.
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

    private NamespaceFactory namespaceFactory;

    protected NamespaceFactory getNamespaceFactory() {
        return namespaceFactory;
    }

    protected void setNamespaceFactory(NamespaceFactory namespaceFactory) {
        this.namespaceFactory = namespaceFactory;
    }

    protected String namespacedPath(String path) {
        if (namespaceFactory == null || path == null || path.isBlank()) {
            return path;
        }
        List<String> ns = namespaceFactory.getNamespace();
        if (ns == null || ns.isEmpty()) {
            return path;
        }
        String prefix = String.join("/", ns);
        if (path.startsWith("/")) {
            return "/" + prefix + path;
        }
        return prefix + "/" + path;
    }

    @Override
    public abstract String id();

    @Override
    public abstract ExecuteResponse execute(String command, Integer timeoutSeconds);

    @Override
    public abstract List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files);

    @Override
    public abstract List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths);

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        String escapedPath = FilesystemUtils.shellQuote(namespacedPath(path));
        String cmd =
                "for f in "
                        + escapedPath
                        + "/*; do "
                        + "  if [ -d \"$f\" ]; then echo \"DIR:$f\"; "
                        + "  elif [ -f \"$f\" ]; then echo \"FILE:$f\"; fi; "
                        + "done 2>/dev/null";

        ExecuteResponse result = execute(cmd, null);
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
        String nsPath = namespacedPath(filePath);
        String fileType = FilesystemUtils.getFileType(nsPath);
        String escapedPath = FilesystemUtils.shellQuote(nsPath);

        if (!"text".equals(fileType)) {
            String cmd = "base64 " + escapedPath + " 2>/dev/null";
            ExecuteResponse result = execute(cmd, null);
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

        ExecuteResponse result = execute(cmd, null);
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
        String nsPath = namespacedPath(filePath);
        String escapedPath = FilesystemUtils.shellQuote(nsPath);
        String checkCmd =
                "if [ -e "
                        + escapedPath
                        + " ]; then echo 'EXISTS'; exit 1; fi; "
                        + "mkdir -p \"$(dirname "
                        + escapedPath
                        + ")\" 2>&1";

        ExecuteResponse checkResult = execute(checkCmd, null);
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
                                        nsPath,
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
        String nsPath = namespacedPath(filePath);
        String payload =
                "{\"path\":\""
                        + jsonEscape(nsPath)
                        + "\","
                        + "\"old\":\""
                        + jsonEscape(oldString)
                        + "\","
                        + "\"new\":\""
                        + jsonEscape(newString)
                        + "\","
                        + "\"replace_all\":"
                        + replaceAll
                        + "}";
        String payloadB64 =
                Base64.getEncoder()
                        .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String cmd =
                "python3 -c \"import sys, os, base64, json\\n"
                    + "payload ="
                    + " json.loads(base64.b64decode(sys.stdin.read().strip()).decode('utf-8'))\\n"
                    + "path, old, new = payload['path'], payload['old'], payload['new']\\n"
                    + "replace_all = payload.get('replace_all', False)\\n"
                    + "if not os.path.isfile(path):\\n"
                    + "    print(json.dumps({'error': 'file_not_found'}))\\n"
                    + "    sys.exit(0)\\n"
                    + "with open(path, 'rb') as f: text = f.read().decode('utf-8')\\n"
                    + "count = text.count(old)\\n"
                    + "if count == 0:\\n"
                    + "    print(json.dumps({'error': 'string_not_found'}))\\n"
                    + "    sys.exit(0)\\n"
                    + "if count > 1 and not replace_all:\\n"
                    + "    print(json.dumps({'error': 'multiple_occurrences', 'count': count}))\\n"
                    + "    sys.exit(0)\\n"
                    + "result = text.replace(old, new) if replace_all else text.replace(old, new,"
                    + " 1)\\n"
                    + "with open(path, 'wb') as f: f.write(result.encode('utf-8'))\\n"
                    + "print(json.dumps({'count': count}))\\n"
                    + "\" 2>&1 <<'__EDIT_EOF__'\n"
                        + payloadB64
                        + "\n__EDIT_EOF__\n";

        ExecuteResponse result = execute(cmd, null);
        String output = result.output() != null ? result.output().strip() : "";

        if (output.contains("\"error\"")) {
            if (output.contains("file_not_found")) {
                return EditResult.fail("Error: File '" + filePath + "' not found");
            }
            if (output.contains("string_not_found")) {
                return EditResult.fail("Error: String not found in file: '" + oldString + "'");
            }
            if (output.contains("multiple_occurrences")) {
                return EditResult.fail(
                        "Error: String '"
                                + oldString
                                + "' appears multiple times. Use replaceAll=true to replace all"
                                + " occurrences.");
            }
            return EditResult.fail("Error editing file '" + filePath + "': " + output);
        }

        if (output.contains("\"count\"")) {
            try {
                int countIdx = output.indexOf("\"count\":") + 8;
                int endIdx = output.indexOf('}', countIdx);
                int count = Integer.parseInt(output.substring(countIdx, endIdx).trim());
                return EditResult.ok(filePath, count);
            } catch (NumberFormatException e) {
                return EditResult.ok(filePath, 1);
            }
        }

        return EditResult.fail(
                "Error editing file '"
                        + filePath
                        + "': unexpected server response: "
                        + output.substring(0, Math.min(200, output.length())));
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        String nsPath = path != null ? namespacedPath(path) : ".";
        String searchPath = FilesystemUtils.shellQuote(nsPath);
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

        ExecuteResponse result = execute(cmd, null);
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
        String effectivePath = path != null ? namespacedPath(path) : "/";
        String escapedPath = FilesystemUtils.shellQuote(effectivePath);
        String escapedPattern = FilesystemUtils.shellQuote(pattern);

        String cmd =
                "find " + escapedPath + " -type f -name " + escapedPattern + " 2>/dev/null | sort";

        ExecuteResponse result = execute(cmd, null);
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
        String effectivePath = namespacedPath(path);
        String escapedPath = FilesystemUtils.shellQuote(effectivePath);
        String cmd = "rm -rf " + escapedPath;
        ExecuteResponse result = execute(cmd, null);
        if (result.exitCode() != 0) {
            return WriteResult.fail("Error deleting '" + path + "': " + result.output());
        }
        return WriteResult.ok(path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        AbstractFilesystem.validatePath(fromPath);
        AbstractFilesystem.validatePath(toPath);
        String from = namespacedPath(fromPath);
        String to = namespacedPath(toPath);
        String escapedFrom = FilesystemUtils.shellQuote(from);
        String escapedTo = FilesystemUtils.shellQuote(to);
        String cmd = "mkdir -p $(dirname " + escapedTo + ") && mv " + escapedFrom + " " + escapedTo;
        ExecuteResponse result = execute(cmd, null);
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
        String effectivePath = namespacedPath(path);
        String escapedPath = FilesystemUtils.shellQuote(effectivePath);
        ExecuteResponse result =
                execute("test -e " + escapedPath + " && echo yes || echo no", null);
        return result.output() != null && result.output().strip().startsWith("yes");
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
