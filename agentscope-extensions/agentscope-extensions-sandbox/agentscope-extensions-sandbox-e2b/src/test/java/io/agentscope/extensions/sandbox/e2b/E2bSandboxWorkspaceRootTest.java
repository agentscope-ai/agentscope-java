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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.util.List;
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
    void createMarksWorkspaceOnVolumeWhenSpecRootIsCoveredByMount() {
        E2bSandboxClient client = new E2bSandboxClient();
        E2bSandboxClientOptions opts = options();
        opts.setVolumeMounts(List.of(new E2bVolumeMount("vol-1", SPEC_ROOT)));

        Sandbox sandbox = client.create(workspaceSpec(SPEC_ROOT), null, opts);

        assertTrue(sandbox instanceof E2bSandbox);
        E2bSandboxState state = (E2bSandboxState) sandbox.getState();
        assertTrue(
                state.isWorkspaceOnVolume(),
                "workspace root " + SPEC_ROOT + " is covered by the configured volume mount");
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
