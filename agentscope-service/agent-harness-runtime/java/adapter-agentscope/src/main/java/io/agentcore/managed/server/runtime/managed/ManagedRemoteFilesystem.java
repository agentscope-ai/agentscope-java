/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Stateless Harness filesystem adapter: standard tool validation runs before remote operations. */
public final class ManagedRemoteFilesystem extends BaseSandboxFilesystem {
    private final ManagedToolRequests requests;

    public ManagedRemoteFilesystem(ManagedToolRequests requests) {
        this.requests = requests;
    }

    @Override
    public String id() {
        return "managed-tool-worker";
    }

    @Override
    public ExecuteResponse execute(RuntimeContext context, String command, Integer timeout) {
        return requests.request(
                context,
                "sandbox.exec",
                Map.of("command", command, "timeout_seconds", timeout == null ? 30 : timeout),
                ExecuteResponse.class);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext context, List<Map.Entry<String, byte[]>> files) {
        return files.stream()
                .map(
                        file ->
                                requests.request(
                                        context,
                                        "fs.upload",
                                        Map.of(
                                                "path",
                                                file.getKey(),
                                                "content",
                                                Base64.getEncoder()
                                                        .encodeToString(file.getValue())),
                                        FileUploadResponse.class))
                .toList();
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext context, List<String> paths) {
        return paths.stream()
                .map(
                        path ->
                                requests.request(
                                        context,
                                        "fs.download",
                                        Map.of("path", path),
                                        FileDownloadResponse.class))
                .toList();
    }
}
