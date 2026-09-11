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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.InputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ensures the {@code ## Workspace} paragraph advertises the sandbox's real workspace root
 * (from {@link SandboxState#getWorkspaceRoot()}) instead of the hard-coded {@code /workspace}.
 */
class WorkspaceContextMiddlewareSandboxRootTest {

    @TempDir Path workspace;

    private WorkspaceManager wm;

    @AfterEach
    void tearDown() {
        if (wm != null) {
            wm.close();
        }
    }

    @Test
    @DisplayName("Sandbox prompt advertises the configured non-default workspace root")
    void advertisesConfiguredRoot() {
        SandboxBackedFilesystem fs = fsWithWorkspaceRoot("/home/agentscope/workspace");
        wm = new WorkspaceManager(workspace, fs);
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();

        assertNotNull(prompt);
        assertTrue(
                prompt.contains("Sandbox root: /home/agentscope/workspace"),
                "prompt must advertise configured root, got:\n" + prompt);
    }

    @Test
    @DisplayName("Sandbox prompt keeps default /workspace when state has null workspaceRoot")
    void fallsBackWhenWorkspaceRootNull() {
        SandboxBackedFilesystem fs = fsWithWorkspaceRoot(null);
        wm = new WorkspaceManager(workspace, fs);
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();

        assertNotNull(prompt);
        assertTrue(
                prompt.contains("Sandbox root: /workspace"),
                "prompt must fall back to /workspace, got:\n" + prompt);
    }

    @Test
    @DisplayName("Blank workspaceRoot falls back to /workspace")
    void fallsBackOnBlankRoot() {
        SandboxBackedFilesystem fs = fsWithWorkspaceRoot("   ");
        wm = new WorkspaceManager(workspace, fs);
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();

        assertNotNull(prompt);
        assertTrue(
                prompt.contains("Sandbox root: /workspace"),
                "prompt must fall back to /workspace, got:\n" + prompt);
    }

    private static SandboxBackedFilesystem fsWithWorkspaceRoot(String root) {
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox(root);
        fs.setSandbox(sandbox);
        return fs;
    }

    /** Minimal Sandbox whose state exposes a configurable workspaceRoot. */
    private static final class FakeSandbox implements Sandbox {

        private final FakeState state;

        FakeSandbox(String workspaceRoot) {
            this.state = new FakeState(workspaceRoot);
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void shutdown() {}

        @Override
        public void close() {}

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public SandboxState getState() {
            return state;
        }

        @Override
        public ExecResult exec(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return new ExecResult(0, "", "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}
    }

    /**
     * SandboxState subclass that overrides {@link #getWorkspaceRoot()} with the test value,
     * matching how real backends (Docker/E2b/AgentRun/...) store it.
     */
    private static final class FakeState extends SandboxState {

        private final String workspaceRoot;

        FakeState(String workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
            setWorkspaceSpec(new WorkspaceSpec());
        }

        @Override
        public String getWorkspaceRoot() {
            return workspaceRoot;
        }
    }
}
