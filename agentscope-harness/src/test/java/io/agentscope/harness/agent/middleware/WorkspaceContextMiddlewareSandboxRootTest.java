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
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.ContextRenderer;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.InputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

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

        String prompt = WorkspacePromptTestSupport.render(mw, RuntimeContext.empty(), "BASE\n");

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

        String prompt = WorkspacePromptTestSupport.render(mw, RuntimeContext.empty(), "BASE\n");

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

        String prompt = WorkspacePromptTestSupport.render(mw, RuntimeContext.empty(), "BASE\n");

        assertNotNull(prompt);
        assertTrue(
                prompt.contains("Sandbox root: /workspace"),
                "prompt must fall back to /workspace, got:\n" + prompt);
    }

    @Test
    void concurrentCallsUseTheirOwnSandboxRoots() {
        SandboxBackedFilesystem fs = fsWithWorkspaceRoot("/another-session");
        wm = new WorkspaceManager(workspace, fs);
        WorkspaceContextMiddleware middleware = new WorkspaceContextMiddleware(wm);
        RuntimeContext first = RuntimeContext.empty();
        first.put(
                SandboxAcquireResult.class,
                SandboxAcquireResult.userManaged(new FakeSandbox("/session-one")));
        RuntimeContext second = RuntimeContext.empty();
        second.put(
                SandboxAcquireResult.class,
                SandboxAcquireResult.userManaged(new FakeSandbox("/session-two")));

        Mono.when(
                        middleware.onSystemPrompt(null, first, ""),
                        middleware.onSystemPrompt(null, second, ""))
                .block();

        String firstPrompt = renderMaterials(first);
        String secondPrompt = renderMaterials(second);
        assertTrue(firstPrompt.contains("Sandbox root: /session-one"));
        assertFalse(firstPrompt.contains("/session-two"));
        assertFalse(firstPrompt.contains("/another-session"));
        assertTrue(secondPrompt.contains("Sandbox root: /session-two"));
        assertFalse(secondPrompt.contains("/session-one"));
        assertFalse(secondPrompt.contains("/another-session"));
    }

    @Test
    void boundSandboxWithoutRootDoesNotReadAnotherSessionsRoot() {
        SandboxBackedFilesystem fs = fsWithWorkspaceRoot("/another-session");
        wm = new WorkspaceManager(workspace, fs);
        WorkspaceContextMiddleware middleware = new WorkspaceContextMiddleware(wm);
        RuntimeContext context = RuntimeContext.empty();
        context.put(
                SandboxAcquireResult.class,
                SandboxAcquireResult.userManaged(new FakeSandbox(null)));

        String prompt = WorkspacePromptTestSupport.render(middleware, context, "");

        assertTrue(prompt.contains("Sandbox root: /workspace"));
        assertFalse(prompt.contains("/another-session"));
    }

    private static SandboxBackedFilesystem fsWithWorkspaceRoot(String root) {
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox(root);
        fs.setSandbox(sandbox);
        return fs;
    }

    private static String renderMaterials(RuntimeContext context) {
        return ContextRenderer.render(
                context.get(WorkspaceContextMaterials.class).items().stream()
                        .filter(item -> item.placement() == ContextItem.Placement.SYSTEM)
                        .toList());
    }

    /** Minimal Sandbox whose state exposes a configurable workspaceRoot. */
    private static final class FakeSandbox implements Sandbox {

        private final DockerSandboxState state;

        FakeSandbox(String workspaceRoot) {
            this.state = new DockerSandboxState();
            state.setWorkspaceRoot(workspaceRoot);
            state.setWorkspaceSpec(new WorkspaceSpec());
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
}
