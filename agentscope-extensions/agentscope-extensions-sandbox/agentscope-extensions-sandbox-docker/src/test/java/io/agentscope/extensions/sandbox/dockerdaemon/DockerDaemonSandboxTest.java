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
package io.agentscope.extensions.sandbox.dockerdaemon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.StreamType;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DockerDaemonSandboxTest {

    /** ExecCreateCmd mocks created by {@link #stubExec}, in call order. */
    private final List<ExecCreateCmd> createdExecCmds = new ArrayList<>();

    /** CreateContainerCmd mock created by {@link #stubCreateContainer}. */
    private final List<CreateContainerCmd> createdContainers = new ArrayList<>();

    // -----------------------------------------------------------------
    //  exec
    // -----------------------------------------------------------------

    @Test
    void execRunsCommandAndDemultiplexesFrames() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        stubExec(docker, "c1", "exec-1", "hello\n", "warn\n", 0);

        ExecResult result = sandbox.exec(null, "echo hello", 10);

        assertEquals(0, result.exitCode());
        assertEquals("hello\n", result.stdout());
        assertEquals("warn\n", result.stderr());
        assertTrue(result.ok());

        ExecCreateCmd createCmd = createdExecCmds.get(0);
        verify(createCmd).withCmd("sh", "-c", "echo hello");
        verify(createCmd).withWorkingDir("/workspace");
        verify(createCmd).withAttachStdout(true);
        verify(createCmd).withAttachStderr(true);
    }

    @Test
    void execFailureThrowsExecException() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        stubExec(docker, "c1", "exec-1", "", "boom", 1);

        SandboxException.ExecException ex =
                assertThrows(
                        SandboxException.ExecException.class,
                        () -> sandbox.exec(null, "false", 10));

        assertEquals(1, ex.getExitCode());
    }

    @Test
    void execTimeoutThrowsExecTimeoutException() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        // exec start never completes: callback returned without onNext/onComplete
        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse createResp = mock(ExecCreateCmdResponse.class);
        when(createResp.getId()).thenReturn("exec-slow");
        when(createCmd.exec()).thenReturn(createResp);
        when(docker.execCreateCmd("c1")).thenReturn(createCmd);
        ExecStartCmd startCmd = mock(ExecStartCmd.class, RETURNS_SELF);
        when(docker.execStartCmd("exec-slow")).thenReturn(startCmd);
        doAnswer(inv -> inv.getArgument(0)).when(startCmd).exec(any(ResultCallback.class));

        assertThrows(
                SandboxException.ExecTimeoutException.class,
                () -> sandbox.exec(null, "sleep 100", 1));
    }

    @Test
    void execOutputBeyondTruncationLimitIsCapped() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        int truncateBytes = 512 * 1024;
        stubExec(docker, "c1", "exec-big", "x".repeat(truncateBytes + 1), "", 0);

        ExecResult result = sandbox.exec(null, "cat big", 10);

        assertTrue(result.truncated());
        assertEquals(truncateBytes, result.stdout().length());
    }

    @Test
    void execTransportFailureThrowsStartError() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, RETURNS_SELF);
        when(docker.execCreateCmd("c1")).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new DockerException("daemon down", 500));

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () -> sandbox.exec(null, "echo", 10));

        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
    }

    // -----------------------------------------------------------------
    //  workspace snapshot
    // -----------------------------------------------------------------

    @Test
    void persistWorkspaceRunsTarWithBindMountExcludes() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        sandbox.getState().setWorkspaceSpec(specWithBindMount());
        byte[] tarBytes = "tar-stream-bytes".getBytes(StandardCharsets.UTF_8);
        stubExec(docker, "c1", "exec-tar", new String(tarBytes, StandardCharsets.UTF_8), "", 0);

        byte[] out;
        try (InputStream in = sandbox.persistWorkspace()) {
            out = in.readAllBytes();
        }

        assertArrayEquals(tarBytes, out);

        ExecCreateCmd createCmd = createdExecCmds.get(0);
        ArgumentCaptor<String[]> cmdCaptor = ArgumentCaptor.forClass(String[].class);
        verify(createCmd).withCmd(cmdCaptor.capture());
        assertArrayEquals(
                new String[] {"tar", "--exclude=./data", "-cf", "-", "-C", "/workspace", "."},
                cmdCaptor.getValue());
    }

    @Test
    void persistWorkspaceFailureThrowsArchiveError() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        stubExec(docker, "c1", "exec-tar", "", "tar: error", 2);

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class, sandbox::persistWorkspace);

        assertEquals(SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR, ex.getErrorCode());
    }

    @Test
    void persistWorkspaceWithLargeTarIsNotTruncated() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        String bigTar = "y".repeat(512 * 1024 + 1);
        stubExec(docker, "c1", "exec-tar", bigTar, "", 0);

        byte[] out;
        try (InputStream in = sandbox.persistWorkspace()) {
            out = in.readAllBytes();
        }

        assertEquals(bigTar.length(), out.length);
        assertEquals(bigTar, new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void hydrateWorkspaceUsesArchiveApi() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        stubExec(docker, "c1", "exec-mkdir", "", "", 0);
        CopyArchiveToContainerCmd archiveCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("c1")).thenReturn(archiveCmd);
        ByteArrayInputStream archive =
                new ByteArrayInputStream("tar".getBytes(StandardCharsets.UTF_8));

        sandbox.hydrateWorkspace(archive);

        verify(archiveCmd).withTarInputStream(archive);
        verify(archiveCmd).withRemotePath("/workspace");
        verify(archiveCmd).exec();
    }

    @Test
    void hydrateWorkspaceArchiveFailureThrowsReadError() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        stubExec(docker, "c1", "exec-mkdir", "", "", 0);
        CopyArchiveToContainerCmd archiveCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("c1")).thenReturn(archiveCmd);
        doThrow(new DockerException("archive failed", 500)).when(archiveCmd).exec();
        ByteArrayInputStream archive =
                new ByteArrayInputStream("tar".getBytes(StandardCharsets.UTF_8));

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () -> sandbox.hydrateWorkspace(archive));

        assertEquals(SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR, ex.getErrorCode());
    }

    // -----------------------------------------------------------------
    //  container lifecycle
    // -----------------------------------------------------------------

    @Test
    void startCreatesContainerWhenMissing() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, null); // no container yet
        stubCreateContainer(docker, "c-new");
        // workspace init (Branch D): mkdir then apply (no entries -> no more execs)
        stubExec(docker, "c-new", "exec-mkdir", "", "", 0);

        sandbox.start();

        assertEquals("c-new", ((DockerDaemonSandboxState) sandbox.getState()).getContainerId());
        verify(docker).startContainerCmd("c-new");
    }

    @Test
    void startReusesRunningContainer() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        stubInspect(docker, "c1", true);
        stubExec(docker, "c1", "exec-mkdir", "", "", 0);

        sandbox.start();

        verify(docker, never()).createContainerCmd(any());
        verify(docker, never()).startContainerCmd(any());
    }

    @Test
    void startRestartsStoppedContainer() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        stubInspect(docker, "c1", false);
        StartContainerCmd startCmd = mock(StartContainerCmd.class, RETURNS_SELF);
        when(docker.startContainerCmd("c1")).thenReturn(startCmd);
        stubExec(docker, "c1", "exec-mkdir", "", "", 0);

        sandbox.start();

        verify(docker).startContainerCmd("c1");
        verify(docker, never()).createContainerCmd(any());
    }

    @Test
    void startRestartFailureKeepsContainerAndThrowsStartError() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        stubInspect(docker, "c1", false); // stopped -> restart branch
        StartContainerCmd startCmd = mock(StartContainerCmd.class, RETURNS_SELF);
        when(docker.startContainerCmd("c1")).thenReturn(startCmd);
        doThrow(new DockerException("daemon down", 500)).when(startCmd).exec();

        SandboxException.SandboxRuntimeException ex =
                assertThrows(SandboxException.SandboxRuntimeException.class, sandbox::start);

        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
        // container kept in place for retry on next start() (CLI parity)
        verify(docker, never()).removeContainerCmd(any());
    }

    @Test
    void startCreateFailureRemovesOrphanedContainer() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, null); // create branch
        // create succeeds, start fails -> orphan cleanup
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        createdContainers.add(createCmd);
        CreateContainerResponse resp = mock(CreateContainerResponse.class);
        when(resp.getId()).thenReturn("c-new");
        when(createCmd.exec()).thenReturn(resp);
        when(docker.createContainerCmd(any())).thenReturn(createCmd);
        StartContainerCmd startCmd = mock(StartContainerCmd.class, RETURNS_SELF);
        when(docker.startContainerCmd("c-new")).thenReturn(startCmd);
        doThrow(new DockerException("daemon down", 500)).when(startCmd).exec();
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd("c-new")).thenReturn(removeCmd);

        SandboxException.SandboxRuntimeException ex =
                assertThrows(SandboxException.SandboxRuntimeException.class, sandbox::start);

        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
        verify(docker).removeContainerCmd("c-new");
        verify(removeCmd).withForce(true);
        verify(removeCmd).exec();
    }

    @Test
    void startRecreatesContainerWhenInspectThrowsNotFound() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        stubInspectNotFound(docker, "c1");
        stubCreateContainer(docker, "c-new");
        stubExec(docker, "c-new", "exec-mkdir", "", "", 0);

        sandbox.start();

        assertEquals("c-new", ((DockerDaemonSandboxState) sandbox.getState()).getContainerId());
        verify(docker).createContainerCmd(any());
        verify(docker).startContainerCmd("c-new");
    }

    @Test
    void createContainerMapsHostConfig() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandboxState state = createState();
        state.setImage("python:3.12-slim");
        state.setMemorySizeBytes(1024L * 1024 * 1024);
        state.setCpuCount(2L);
        state.setExposedPorts(new int[] {8080});
        state.setNetwork("bridge");
        state.setWorkspaceSpec(specWithBindMount());
        DockerDaemonSandbox sandbox = new DockerDaemonSandbox(state, docker);
        stubCreateContainer(docker, "c-new");
        stubExec(docker, "c-new", "exec-mkdir", "", "", 0);

        sandbox.start();

        CreateContainerCmd cmd = createdContainers.get(0);
        verify(cmd).withName("agentscope-sandbox-s1");
        ArgumentCaptor<List<String>> envCaptor = ArgumentCaptor.forClass(List.class);
        verify(cmd).withEnv(envCaptor.capture());
        assertEquals(List.of("FOO=bar"), envCaptor.getValue());
        ArgumentCaptor<ExposedPort[]> exposedCaptor = ArgumentCaptor.forClass(ExposedPort[].class);
        verify(cmd).withExposedPorts(exposedCaptor.capture());
        assertArrayEquals(new ExposedPort[] {ExposedPort.tcp(8080)}, exposedCaptor.getValue());
        ArgumentCaptor<HostConfig> hostCaptor = ArgumentCaptor.forClass(HostConfig.class);
        verify(cmd).withHostConfig(hostCaptor.capture());
        HostConfig host = hostCaptor.getValue();
        assertEquals(1024L * 1024 * 1024, host.getMemory());
        assertEquals(2_000_000_000L, host.getNanoCPUs());
        assertEquals("bridge", host.getNetworkMode());
        assertArrayEquals(
                new Bind[] {
                    Bind.parse(
                            WorkspaceMountSupport.normalizedHostPath("/host/data")
                                    + ":/workspace/data:rw")
                },
                host.getBinds());
        assertEquals(1, host.getPortBindings().getBindings().size());
        verify(docker).startContainerCmd("c-new");
    }

    @Test
    void createContainerAppliesAdditionalMountsAlongsideBinds() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandboxState state = createState();
        state.setWorkspaceSpec(specWithBindMount());
        state.setMounts(
                List.of(
                        new MountSpec("bind", "/host/artifacts", "/app/artifacts", false),
                        new MountSpec("volume", "mydata", "/data", true),
                        new MountSpec("tmpfs", null, "/tmp/shm", false)));
        DockerDaemonSandbox sandbox = new DockerDaemonSandbox(state, docker);
        stubCreateContainer(docker, "c-new");
        stubExec(docker, "c-new", "exec-mkdir", "", "", 0);

        sandbox.start();

        CreateContainerCmd cmd = createdContainers.get(0);
        ArgumentCaptor<HostConfig> hostCaptor = ArgumentCaptor.forClass(HostConfig.class);
        verify(cmd).withHostConfig(hostCaptor.capture());
        HostConfig host = hostCaptor.getValue();
        // workspace bind mounts still applied
        assertArrayEquals(
                new Bind[] {
                    Bind.parse(
                            WorkspaceMountSupport.normalizedHostPath("/host/data")
                                    + ":/workspace/data:rw")
                },
                host.getBinds());
        List<Mount> mounts = host.getMounts();
        assertEquals(3, mounts.size());
        assertEquals(MountType.BIND, mounts.get(0).getType());
        assertEquals(
                WorkspaceMountSupport.normalizedHostPath("/host/artifacts"),
                mounts.get(0).getSource());
        assertEquals("/app/artifacts", mounts.get(0).getTarget());
        assertEquals(false, mounts.get(0).getReadOnly());
        assertEquals(MountType.VOLUME, mounts.get(1).getType());
        assertEquals("mydata", mounts.get(1).getSource());
        assertEquals("/data", mounts.get(1).getTarget());
        assertEquals(true, mounts.get(1).getReadOnly());
        assertEquals(MountType.TMPFS, mounts.get(2).getType());
        assertEquals(null, mounts.get(2).getSource());
        assertEquals("/tmp/shm", mounts.get(2).getTarget());
    }

    @Test
    void createContainerRejectsInvalidStoredMount() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandboxState state = createState();
        // canonical constructor bypasses MountSpec.of() — simulates hand-crafted state JSON
        state.setMounts(List.of(new MountSpec("bogus", "/x", "/y", false)));
        DockerDaemonSandbox sandbox = new DockerDaemonSandbox(state, docker);
        stubCreateContainer(docker, "c-new");

        SandboxException.SandboxRuntimeException ex =
                assertThrows(SandboxException.SandboxRuntimeException.class, sandbox::start);

        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
    }

    @Test
    void createContainerRejectsNullMountEntry() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandboxState state = createState();
        // hand-crafted persisted state JSON "mounts": [null] — null entry must not NPE
        state.setMounts(Collections.singletonList(null));
        DockerDaemonSandbox sandbox = new DockerDaemonSandbox(state, docker);
        stubCreateContainer(docker, "c-new");

        SandboxException.SandboxRuntimeException ex =
                assertThrows(SandboxException.SandboxRuntimeException.class, sandbox::start);

        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
    }

    @Test
    void shutdownStopsAndRemovesOwnedContainer() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandbox sandbox = newSandbox(docker, "c1");
        StopContainerCmd stopCmd = mock(StopContainerCmd.class, RETURNS_SELF);
        when(docker.stopContainerCmd("c1")).thenReturn(stopCmd);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd("c1")).thenReturn(removeCmd);

        sandbox.shutdown();

        verify(stopCmd).withTimeout(30);
        verify(stopCmd).exec();
        verify(removeCmd).withForce(true);
        verify(removeCmd).exec();
        verify(docker).close();
    }

    @Test
    void shutdownSkipsUserManagedContainerButClosesClient() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandboxState state = createState();
        state.setContainerOwned(false);
        DockerDaemonSandbox sandbox = new DockerDaemonSandbox(state, docker);

        sandbox.shutdown();

        verify(docker, never()).stopContainerCmd(any());
        verify(docker, never()).removeContainerCmd(any());
        verify(docker).close();
    }

    @Test
    void shutdownWithNullContainerIdDoesNothing() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        DockerDaemonSandboxState state = createState();
        state.setContainerId(null);
        DockerDaemonSandbox sandbox = new DockerDaemonSandbox(state); // client never created

        sandbox.shutdown();

        verify(docker, never()).stopContainerCmd(any());
        verify(docker, never()).removeContainerCmd(any());
        verify(docker, never()).close();
    }

    // -----------------------------------------------------------------
    //  helpers
    // -----------------------------------------------------------------

    private static DockerDaemonSandbox newSandbox(DockerClient docker, String containerId) {
        DockerDaemonSandboxState state = createState();
        state.setContainerId(containerId);
        return new DockerDaemonSandbox(state, docker);
    }

    private static DockerDaemonSandboxState createState() {
        DockerDaemonSandboxState state = new DockerDaemonSandboxState();
        state.setSessionId("s1");
        state.setImage("ubuntu:22.04");
        state.setWorkspaceRoot("/workspace");
        state.setContainerOwned(true);
        state.setWorkspaceSpec(new WorkspaceSpec());
        return state;
    }

    private static WorkspaceSpec specWithBindMount() {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot("/workspace");
        spec.setEnvironment(Map.of("FOO", "bar"));
        BindMountEntry bm = new BindMountEntry();
        bm.setHostPath("/host/data");
        spec.getEntries().put("data", bm);
        return spec;
    }

    /** Stubs one exec: frames fed into the captured callback, then exit code from inspect. */
    private void stubExec(
            DockerClient docker,
            String containerId,
            String execId,
            String stdout,
            String stderr,
            int exitCode) {
        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, RETURNS_SELF);
        createdExecCmds.add(createCmd);
        ExecCreateCmdResponse createResp = mock(ExecCreateCmdResponse.class);
        when(createResp.getId()).thenReturn(execId);
        when(createCmd.exec()).thenReturn(createResp);
        when(docker.execCreateCmd(containerId)).thenReturn(createCmd);
        ExecStartCmd startCmd = mock(ExecStartCmd.class, RETURNS_SELF);
        when(docker.execStartCmd(execId)).thenReturn(startCmd);
        doAnswer(
                        inv -> {
                            ResultCallback<Frame> cb = inv.getArgument(0);
                            if (stdout != null) {
                                cb.onNext(
                                        new Frame(
                                                StreamType.STDOUT,
                                                stdout.getBytes(StandardCharsets.UTF_8)));
                            }
                            if (stderr != null) {
                                cb.onNext(
                                        new Frame(
                                                StreamType.STDERR,
                                                stderr.getBytes(StandardCharsets.UTF_8)));
                            }
                            cb.onComplete();
                            return cb;
                        })
                .when(startCmd)
                .exec(any(ResultCallback.class));
        InspectExecCmd inspectCmd = mock(InspectExecCmd.class);
        InspectExecResponse inspectResp = mock(InspectExecResponse.class);
        when(docker.inspectExecCmd(execId)).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspectResp);
        when(inspectResp.getExitCode()).thenReturn(exitCode);
    }

    private static void stubInspectNotFound(DockerClient docker, String containerId) {
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new NotFoundException("no such container"));
    }

    private static void stubInspect(DockerClient docker, String containerId, boolean running) {
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse resp = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state =
                mock(InspectContainerResponse.ContainerState.class);
        when(docker.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(resp);
        when(resp.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(running);
    }

    private void stubCreateContainer(DockerClient docker, String newId) {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        createdContainers.add(createCmd);
        CreateContainerResponse resp = mock(CreateContainerResponse.class);
        when(resp.getId()).thenReturn(newId);
        when(createCmd.exec()).thenReturn(resp);
        when(docker.createContainerCmd(any())).thenReturn(createCmd);
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(docker.startContainerCmd(newId)).thenReturn(startCmd);
    }
}
