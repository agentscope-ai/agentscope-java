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
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Tests for the default methods on the {@link Sandbox} interface.
 */
class SandboxTest {

    @Test
    void getWorkspaceRoot_returnsSpecRootWhenStateAndSpecAreSet() {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot("/custom/root");
        SandboxState state = new DockerSandboxState();
        state.setWorkspaceSpec(spec);

        Sandbox sandbox = new DefaultSandbox(state);
        assertEquals("/custom/root", sandbox.getWorkspaceRoot());
    }

    @Test
    void getWorkspaceRoot_fallsBackToDefaultWhenStateIsNull() {
        Sandbox sandbox = new DefaultSandbox(null);
        assertEquals("/workspace", sandbox.getWorkspaceRoot());
    }

    @Test
    void getWorkspaceRoot_fallsBackToDefaultWhenSpecIsNull() {
        SandboxState state = new DockerSandboxState();
        Sandbox sandbox = new DefaultSandbox(state);
        assertEquals("/workspace", sandbox.getWorkspaceRoot());
    }

    /**
     * Minimal {@link Sandbox} implementation that does <em>not</em> override
     * {@link #getWorkspaceRoot()} so the default method is exercised.
     */
    private static final class DefaultSandbox implements Sandbox {

        private final SandboxState state;

        DefaultSandbox(SandboxState state) {
            this.state = state;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

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
