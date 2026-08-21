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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.SandboxManager;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.services.Commands;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class OfficialOpenSandboxSdkTest {

    @Test
    void createMapsStateAndConnectionOptionsToOfficialBuilder() {
        OpenSandboxState state = new OpenSandboxState();
        state.setImage("ubuntu:24.04");
        state.setEntrypoint(List.of("sleep", "infinity"));
        state.setResourceLimits(Map.of("cpu", "2", "memory", "4Gi"));
        state.setSandboxTimeoutSeconds(900);
        OpenSandboxClientOptions options = options();
        Sandbox.Builder builder = mock(Sandbox.Builder.class, RETURNS_SELF);
        Sandbox official = mock(Sandbox.class);
        when(builder.build()).thenReturn(official);
        when(official.getId()).thenReturn("sandbox-created");

        try (MockedStatic<Sandbox> sandboxClass = mockStatic(Sandbox.class)) {
            sandboxClass.when(Sandbox::builder).thenReturn(builder);

            OpenSandboxSdk.Handle handle = new OfficialOpenSandboxSdk().create(state, options);

            assertEquals("sandbox-created", handle.id());
            verify(builder).image("ubuntu:24.04");
            verify(builder).entrypoint(List.of("sleep", "infinity"));
            verify(builder).resource(Map.of("cpu", "2", "memory", "4Gi"));
            verify(builder).timeout(Duration.ofSeconds(900));
            verify(builder).readyTimeout(Duration.ofSeconds(17));
            verify(builder)
                    .connectionConfig(
                            argThat(
                                    config ->
                                            "https".equals(config.getProtocol())
                                                    && "sandbox.example.com:8443"
                                                            .equals(config.getDomain())
                                                    && "secret".equals(config.getApiKey())
                                                    && Duration.ofSeconds(23)
                                                            .equals(config.getRequestTimeout())
                                                    && config.getUseServerProxy()));
        }
    }

    @Test
    void connectAndKillUseConfiguredControlPlane() {
        OpenSandboxClientOptions options = options();
        Sandbox.Connector connector = mock(Sandbox.Connector.class, RETURNS_SELF);
        Sandbox official = mock(Sandbox.class);
        when(connector.connect()).thenReturn(official);
        SandboxManager.Builder managerBuilder = mock(SandboxManager.Builder.class, RETURNS_SELF);
        SandboxManager manager = mock(SandboxManager.class);
        when(managerBuilder.build()).thenReturn(manager);

        try (MockedStatic<Sandbox> sandboxClass = mockStatic(Sandbox.class);
                MockedStatic<SandboxManager> managerClass = mockStatic(SandboxManager.class)) {
            sandboxClass.when(Sandbox::connector).thenReturn(connector);
            managerClass.when(SandboxManager::builder).thenReturn(managerBuilder);

            OfficialOpenSandboxSdk sdk = new OfficialOpenSandboxSdk();
            sdk.connect("sandbox-existing", options);
            sdk.kill("sandbox-existing", options);

            verify(connector).sandboxId("sandbox-existing");
            verify(connector).connectTimeout(Duration.ofSeconds(17));
            verify(connector).connectionConfig(any());
            verify(managerBuilder).connectionConfig(any());
            verify(manager).killSandbox("sandbox-existing");
            verify(manager).close();
        }
    }

    @Test
    void notFoundRecognizesNestedOfficial404Only() {
        OfficialOpenSandboxSdk sdk = new OfficialOpenSandboxSdk();
        SandboxError error = new SandboxError("test", "test error");
        SandboxApiException notFound = new SandboxApiException("missing", null, 404, error);
        SandboxApiException serverError = new SandboxApiException("failed", null, 500, error);

        assertTrue(sdk.isNotFound(new IllegalStateException("wrapped", notFound)));
        assertFalse(sdk.isNotFound(serverError));
        assertFalse(sdk.isNotFound(null));
    }

    @Test
    void handleAdaptsCommandsAndFileOperations() throws Exception {
        Sandbox official = mock(Sandbox.class);
        Commands commands = mock(Commands.class);
        Filesystem files = mock(Filesystem.class);
        when(official.getId()).thenReturn("sandbox-1");
        when(official.commands()).thenReturn(commands);
        when(official.files()).thenReturn(files);

        ExecutionLogs logs = new ExecutionLogs();
        logs.addStdout(new OutputMessage("one", 1, false));
        logs.addStdout(new OutputMessage("two", 2, false));
        logs.addStderr(new OutputMessage("bad", 3, true));
        Execution execution = new Execution("exec-1", 1L, List.of(), null, null, 7, logs);
        Execution emptyExecution = new Execution();
        when(commands.run(any(RunCommandRequest.class)))
                .thenReturn(execution, emptyExecution, null);
        byte[] remoteBytes = new byte[] {0, 1, (byte) 255};
        InputStream remote = new ByteArrayInputStream(remoteBytes);
        when(files.readStream("/tmp/data.bin")).thenReturn(remote);
        OpenSandboxSdk.Handle handle = newHandle(official);

        ExecResult result = handle.exec("printf test", "/workspace", 0);
        ExecResult empty = handle.exec("true", "/workspace", 3);
        ExecResult missing = handle.exec("missing", "/workspace", 3);
        byte[] localBytes;
        try (InputStream input = handle.read("/tmp/data.bin")) {
            localBytes = input.readAllBytes();
        }
        handle.write("/tmp/upload.bin", remoteBytes);
        handle.close();

        assertEquals("sandbox-1", handle.id());
        assertEquals(7, result.exitCode());
        assertEquals("onetwo", result.stdout());
        assertEquals("bad", result.stderr());
        assertEquals(-1, empty.exitCode());
        assertEquals("", empty.stdout());
        assertEquals(-1, missing.exitCode());
        assertArrayEquals(remoteBytes, localBytes);
        verify(commands)
                .run(
                        argThat(
                                (RunCommandRequest request) ->
                                        "printf test".equals(request.getCommand())
                                                && "/workspace"
                                                        .equals(request.getWorkingDirectory())
                                                && Duration.ofSeconds(1)
                                                        .equals(request.getTimeout())));
        verify(files)
                .writeFile(
                        argThat(
                                entry ->
                                        "/tmp/upload.bin".equals(entry.getPath())
                                                && entry.getData() instanceof byte[] bytes
                                                && java.util.Arrays.equals(remoteBytes, bytes)));
        verify(official).close();
    }

    private static OpenSandboxSdk.Handle newHandle(Sandbox sandbox) throws Exception {
        Class<?> type = Class.forName(OfficialOpenSandboxSdk.class.getName() + "$HandleImpl");
        Constructor<?> constructor = type.getDeclaredConstructor(Sandbox.class);
        constructor.setAccessible(true);
        return (OpenSandboxSdk.Handle) constructor.newInstance(sandbox);
    }

    private static OpenSandboxClientOptions options() {
        OpenSandboxClientOptions options = new OpenSandboxClientOptions();
        options.setEndpoint("https://sandbox.example.com:8443");
        options.setApiKey("secret");
        options.setReadyTimeoutSeconds(17);
        options.setRequestTimeoutSeconds(23);
        options.setUseServerProxy(true);
        return options;
    }
}
