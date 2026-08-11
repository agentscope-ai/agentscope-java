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
package io.agentscope.extensions.sandbox.kubernetes.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxRequestException;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.SandboxConnector;
import io.agentscope.extensions.sandbox.kubernetes.client.model.FileEntry;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/** File operations inside a sandbox via the runtime HTTP API. */
public class Filesystem {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final SandboxConnector connector;

    public Filesystem(SandboxConnector connector) {
        this.connector = connector;
    }

    /**
     * Writes content to a file in the sandbox.
     *
     * @param path relative destination path
     * @param content file bytes
     */
    public void write(String path, byte[] content) {
        write(path, content, DEFAULT_TIMEOUT, false);
    }

    /**
     * Writes content to a file in the sandbox.
     *
     * @param path destination path
     * @param content file bytes
     * @param timeout request timeout
     * @param allowUnsafePaths when false, rejects absolute paths and {@code ..}
     */
    public void write(String path, byte[] content, Duration timeout, boolean allowUnsafePaths) {
        String safePath = allowUnsafePaths ? path : safeUploadPath(path);
        try {
            String boundary = "----AgentScopeSandbox" + System.nanoTime();
            byte[] body = buildMultipartBody(safePath, content, boundary);
            HttpResponse<String> resp =
                    connector.sendRequest(
                            "POST",
                            "upload",
                            HttpRequest.BodyPublishers.ofByteArray(body),
                            "multipart/form-data; boundary=" + boundary,
                            3);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new SandboxRequestException(
                        "Upload failed (status="
                                + resp.statusCode()
                                + "): "
                                + truncate(resp.body(), 256),
                        resp.statusCode());
            }
        } catch (SandboxRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxRequestException("Failed to upload file: " + e.getMessage(), null, e);
        }
    }

    /**
     * Writes string content (UTF-8) to a file in the sandbox.
     *
     * @param path relative destination path
     * @param content text content
     */
    public void write(String path, String content) {
        write(path, content != null ? content.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    /**
     * Reads a file from the sandbox.
     *
     * @param path file path
     * @return file bytes
     */
    public byte[] read(String path) {
        return read(path, DEFAULT_TIMEOUT, false);
    }

    /**
     * Reads a file from the sandbox.
     *
     * @param path file path
     * @param timeout request timeout
     * @param allowUnsafePaths when false, rejects absolute paths and {@code ..}
     * @return file bytes
     */
    public byte[] read(String path, Duration timeout, boolean allowUnsafePaths) {
        String safePath = allowUnsafePaths ? path : safeUploadPath(path);
        try {
            String encoded = percentEncode(safePath);
            HttpResponse<byte[]> resp =
                    connector.sendRequestForBytes("GET", "download/" + encoded, null, null);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new SandboxRequestException(
                        "Download failed (status=" + resp.statusCode() + ") for path: " + path,
                        resp.statusCode());
            }
            return resp.body();
        } catch (SandboxRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxRequestException(
                    "Failed to download file: " + e.getMessage(), null, e);
        }
    }

    /**
     * Lists entries in a directory.
     *
     * @param path directory path
     * @return file entries
     */
    public List<FileEntry> list(String path) {
        return list(path, DEFAULT_TIMEOUT);
    }

    /**
     * Lists entries in a directory.
     *
     * @param path directory path
     * @param timeout request timeout
     * @return file entries
     */
    public List<FileEntry> list(String path, Duration timeout) {
        try {
            String encoded = percentEncode(path);
            HttpResponse<String> resp =
                    connector.sendRequest("GET", "list/" + encoded, null, null, 3);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new SandboxRequestException(
                        "List failed (status=" + resp.statusCode() + ") for path: " + path,
                        resp.statusCode());
            }
            String body = resp.body();
            if (body == null || body.isBlank() || "null".equals(body.trim())) {
                return Collections.emptyList();
            }
            return MAPPER.readValue(body, new TypeReference<List<FileEntry>>() {});
        } catch (SandboxRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxRequestException(
                    "Failed to list directory: " + e.getMessage(), null, e);
        }
    }

    /**
     * Checks whether a path exists in the sandbox.
     *
     * @param path path to check
     * @return true if exists
     */
    public boolean exists(String path) {
        return exists(path, DEFAULT_TIMEOUT);
    }

    /**
     * Checks whether a path exists in the sandbox.
     *
     * @param path path to check
     * @param timeout request timeout
     * @return true if exists
     */
    public boolean exists(String path, Duration timeout) {
        try {
            String encoded = percentEncode(path);
            HttpResponse<String> resp =
                    connector.sendRequest("GET", "exists/" + encoded, null, null, 3);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new SandboxRequestException(
                        "Exists check failed (status=" + resp.statusCode() + ") for path: " + path,
                        resp.statusCode());
            }
            ExistsResult result = MAPPER.readValue(resp.body(), ExistsResult.class);
            return result.exists;
        } catch (SandboxRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxRequestException(
                    "Failed to check path existence: " + e.getMessage(), null, e);
        }
    }

    /**
     * Returns a relative, {@code ..}-free filename safe to send as multipart filename.
     *
     * @param path upload path
     * @return sanitized relative path
     */
    static String safeUploadPath(String path) {
        if (path == null) {
            throw new IllegalArgumentException("Upload path cannot be empty.");
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException(
                        "Upload path contains ASCII control characters: " + path);
            }
        }
        String stripped = path.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("Upload path cannot be empty.");
        }
        String normalized = normalizePosix(stripped);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || ".".equals(normalized)) {
            throw new IllegalArgumentException("Upload path '" + path + "' does not name a file.");
        }
        for (String part : normalized.split("/")) {
            if ("..".equals(part)) {
                throw new IllegalArgumentException(
                        "Upload path '" + path + "' escapes the sandbox root.");
            }
        }
        return normalized;
    }

    private static String normalizePosix(String path) {
        // Simplified posixpath.normpath for relative/absolute paths
        String[] parts = path.split("/");
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        boolean absolute = path.startsWith("/");
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty() && !"..".equals(stack.peekLast())) {
                    stack.removeLast();
                } else if (!absolute) {
                    stack.addLast("..");
                }
            } else {
                stack.addLast(part);
            }
        }
        if (stack.isEmpty()) {
            return absolute ? "/" : ".";
        }
        String joined = String.join("/", stack);
        return absolute ? "/" + joined : joined;
    }

    private static byte[] buildMultipartBody(String filename, byte[] content, String boundary) {
        String prefix =
                "--"
                        + boundary
                        + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\""
                        + filename
                        + "\"\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "\r\n";
        String suffix = "\r\n--" + boundary + "--\r\n";
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] data = content != null ? content : new byte[0];
        byte[] result = new byte[prefixBytes.length + data.length + suffixBytes.length];
        System.arraycopy(prefixBytes, 0, result, 0, prefixBytes.length);
        System.arraycopy(data, 0, result, prefixBytes.length, data.length);
        System.arraycopy(
                suffixBytes, 0, result, prefixBytes.length + data.length, suffixBytes.length);
        return result;
    }

    private static String percentEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ExistsResult {
        @JsonProperty("exists")
        boolean exists;
    }
}
