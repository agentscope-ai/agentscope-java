package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for the default 4-arg create() method on {@link SandboxClient}. */
@ExtendWith(MockitoExtension.class)
class SandboxClientDefaultMethodTest {

    @Mock Sandbox sandbox3arg;

    @Test
    @DisplayName("default 4-arg create delegates to 3-arg create")
    void defaultMethodDelegates() {
        SandboxClient<SandboxClientOptions> client =
                new SandboxClient<>() {
                    @Override
                    public Sandbox create(
                            WorkspaceSpec ws, SandboxSnapshotSpec ss, SandboxClientOptions opts) {
                        return sandbox3arg;
                    }

                    @Override
                    public Sandbox resume(SandboxState state) {
                        return null;
                    }

                    @Override
                    public void delete(Sandbox sandbox) {}

                    @Override
                    public String serializeState(SandboxState state) {
                        return null;
                    }

                    @Override
                    public SandboxState deserializeState(String json) {
                        return null;
                    }
                };

        Sandbox result = client.create(new WorkspaceSpec(), null, null, "some-snapshot-id");
        assertSame(sandbox3arg, result);
        assertNotNull(result);
    }
}
