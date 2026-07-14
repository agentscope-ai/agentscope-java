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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceContextMiddlewareSandboxTest {

    private static final RuntimeContext RC =
            RuntimeContext.builder().sessionId("test-session").build();

    @Test
    void sandboxBranch_includesSandboxRootAndId(@TempDir Path workspace) {
        FakeSandboxFilesystem fs = new FakeSandboxFilesystem("sbox-1", "/custom/root");
        WorkspaceManager wm = new WorkspaceManager(workspace, fs);
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RC, "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("Sandbox root: /custom/root"));
        assertTrue(prompt.contains("container id: sbox-1"));
        assertTrue(prompt.contains("upload/download tools"));
        assertTrue(prompt.contains("AGENTS.md defines persona"));
    }

    @Test
    void sandboxBranch_defaultWorkspaceRoot(@TempDir Path workspace) {
        FakeSandboxFilesystem fs = new FakeSandboxFilesystem("sbox-2", null);
        WorkspaceManager wm = new WorkspaceManager(workspace, fs);
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RC, "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("Sandbox root: /workspace"));
    }

    @Test
    void querySandbox_includesOsAndTempdir(@TempDir Path workspace) {
        FakeSandboxFilesystem fs = new FakeSandboxFilesystem("sbox-3", "/workspace");
        fs.osReleaseResponse = new ExecuteResponse("Ubuntu 22.04", 0, false);
        fs.tempdirResponse = new ExecuteResponse("/tmp", 0, false);
        WorkspaceManager wm = new WorkspaceManager(workspace, fs);
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RC, "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("Ubuntu 22.04"));
        assertTrue(prompt.contains("/tmp"));
    }

    @Test
    void querySandbox_fallbackOnNonZeroExit(@TempDir Path workspace) {
        FakeSandboxFilesystem fs = new FakeSandboxFilesystem("sbox-4", "/workspace");
        fs.osReleaseResponse = new ExecuteResponse("", 1, false);
        WorkspaceManager wm = new WorkspaceManager(workspace, fs);
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RC, "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("Operating system:") || prompt.contains("Linux"));
    }

    @Test
    void querySandbox_fallbackOnException(@TempDir Path workspace) {
        FakeSandboxFilesystem fs = new FakeSandboxFilesystem("sbox-5", "/workspace");
        fs.osException = new RuntimeException("sandbox unavailable");
        WorkspaceManager wm = new WorkspaceManager(workspace, fs);
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RC, "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("Linux"));
    }

    @Test
    void querySandbox_emptyOutputFallsBack(@TempDir Path workspace) {
        FakeSandboxFilesystem fs = new FakeSandboxFilesystem("sbox-6", "/workspace");
        fs.tempdirResponse = new ExecuteResponse("", 0, false);
        WorkspaceManager wm = new WorkspaceManager(workspace, fs);
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RC, "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("/tmp"));
    }

    @Test
    void sessionContext_includesSessionInfo(@TempDir Path workspace) {
        FakeSandboxFilesystem fs = new FakeSandboxFilesystem("sbox-7", "/workspace");
        WorkspaceManager wm = new WorkspaceManager(workspace, fs);
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RC, "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("Session Context"));
        assertTrue(prompt.contains("Session ID: test-session"));
        assertFalse(prompt.contains("AgentStateStore"));
    }

    private static final class FakeSandboxFilesystem extends BaseSandboxFilesystem {

        private final String id;
        private final String workspaceRoot;

        ExecuteResponse osReleaseResponse;
        ExecuteResponse unameResponse;
        ExecuteResponse tempdirResponse;
        RuntimeException osException;

        FakeSandboxFilesystem(String id, String workspaceRoot) {
            this.id = id;
            this.workspaceRoot = workspaceRoot;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String getWorkspaceRoot() {
            return workspaceRoot != null ? workspaceRoot : "/workspace";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            if (command.contains("/etc/os-release")) {
                if (osException != null) {
                    throw osException;
                }
                return osReleaseResponse != null
                        ? osReleaseResponse
                        : new ExecuteResponse("Linux 6.2", 0, false);
            }
            if (command.contains("uname")) {
                if (osException != null) {
                    throw osException;
                }
                return unameResponse != null
                        ? unameResponse
                        : new ExecuteResponse("Linux", 0, false);
            }
            if (command.contains("TMPDIR")) {
                return tempdirResponse != null
                        ? tempdirResponse
                        : new ExecuteResponse("/tmp", 0, false);
            }
            return new ExecuteResponse("", 0, false);
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            return List.of();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            return List.of();
        }
    }
}
