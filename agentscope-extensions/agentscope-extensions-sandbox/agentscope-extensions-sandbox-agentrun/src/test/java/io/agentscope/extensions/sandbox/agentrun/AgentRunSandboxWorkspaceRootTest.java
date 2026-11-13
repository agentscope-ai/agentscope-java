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
package io.agentscope.extensions.sandbox.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.FileEntry;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentRunSandboxWorkspaceRootTest {

    private static final String SPEC_ROOT = "/root/workspace";

    @Test
    void createUsesWorkspaceSpecRootOverOptionsDefault() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        WorkspaceSpec spec = workspaceSpec(SPEC_ROOT);

        Sandbox sandbox = client.create(spec, null, options());

        AgentRunSandboxState state = (AgentRunSandboxState) sandbox.getState();
        assertEquals(SPEC_ROOT, state.getWorkspaceRoot());
    }

    @Test
    void resumeKeepsPersistedExecutionRootOverWorkspaceSpecRoot() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-1");
        state.setSandboxId("sandbox-1");
        state.setWorkspaceSpec(workspaceSpec(SPEC_ROOT));
        state.setWorkspaceRoot(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT);

        client.resume(state);

        assertEquals(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT, state.getWorkspaceRoot());
        assertEquals(state.getWorkspaceRoot(), state.getWorkspaceSpec().getRoot());
    }

    @Test
    void resumePreservesWorkspaceStateWhenLegacyManifestRootDiffers() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-1");
        state.setSandboxId("sandbox-1");
        state.setWorkspaceSpec(workspaceSpec(SPEC_ROOT));
        state.setWorkspaceRoot(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT);
        state.setWorkspaceRootReady(true);
        state.setWorkspaceProjectionHash("old-projection");

        client.resume(state);

        assertEquals(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT, state.getWorkspaceRoot());
        assertEquals(state.getWorkspaceRoot(), state.getWorkspaceSpec().getRoot());
        assertTrue(state.isWorkspaceRootReady());
        assertEquals("old-projection", state.getWorkspaceProjectionHash());
    }

    @Test
    void resumeKeepsWorkspaceStateWhenRootIsUnchanged() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-1");
        state.setSandboxId("sandbox-1");
        state.setWorkspaceSpec(workspaceSpec(SPEC_ROOT));
        state.setWorkspaceRoot(SPEC_ROOT);
        state.setWorkspaceRootReady(true);
        state.setWorkspaceProjectionHash("same-projection");

        client.resume(state);

        assertTrue(state.isWorkspaceRootReady());
        assertEquals("same-projection", state.getWorkspaceProjectionHash());
    }

    @Test
    void resumeKeepsPersistedNasStateWhenRootIsUnchanged() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-1");
        state.setSandboxId("sandbox-1");
        state.setWorkspaceSpec(workspaceSpec(SPEC_ROOT));
        state.setWorkspaceRoot(SPEC_ROOT);
        state.setWorkspaceOnNas(true);

        client.resume(state);

        assertTrue(
                state.isWorkspaceOnNas(),
                "the persisted mount state stays authoritative when the root is unchanged");
    }

    @Test
    void createMarksWorkspaceOnNasWhenSpecRootIsCoveredByMount() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        AgentRunSandboxClientOptions opts = options();
        opts.setNasConfig(nasMount("/mnt/data"));

        Sandbox sandbox = client.create(workspaceSpec("/mnt/data/ws"), null, opts);

        AgentRunSandboxState state = (AgentRunSandboxState) sandbox.getState();
        assertTrue(state.isWorkspaceOnNas());
    }

    @Test
    void filesystemSpecWorkspaceRootDrivesEffectiveWorkspaceSpec() {
        AgentRunFilesystemSpec spec =
                new AgentRunFilesystemSpec()
                        .apiKey("test-api-key")
                        .templateName("test-template")
                        .mcpServerUrl("https://example.com/mcp")
                        .nasConfig(nasMount("/mnt/data"))
                        .workspaceRoot("/mnt/data/ws");
        SandboxContext context = spec.toSandboxContext();
        AgentRunSandboxClient client = (AgentRunSandboxClient) context.getClient();

        Sandbox sandbox =
                client.create(
                        context.getWorkspaceSpec(),
                        null,
                        (AgentRunSandboxClientOptions) context.getClientOptions());

        AgentRunSandboxState state = (AgentRunSandboxState) sandbox.getState();
        assertEquals("/mnt/data/ws", state.getWorkspaceRoot());
        assertTrue(state.isWorkspaceOnNas());
    }

    @Test
    void createNormalizesRootAndCopiesCallerLayout() {
        WorkspaceSpec supplied = workspaceSpec("/root/./workspace/unused/../");
        supplied.getEntries().put("notes.txt", new FileEntry("initial"));
        supplied.getEnvironment().put("MODE", "test");

        Sandbox sandbox = new AgentRunSandboxClient(options(), null).create(supplied, null, null);
        AgentRunSandboxState state = (AgentRunSandboxState) sandbox.getState();

        assertEquals(SPEC_ROOT, state.getWorkspaceRoot());
        assertEquals(SPEC_ROOT, state.getWorkspaceSpec().getRoot());
        assertNotSame(supplied, state.getWorkspaceSpec());
        assertEquals(supplied.getEntries(), state.getWorkspaceSpec().getEntries());
        assertEquals(supplied.getEnvironment(), state.getWorkspaceSpec().getEnvironment());
        state.getWorkspaceSpec().getEnvironment().put("MODE", "changed");
        assertEquals("test", supplied.getEnvironment().get("MODE"));
        assertEquals("/root/./workspace/unused/../", supplied.getRoot());
    }

    @Test
    void untouchedOrAbsentWorkspaceSpecInheritsOptionsRoot() {
        AgentRunSandboxClientOptions opts = options();
        opts.setWorkspaceRoot("/custom/./execution");
        AgentRunSandboxClient client = new AgentRunSandboxClient(opts, null);
        WorkspaceSpec supplied = new WorkspaceSpec();
        for (WorkspaceSpec spec : new WorkspaceSpec[] {supplied, null}) {
            Sandbox sandbox = client.create(spec, null, null);
            assertEquals("/custom/execution", sandbox.getState().getWorkspaceSpec().getRoot());
        }
        assertEquals("/workspace", supplied.getRoot());
    }

    @Test
    void filesystemSpecRejectsExplicitConflictsInBothOrders() {
        WorkspaceSpec supplied = workspaceSpec(SPEC_ROOT);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AgentRunFilesystemSpec()
                                .workspaceSpec(supplied)
                                .workspaceRoot("/different")
                                .toSandboxContext());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AgentRunFilesystemSpec()
                                .workspaceRoot("/different")
                                .workspaceSpec(supplied)
                                .toSandboxContext());
        assertEquals(SPEC_ROOT, supplied.getRoot());
    }

    @Test
    void filesystemSpecAcceptsNormalizedMatchesInBothOrdersAndIsolatesCaller() {
        WorkspaceSpec supplied = workspaceSpec(SPEC_ROOT);
        supplied.getEnvironment().put("MODE", "test");
        AgentRunFilesystemSpec[] builders = {
            new AgentRunFilesystemSpec()
                    .workspaceSpec(supplied)
                    .workspaceRoot("/root/./workspace/unused/../"),
            new AgentRunFilesystemSpec()
                    .workspaceRoot("/root/./workspace/unused/../")
                    .workspaceSpec(supplied)
        };
        for (AgentRunFilesystemSpec builder : builders) {
            SandboxContext context = builder.toSandboxContext();
            assertEquals(SPEC_ROOT, context.getWorkspaceSpec().getRoot());
            assertEquals(
                    SPEC_ROOT,
                    ((AgentRunSandboxClientOptions) context.getClientOptions()).getWorkspaceRoot());
            context.getWorkspaceSpec().getEnvironment().put("MODE", "changed");
        }
        assertEquals(SPEC_ROOT, supplied.getRoot());
        assertEquals("test", supplied.getEnvironment().get("MODE"));
    }

    @Test
    void filesystemSpecDefaultRootInheritsAndExplicitSpecSelectsExecutionRoot() {
        WorkspaceSpec untouched = new WorkspaceSpec();
        SandboxContext inherited =
                new AgentRunFilesystemSpec()
                        .workspaceSpec(untouched)
                        .workspaceRoot(SPEC_ROOT)
                        .toSandboxContext();
        assertEquals(SPEC_ROOT, inherited.getWorkspaceSpec().getRoot());
        assertEquals("/workspace", untouched.getRoot());
        SandboxContext selected =
                new AgentRunFilesystemSpec()
                        .workspaceSpec(workspaceSpec(SPEC_ROOT))
                        .toSandboxContext();
        assertEquals(
                SPEC_ROOT,
                ((AgentRunSandboxClientOptions) selected.getClientOptions()).getWorkspaceRoot());
        assertEquals(
                AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT,
                new AgentRunFilesystemSpec().toSandboxContext().getWorkspaceSpec().getRoot());
    }

    @Test
    void resumeCopiesNewCallLayoutToSavedRootAndPreservesNasMetadata() throws Exception {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        AgentRunSandboxState saved = new AgentRunSandboxState();
        saved.setSessionId("saved-session");
        saved.setSandboxId("saved-sandbox");
        saved.setWorkspaceRoot("/mnt/data/./saved/unused/../");
        saved.setWorkspaceRootReady(true);
        saved.setWorkspaceProjectionHash("saved-projection");
        saved.setWorkspaceOnNas(true);
        SessionSandboxStateStore store = mock(SessionSandboxStateStore.class);
        when(store.load(any())).thenReturn(Optional.of(client.serializeState(saved)));
        WorkspaceSpec newCall = workspaceSpec("/new/call");
        newCall.getEntries().put("notes.txt", new FileEntry("current"));
        newCall.getEnvironment().put("MODE", "current");
        SandboxContext context =
                SandboxContext.builder()
                        .isolationScope(IsolationScope.SESSION)
                        .workspaceSpec(newCall)
                        .build();

        Sandbox resumed =
                new SandboxManager(client, store, "agent")
                        .acquire(context, RuntimeContext.builder().sessionId("session").build())
                        .getSandbox();
        AgentRunSandboxState state = (AgentRunSandboxState) resumed.getState();

        assertEquals("saved-sandbox", state.getSandboxId());
        assertEquals("/mnt/data/saved", state.getWorkspaceRoot());
        assertEquals(state.getWorkspaceRoot(), state.getWorkspaceSpec().getRoot());
        assertTrue(state.isWorkspaceRootReady());
        assertEquals("saved-projection", state.getWorkspaceProjectionHash());
        assertTrue(state.isWorkspaceOnNas());
        assertNotSame(newCall, state.getWorkspaceSpec());
        assertEquals(newCall.getEntries(), state.getWorkspaceSpec().getEntries());
        assertEquals(newCall.getEnvironment(), state.getWorkspaceSpec().getEnvironment());
        assertEquals("/new/call", newCall.getRoot());
    }

    @Test
    void legacyStateWithoutLayoutUsesSavedDefaultExecutionRoot() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        AgentRunSandboxState state =
                (AgentRunSandboxState)
                        client.deserializeState("{\"type\":\"agentrun\",\"sessionId\":\"legacy\"}");
        state.setWorkspaceSpec(null);
        client.resume(state);
        assertEquals(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT, state.getWorkspaceRoot());
        assertEquals(state.getWorkspaceRoot(), state.getWorkspaceSpec().getRoot());
    }

    @Test
    void createAndResumeRejectBlankOrRelativeRoots() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        for (String root : new String[] {null, "", "relative/root"}) {
            assertThrows(
                    SandboxException.SandboxConfigurationException.class,
                    () -> client.create(workspaceSpec(root), null, null));
            if (root != null) {
                AgentRunSandboxState state = new AgentRunSandboxState();
                state.setWorkspaceRoot(root);
                assertThrows(
                        SandboxException.SandboxConfigurationException.class,
                        () -> client.resume(state));
            }
        }
    }

    private static AgentRunNasMountConfig nasMount(String mountDir) {
        AgentRunNasMountConfig nas = new AgentRunNasMountConfig();
        nas.setMountDir(mountDir);
        return nas;
    }

    private static WorkspaceSpec workspaceSpec(String root) {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(root);
        return spec;
    }

    private static AgentRunSandboxClientOptions options() {
        AgentRunSandboxClientOptions options = new AgentRunSandboxClientOptions();
        options.setApiKey("test-api-key");
        options.setTemplateName("test-template");
        options.setMcpServerUrl("https://example.com/mcp");
        return options;
    }
}
