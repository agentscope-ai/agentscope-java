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
package io.agentscope.extensions.sandbox.kubernetes.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxRequestException;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.SandboxConnector;
import io.agentscope.extensions.sandbox.kubernetes.client.model.ExecutionResult;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommandExecutorTest {

    @Mock SandboxConnector connector;

    CommandExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CommandExecutor(connector);
    }

    @Test
    void run_success() throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        doReturn(200).when(resp).statusCode();
        doReturn("{\"stdout\":\"hello\",\"stderr\":\"\",\"exit_code\":0}").when(resp).body();

        doReturn(resp)
                .when(connector)
                .sendRequest(
                        eq("POST"),
                        eq("execute"),
                        any(HttpRequest.BodyPublisher.class),
                        eq("application/json"),
                        anyInt());

        ExecutionResult result = executor.run("echo hello");
        assertEquals(0, result.getExitCode());
        assertEquals("hello", result.getStdout());
        assertEquals("", result.getStderr());
    }

    @Test
    void run_nonZeroExit() throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        doReturn(200).when(resp).statusCode();
        doReturn("{\"stdout\":\"\",\"stderr\":\"not found\",\"exit_code\":127}").when(resp).body();

        doReturn(resp)
                .when(connector)
                .sendRequest(
                        eq("POST"),
                        eq("execute"),
                        any(HttpRequest.BodyPublisher.class),
                        eq("application/json"),
                        anyInt());

        ExecutionResult result = executor.run("nonexistent");
        assertEquals(127, result.getExitCode());
        assertEquals("not found", result.getStderr());
    }

    @Test
    void run_httpError() throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        doReturn(500).when(resp).statusCode();
        doReturn("internal error").when(resp).body();

        doReturn(resp)
                .when(connector)
                .sendRequest(
                        eq("POST"),
                        eq("execute"),
                        any(HttpRequest.BodyPublisher.class),
                        eq("application/json"),
                        anyInt());

        assertThrows(SandboxRequestException.class, () -> executor.run("test"));
    }
}
