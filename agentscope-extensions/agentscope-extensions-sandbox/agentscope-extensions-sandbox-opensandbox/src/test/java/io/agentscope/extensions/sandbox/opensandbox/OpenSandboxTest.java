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
package io.agentscope.extensions.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenSandboxTest {
    @Test
    void startCreatesWhenStateHasNoId() throws Exception {
        Fixture fixture = fixture();

        fixture.sandbox.start();

        assertEquals(1, fixture.sdk.createCalls);
        assertEquals("created-id", fixture.state.getSandboxId());
    }

    @Test
    void startRecreatesOnlyAfterExplicitNotFound() throws Exception {
        Fixture fixture = fixture();
        fixture.state.setSandboxId("gone");
        fixture.sdk.connectFailure = new NotFoundException();

        fixture.sandbox.start();

        assertEquals(1, fixture.sdk.connectCalls);
        assertEquals(1, fixture.sdk.createCalls);
        assertEquals("created-id", fixture.state.getSandboxId());
    }

    @Test
    void startDoesNotRecreateAfterConnectionFailure() {
        Fixture fixture = fixture();
        fixture.state.setSandboxId("temporarily-unreachable");
        fixture.sdk.connectFailure = new IOException("timeout");

        assertThrows(Exception.class, fixture.sandbox::start);
        assertEquals(0, fixture.sdk.createCalls);
    }

    @Test
    void shutdownKillsOwnedSandboxByIdAfterHandleWasClosed() throws Exception {
        Fixture fixture = fixture();
        fixture.sandbox.start();

        fixture.sandbox.stop();
        fixture.sandbox.shutdown();

        assertEquals(1, fixture.sdk.handle.closeCalls);
        assertEquals(List.of("created-id"), fixture.sdk.killedIds);
    }

    @Test
    void nonZeroExecUsesHarnessExceptionContract() throws Exception {
        Fixture fixture = fixture();
        fixture.sandbox.start();
        fixture.sdk.handle.nextResult = new ExecResult(7, "out", "bad", false);

        SandboxException.ExecException error =
                assertThrows(
                        SandboxException.ExecException.class,
                        () -> fixture.sandbox.exec(null, "false", 5));

        assertEquals(7, error.getExitCode());
        assertEquals("out", error.getStdout());
        assertEquals("bad", error.getStderr());
    }

    @Test
    void nativeFileTransferCreatesParentAndPreservesBinaryBytes() throws Exception {
        Fixture fixture = fixture();
        fixture.sandbox.start();
        byte[] bytes = new byte[] {0, 1, 2, (byte) 255};

        fixture.sandbox.uploadFile("/workspace/nested/data.bin", bytes);

        assertArrayEquals(bytes, fixture.sandbox.downloadFile("/workspace/nested/data.bin"));
        assertTrue(fixture.sdk.handle.commands.stream().anyMatch(c -> c.contains("mkdir -p")));
    }

    @Test
    void hydrateUploadsTarExtractsAndCleansTemporaryFile() throws Exception {
        Fixture fixture = fixture();
        fixture.sandbox.start();

        fixture.sandbox.hydrateWorkspace(new ByteArrayInputStream(new byte[] {1, 2, 3}));

        assertTrue(fixture.sdk.handle.commands.stream().anyMatch(c -> c.contains("tar -xf")));
        assertTrue(fixture.sdk.handle.commands.stream().anyMatch(c -> c.contains("rm -f")));
    }

    private static Fixture fixture() {
        OpenSandboxState state = new OpenSandboxState();
        state.setSessionId("session-1");
        WorkspaceSpec workspace = new WorkspaceSpec();
        workspace.setRoot("/workspace");
        state.setWorkspaceSpec(workspace);
        RecordingSdk sdk = new RecordingSdk();
        return new Fixture(state, sdk, new OpenSandbox(state, new OpenSandboxClientOptions(), sdk));
    }

    private record Fixture(OpenSandboxState state, RecordingSdk sdk, OpenSandbox sandbox) {}

    private static final class NotFoundException extends Exception {}

    private static final class RecordingSdk implements OpenSandboxSdk {
        private int createCalls;
        private int connectCalls;
        private Exception connectFailure;
        private final List<String> killedIds = new ArrayList<>();
        private final RecordingHandle handle = new RecordingHandle();

        @Override
        public Handle create(OpenSandboxState state, OpenSandboxClientOptions options) {
            createCalls++;
            return handle;
        }

        @Override
        public Handle connect(String sandboxId, OpenSandboxClientOptions options) throws Exception {
            connectCalls++;
            if (connectFailure != null) throw connectFailure;
            return handle;
        }

        @Override
        public void kill(String sandboxId, OpenSandboxClientOptions options) {
            killedIds.add(sandboxId);
        }

        @Override
        public boolean isNotFound(Throwable error) {
            return error instanceof NotFoundException;
        }
    }

    private static final class RecordingHandle implements OpenSandboxSdk.Handle {
        private final List<String> commands = new ArrayList<>();
        private final Map<String, byte[]> files = new HashMap<>();
        private ExecResult nextResult = new ExecResult(0, "", "", false);
        private int closeCalls;

        @Override
        public String id() {
            return "created-id";
        }

        @Override
        public ExecResult exec(String command, String workingDirectory, int timeoutSeconds) {
            commands.add(command);
            ExecResult result = nextResult;
            nextResult = new ExecResult(0, "", "", false);
            return result;
        }

        @Override
        public InputStream read(String absolutePath) {
            return new ByteArrayInputStream(files.getOrDefault(absolutePath, new byte[0]));
        }

        @Override
        public void write(String absolutePath, byte[] content) {
            files.put(absolutePath, content.clone());
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
