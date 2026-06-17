package io.agentscope.extensions.sandbox.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KubernetesSandboxClientSnapshotIdTest {

    private final KubernetesSandboxClient client = new KubernetesSandboxClient();

    @TempDir Path tempDir;

    @Test
    @DisplayName("3-arg create delegates to 4-arg")
    void threeArgDelegatesToFourArg() {
        Sandbox sandbox = client.create(new WorkspaceSpec(), null, null);
        assertNotNull(sandbox);
        assertNotNull(sandbox.getState().getSessionId());
    }

    @Test
    @DisplayName("4-arg create uses provided snapshotId")
    void usesProvidedSnapshotId() {
        LocalSnapshotSpec spec = new LocalSnapshotSpec(tempDir.toString());
        Sandbox sandbox = client.create(new WorkspaceSpec(), spec, null, "bob");
        SandboxState state = sandbox.getState();
        assertNotNull(state.getSnapshot());
        assertEquals("bob", snapshotIdOf(state.getSnapshot()));
    }

    @Test
    @DisplayName("4-arg create falls back to sessionId when null")
    void fallsBackWhenNull() {
        LocalSnapshotSpec spec = new LocalSnapshotSpec(tempDir.toString());
        Sandbox sandbox = client.create(new WorkspaceSpec(), spec, null, null);
        SandboxState state = sandbox.getState();
        assertNotNull(state.getSnapshot());
        assertEquals(state.getSessionId(), snapshotIdOf(state.getSnapshot()));
    }

    private static String snapshotIdOf(SandboxSnapshot snapshot) {
        try {
            java.lang.reflect.Field f = snapshot.getClass().getDeclaredField("id");
            f.setAccessible(true);
            return (String) f.get(snapshot);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
