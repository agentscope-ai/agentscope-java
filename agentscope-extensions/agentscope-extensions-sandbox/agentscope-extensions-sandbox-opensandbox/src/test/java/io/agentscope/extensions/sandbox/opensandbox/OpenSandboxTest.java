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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void repeatedStartReusesConnectedHandle() throws Exception {
        Fixture fixture = fixture();

        fixture.sandbox.start();
        fixture.sandbox.start();

        assertEquals(1, fixture.sdk.createCalls);
    }

    @Test
    void failedWorkspaceSetupClosesCreatedHandle() {
        Fixture fixture = fixture();
        fixture.sdk.handle.nextResult = new ExecResult(9, "", "mkdir failed", false);

        assertThrows(SandboxException.WorkspaceStartException.class, fixture.sandbox::start);

        assertEquals(1, fixture.sdk.handle.closeCalls);
        assertFalse(fixture.state.isWorkspaceRootReady());
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
    void shutdownIsIdempotentAndDoesNotKillUnownedSandbox() throws Exception {
        Fixture fixture = fixture();
        fixture.state.setSandboxId("external-id");
        fixture.state.setSandboxOwned(false);

        fixture.sandbox.start();
        fixture.sandbox.shutdown();
        fixture.sandbox.shutdown();

        assertTrue(fixture.sdk.killedIds.isEmpty());
        assertEquals(1, fixture.sdk.handle.closeCalls);
    }

    @Test
    void shutdownPreservesCloseFailureAndSuppressesKillFailure() throws Exception {
        Fixture fixture = fixture();
        fixture.sandbox.start();
        IOException closeFailure = new IOException("close failed");
        fixture.sdk.handle.closeFailure = closeFailure;
        fixture.sdk.killFailure = new IOException("kill failed");

        Exception failure = assertThrows(Exception.class, fixture.sandbox::shutdown);

        assertEquals(closeFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("kill failed", failure.getSuppressed()[0].getMessage());
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

    @Test
    void persistDownloadsTarAndCleansTemporaryFile() throws Exception {
        Fixture fixture = fixture();
        byte[] archive = new byte[] {4, 5, 6};
        String temp =
                "/tmp/agentscope-persist-" + Integer.toHexString("session-1".hashCode()) + ".tar";
        fixture.sdk.handle.files.put(temp, archive);
        fixture.sandbox.start();

        byte[] persisted;
        try (InputStream input = fixture.sandbox.persistWorkspace()) {
            persisted = input.readAllBytes();
        }

        assertArrayEquals(archive, persisted);
        assertTrue(fixture.sdk.handle.commands.stream().anyMatch(c -> c.contains("tar -cf")));
        assertTrue(fixture.sdk.handle.commands.stream().anyMatch(c -> c.contains("rm -f")));
    }

    @Test
    void cleanupFailureDoesNotMaskSuccessfulHydration() throws Exception {
        Fixture fixture = fixture();
        fixture.sandbox.start();
        fixture.sdk.handle.failCommandContains = "rm -f";

        fixture.sandbox.hydrateWorkspace(new ByteArrayInputStream(new byte[] {1}));

        assertTrue(fixture.sdk.handle.commands.stream().anyMatch(c -> c.contains("tar -xf")));
    }

    @Test
    void transferValidationAndShellQuotingCoverEdgePaths() throws Exception {
        Fixture fixture = fixture();

        assertFalse(fixture.sandbox.supportsFileTransfer(null));
        assertFalse(fixture.sandbox.supportsFileTransfer("relative"));
        assertTrue(fixture.sandbox.supportsFileTransfer("/absolute"));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.sandbox.uploadFile("relative", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> fixture.sandbox.downloadFile(null));
        assertThrows(
                NullPointerException.class,
                () -> fixture.sandbox.uploadFile("/workspace/file", null));
        assertThrows(
                SandboxException.SandboxRuntimeException.class,
                () -> fixture.sandbox.downloadFile("/workspace/file"));

        fixture.sandbox.start();
        fixture.sandbox.uploadFile("/workspace/o'hara/file", new byte[] {1});

        assertTrue(
                fixture.sdk.handle.commands.stream()
                        .anyMatch(command -> command.contains("'\"'\"'")));
    }

    @Test
    void destroyWorkspaceUsesRootDirectoryAsWorkingDirectory() throws Exception {
        OpenSandboxState state = state();
        RecordingSdk sdk = new RecordingSdk();
        ExposedOpenSandbox sandbox =
                new ExposedOpenSandbox(state, new OpenSandboxClientOptions(), sdk);
        sandbox.start();

        sandbox.destroyWorkspace();

        assertTrue(sdk.handle.commands.stream().anyMatch(c -> c.startsWith("rm -rf")));
        assertEquals(
                "/", sdk.handle.workingDirectories.get(sdk.handle.workingDirectories.size() - 1));
    }

    private static Fixture fixture() {
        OpenSandboxState state = state();
        RecordingSdk sdk = new RecordingSdk();
        return new Fixture(state, sdk, new OpenSandbox(state, new OpenSandboxClientOptions(), sdk));
    }

    private static OpenSandboxState state() {
        OpenSandboxState state = new OpenSandboxState();
        state.setSessionId("session-1");
        WorkspaceSpec workspace = new WorkspaceSpec();
        workspace.setRoot("/workspace");
        state.setWorkspaceSpec(workspace);
        return state;
    }

    private record Fixture(OpenSandboxState state, RecordingSdk sdk, OpenSandbox sandbox) {}

    private static final class NotFoundException extends Exception {}

    private static final class RecordingSdk implements OpenSandboxSdk {
        private int createCalls;
        private int connectCalls;
        private Exception connectFailure;
        private Exception killFailure;
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
        public void kill(String sandboxId, OpenSandboxClientOptions options) throws Exception {
            if (killFailure != null) throw killFailure;
            killedIds.add(sandboxId);
        }

        @Override
        public boolean isNotFound(Throwable error) {
            return error instanceof NotFoundException;
        }
    }

    private static final class RecordingHandle implements OpenSandboxSdk.Handle {
        private final List<String> commands = new ArrayList<>();
        private final List<String> workingDirectories = new ArrayList<>();
        private final Map<String, byte[]> files = new HashMap<>();
        private ExecResult nextResult = new ExecResult(0, "", "", false);
        private String failCommandContains;
        private Exception closeFailure;
        private int closeCalls;

        @Override
        public String id() {
            return "created-id";
        }

        @Override
        public ExecResult exec(String command, String workingDirectory, int timeoutSeconds)
                throws Exception {
            commands.add(command);
            workingDirectories.add(workingDirectory);
            if (failCommandContains != null && command.contains(failCommandContains)) {
                throw new IOException("command failed");
            }
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
        public void close() throws Exception {
            closeCalls++;
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static final class ExposedOpenSandbox extends OpenSandbox {
        private ExposedOpenSandbox(
                OpenSandboxState state, OpenSandboxClientOptions options, OpenSandboxSdk sdk) {
            super(state, options, sdk);
        }

        private void destroyWorkspace() throws Exception {
            doDestroyWorkspace();
        }
    }
}
