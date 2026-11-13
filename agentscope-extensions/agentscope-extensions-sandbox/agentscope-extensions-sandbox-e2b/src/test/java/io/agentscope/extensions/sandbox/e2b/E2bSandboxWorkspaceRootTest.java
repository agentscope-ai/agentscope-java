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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link E2bSandboxClient} honouring {@link WorkspaceSpec#getRoot()} instead
 * of only {@link E2bSandboxClientOptions#getWorkspaceRoot()}.
 *
 * <p>Creation honours the supplied workspace layout. Resumption retains the persisted execution
 * root and aligns legacy manifest metadata without relocating the workspace.
 */
class E2bSandboxWorkspaceRootTest {

    private static final String SPEC_ROOT = "/root/workspace";

    @Test
    void createUsesWorkspaceSpecRootOverOptionsDefault() {
        E2bSandboxClient client = new E2bSandboxClient();
        WorkspaceSpec spec = workspaceSpec(SPEC_ROOT);

        Sandbox sandbox = client.create(spec, null, options());

        assertTrue(sandbox instanceof E2bSandbox);
        assertEquals(SPEC_ROOT, ((E2bSandbox) sandbox).getWorkspaceRoot());
    }

    @Test
    void resumeKeepsPersistedExecutionRootOverWorkspaceSpecRoot() {
        E2bSandboxClient client = new E2bSandboxClient();
        E2bSandboxState state = new E2bSandboxState();
        state.setSessionId("session-1");
        state.setWorkspaceSpec(workspaceSpec(SPEC_ROOT));
        state.setWorkspaceRoot("/home/user");

        Sandbox resumed = client.resume(state);

        assertTrue(resumed instanceof E2bSandbox);
        assertEquals("/home/user", ((E2bSandbox) resumed).getWorkspaceRoot());
        assertEquals("/home/user", resumed.getState().getWorkspaceSpec().getRoot());
    }

    @Test
    void resumePreservesWorkspaceStateWhenLegacyManifestRootDiffers() {
        E2bSandboxClient client = new E2bSandboxClient();
        E2bSandboxState state = new E2bSandboxState();
        state.setSessionId("session-1");
        state.setWorkspaceSpec(workspaceSpec(SPEC_ROOT));
        state.setWorkspaceRoot("/home/user");
        state.setWorkspaceRootReady(true);
        state.setWorkspaceProjectionHash("old-projection");

        client.resume(state);

        assertEquals("/home/user", state.getWorkspaceRoot());
        assertEquals("/home/user", state.getWorkspaceSpec().getRoot());
        assertTrue(state.isWorkspaceRootReady());
        assertEquals("old-projection", state.getWorkspaceProjectionHash());
    }

    @Test
    void resumeKeepsWorkspaceStateWhenRootIsUnchanged() {
        E2bSandboxClient client = new E2bSandboxClient();
        E2bSandboxState state = new E2bSandboxState();
        state.setSessionId("session-1");
        state.setWorkspaceSpec(workspaceSpec(SPEC_ROOT));
        state.setWorkspaceRoot(SPEC_ROOT);
        state.setWorkspaceRootReady(true);
        state.setWorkspaceProjectionHash("same-projection");

        client.resume(state);

        assertTrue(state.isWorkspaceRootReady());
        assertEquals("same-projection", state.getWorkspaceProjectionHash());
    }

    @Test
    void createNormalizesExplicitRootAndCopiesCallerSpec() {
        WorkspaceSpec supplied = workspaceSpec("/root/./workspace/unused/../");
        Sandbox sandbox = new E2bSandboxClient().create(supplied, null, options());
        assertEquals(SPEC_ROOT, ((E2bSandbox) sandbox).getWorkspaceRoot());
        assertEquals(SPEC_ROOT, sandbox.getState().getWorkspaceSpec().getRoot());
        assertEquals("/root/./workspace/unused/../", supplied.getRoot());
    }

    @Test
    void untouchedWorkspaceSpecInheritsOptionsRoot() {
        E2bSandboxClientOptions options = options();
        options.setWorkspaceRoot("/custom/execution");
        WorkspaceSpec supplied = new WorkspaceSpec();
        Sandbox sandbox = new E2bSandboxClient().create(supplied, null, options);
        assertEquals("/custom/execution", sandbox.getState().getWorkspaceSpec().getRoot());
        assertEquals("/workspace", supplied.getRoot());
    }

    @Test
    void createAndResumeRejectInvalidExplicitRoots() {
        E2bSandboxClient client = new E2bSandboxClient();
        for (String root : new String[] {null, "", "relative/root"}) {
            assertThrows(
                    io.agentscope.harness.agent.sandbox.SandboxException
                            .SandboxConfigurationException.class,
                    () -> client.create(workspaceSpec(root), null, options()));
            E2bSandboxState state = new E2bSandboxState();
            state.setWorkspaceRoot(root);
            assertThrows(
                    io.agentscope.harness.agent.sandbox.SandboxException
                            .SandboxConfigurationException.class,
                    () -> client.resume(state));
        }
    }

    private static WorkspaceSpec workspaceSpec(String root) {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(root);
        return spec;
    }

    private static E2bSandboxClientOptions options() {
        E2bSandboxClientOptions options = new E2bSandboxClientOptions();
        options.setApiKey("test-api-key");
        return options;
    }
}
