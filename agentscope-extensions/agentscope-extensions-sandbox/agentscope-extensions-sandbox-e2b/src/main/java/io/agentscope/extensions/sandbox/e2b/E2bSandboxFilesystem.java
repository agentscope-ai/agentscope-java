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
package io.agentscope.extensions.sandbox.e2b;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An E2B-optimized filesystem that uses the Filesystem REST API for upload/download, avoiding
 * base64 overhead and the 512 KB output truncation of shell-based transfers.
 */
public class E2bSandboxFilesystem extends SandboxBackedFilesystem {

    private static final Logger log = LoggerFactory.getLogger(E2bSandboxFilesystem.class);

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        Sandbox s = getSandbox();
        if (!(s instanceof E2bSandbox e2b)) {
            return super.uploadFiles(runtimeContext, files);
        }
        List<FileUploadResponse> results = new ArrayList<>(files.size());
        for (Map.Entry<String, byte[]> file : files) {
            String path = file.getKey();
            try {
                String absPath = resolveAbsolute(path, e2b.getWorkspaceRoot());
                e2b.uploadFile(absPath, file.getValue());
                results.add(FileUploadResponse.success(path));
            } catch (Exception e) {
                log.warn("[e2b-sandbox-fs] upload failed for path: {}", path, e);
                results.add(FileUploadResponse.fail(path, e.getMessage()));
            }
        }
        return results;
    }

    private static String resolveAbsolute(String path, String workspaceRoot) {
        if (path == null || path.isEmpty()) return path;
        if (path.startsWith("/")) return path;
        return workspaceRoot + (workspaceRoot.endsWith("/") ? "" : "/") + path;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        Sandbox s = getSandbox();
        if (!(s instanceof E2bSandbox e2b)) {
            return super.downloadFiles(runtimeContext, paths);
        }
        List<FileDownloadResponse> results = new ArrayList<>(paths.size());
        for (String path : paths) {
            try {
                String absPath = resolveAbsolute(path, e2b.getWorkspaceRoot());
                byte[] content = e2b.downloadFile(absPath);
                results.add(FileDownloadResponse.success(path, content));
            } catch (Exception e) {
                log.warn("[e2b-sandbox-fs] download failed for path: {}", path, e);
                results.add(FileDownloadResponse.fail(path, e.getMessage()));
            }
        }
        return results;
    }
}
