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
package io.agentscope.extensions.sandbox.kubernetes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.sandbox.kubernetes.client.CommandExecutor;
import io.agentscope.extensions.sandbox.kubernetes.client.Filesystem;
import io.agentscope.extensions.sandbox.kubernetes.client.Sandbox;
import io.agentscope.extensions.sandbox.kubernetes.client.model.ExecutionResult;
import io.agentscope.harness.agent.sandbox.ExecResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KubernetesSandboxTest {

    private CommandExecutor commands;
    private Filesystem files;
    private KubernetesSandboxState state;
    private KubernetesSandbox sandbox;

    @BeforeEach
    void setUp() {
        commands = mock(CommandExecutor.class);
        files = mock(Filesystem.class);
        Sandbox sdkSandbox = mock(Sandbox.class);
        when(sdkSandbox.commands()).thenReturn(commands);
        when(sdkSandbox.files()).thenReturn(files);

        state = new KubernetesSandboxState();
        state.setSessionId("session-1");
        io.agentscope.harness.agent.sandbox.WorkspaceSpec ws =
                new io.agentscope.harness.agent.sandbox.WorkspaceSpec();
        ws.setRoot("/workspace");
        state.setWorkspaceSpec(ws);
        state.setFileApiBaseDir("/workspace");
        sandbox = new KubernetesSandbox(state, sdkSandbox);
    }

    @Test
    void doExecWrapsCommandWithWorkspaceCd() throws Exception {
        when(commands.run(anyString(), any(Duration.class)))
                .thenReturn(new ExecutionResult("out", "", 0));

        ExecResult result = sandbox.doExec(null, "echo hi", 30);

        assertEquals(0, result.exitCode());
        assertEquals("out", result.stdout());
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(commands).run(cmd.capture(), eq(Duration.ofSeconds(30)));
        assertEquals("cd '/workspace' && (echo hi)", cmd.getValue());
    }

    @Test
    void hydrateUsesFileApiWhenConfigured() throws Exception {
        when(commands.run(anyString())).thenReturn(new ExecutionResult("", "", 0));
        byte[] tar = "fake-tar".getBytes();

        sandbox.doHydrateWorkspace(new ByteArrayInputStream(tar));

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(files).write(path.capture(), content.capture());
        assertTrue(path.getValue().startsWith(".agentscope-tmp/ws-hydrate-"));
        assertArrayEquals(tar, content.getValue());
        verify(commands).run(eq("tar -xf '/workspace/" + path.getValue() + "' -C '/workspace'"));
    }

    @Test
    void hydrateUsesExecWhenFileApiDisabled() throws Exception {
        state.setFileApiBaseDir(null);
        when(commands.run(anyString())).thenReturn(new ExecutionResult("", "", 0));
        byte[] tar = "fake-tar".getBytes();

        sandbox.doHydrateWorkspace(new ByteArrayInputStream(tar));

        verify(files, never()).write(anyString(), any(byte[].class));
        String b64 = Base64.getEncoder().encodeToString(tar);
        verify(commands).run(contains("echo '" + b64 + "' | base64 -d > /tmp/ws-hydrate-"));
    }

    @Test
    void persistUsesFileApiWhenConfigured() throws Exception {
        when(commands.run(anyString())).thenReturn(new ExecutionResult("", "", 0));
        byte[] tar = "fake-tar".getBytes();
        when(files.read(startsWith(".agentscope-tmp/ws-persist-"))).thenReturn(tar);

        InputStream archive = sandbox.doPersistWorkspace();

        assertArrayEquals(tar, archive.readAllBytes());
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(commands, org.mockito.Mockito.atLeastOnce()).run(cmd.capture());
        String tarCmd =
                cmd.getAllValues().stream()
                        .filter(c -> c.contains("tar "))
                        .findFirst()
                        .orElseThrow();
        assertTrue(tarCmd.contains("-cf '/workspace/.agentscope-tmp/ws-persist-"));
        // The temp archive lives inside the workspace; it must exclude itself.
        assertTrue(tarCmd.contains("--exclude=./.agentscope-tmp"));
    }

    @Test
    void supportsFileTransferOnlyUnderFileApiBaseDir() {
        assertTrue(sandbox.supportsFileTransfer("/workspace/src/Foo.java"));
        assertTrue(!sandbox.supportsFileTransfer("/etc/passwd"));
        assertTrue(!sandbox.supportsFileTransfer("/workspace"));
        assertTrue(!sandbox.supportsFileTransfer("/workspace/../etc/passwd"));

        state.setFileApiBaseDir(null);
        assertTrue(!sandbox.supportsFileTransfer("/workspace/src/Foo.java"));
    }

    @Test
    void uploadFileCreatesParentAndWritesRelativePath() throws Exception {
        when(commands.run(anyString())).thenReturn(new ExecutionResult("", "", 0));
        byte[] content = "hello".getBytes();

        sandbox.uploadFile("/workspace/src/Foo.java", content);

        verify(commands).run(eq("mkdir -p '/workspace/src'"));
        verify(files).write(eq("src/Foo.java"), eq(content));
    }

    @Test
    void downloadFileReadsRelativePath() throws Exception {
        byte[] content = "bytes".getBytes();
        when(files.read(eq("out/report.pdf"))).thenReturn(content);

        assertArrayEquals(content, sandbox.downloadFile("/workspace/out/report.pdf"));
    }

    @Test
    void persistUsesExecWhenFileApiDisabled() throws Exception {
        state.setFileApiBaseDir("");
        byte[] tar = "fake-tar".getBytes();
        // GNU base64 wraps output; the decoder must tolerate embedded newlines.
        String wrapped = Base64.getMimeEncoder(4, "\n".getBytes()).encodeToString(tar);
        when(commands.run(anyString())).thenReturn(new ExecutionResult(wrapped, "", 0));

        InputStream archive = sandbox.doPersistWorkspace();

        assertArrayEquals(tar, archive.readAllBytes());
        verify(commands).run(contains("| base64"));
    }
}
