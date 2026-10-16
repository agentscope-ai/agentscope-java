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

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.SandboxManager;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import io.agentscope.harness.agent.sandbox.ExecResult;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/** Production bridge to the official OpenSandbox Java SDK. */
final class OfficialOpenSandboxSdk implements OpenSandboxSdk {
    private ConnectionConfig config(OpenSandboxClientOptions options) {
        OpenSandboxEndpoint endpoint = OpenSandboxEndpoint.parse(options.getEndpoint());
        return ConnectionConfig.builder()
                .domain(endpoint.domain())
                .protocol(endpoint.protocol())
                .apiKey(options.getApiKey())
                .requestTimeout(Duration.ofSeconds(options.getRequestTimeoutSeconds()))
                .useServerProxy(options.isUseServerProxy())
                .build();
    }

    @Override
    public Handle create(OpenSandboxState state, OpenSandboxClientOptions options) {
        Sandbox sandbox =
                Sandbox.builder()
                        .image(state.getImage())
                        .entrypoint(state.getEntrypoint())
                        .resource(state.getResourceLimits())
                        .timeout(Duration.ofSeconds(state.getSandboxTimeoutSeconds()))
                        .readyTimeout(Duration.ofSeconds(options.getReadyTimeoutSeconds()))
                        .connectionConfig(config(options))
                        .build();
        return new HandleImpl(sandbox);
    }

    @Override
    public Handle connect(String sandboxId, OpenSandboxClientOptions options) {
        Sandbox sandbox =
                Sandbox.connector()
                        .sandboxId(sandboxId)
                        .connectionConfig(config(options))
                        .connectTimeout(Duration.ofSeconds(options.getReadyTimeoutSeconds()))
                        .connect();
        return new HandleImpl(sandbox);
    }

    @Override
    public void kill(String sandboxId, OpenSandboxClientOptions options) {
        try (SandboxManager manager =
                SandboxManager.builder().connectionConfig(config(options)).build()) {
            manager.killSandbox(sandboxId);
        }
    }

    @Override
    public boolean isNotFound(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SandboxApiException apiException
                    && Integer.valueOf(404).equals(apiException.getStatusCode())) {
                return true;
            }
        }
        return false;
    }

    private static final class HandleImpl implements Handle {
        private final Sandbox sandbox;

        private HandleImpl(Sandbox sandbox) {
            this.sandbox = sandbox;
        }

        @Override
        public String id() {
            return sandbox.getId();
        }

        @Override
        public ExecResult exec(String command, String workingDirectory, int timeoutSeconds) {
            Execution execution =
                    sandbox.commands()
                            .run(
                                    RunCommandRequest.builder()
                                            .command(command)
                                            .workingDirectory(workingDirectory)
                                            .timeout(
                                                    Duration.ofSeconds(Math.max(timeoutSeconds, 1)))
                                            .build());
            String stdout =
                    output(
                            execution == null || execution.getLogs() == null
                                    ? null
                                    : execution.getLogs().getStdout());
            String stderr =
                    output(
                            execution == null || execution.getLogs() == null
                                    ? null
                                    : execution.getLogs().getStderr());
            int exitCode =
                    execution == null || execution.getExitCode() == null
                            ? -1
                            : execution.getExitCode();
            return new ExecResult(exitCode, stdout, stderr, false);
        }

        @Override
        public InputStream read(String absolutePath) {
            return sandbox.files().readStream(absolutePath);
        }

        @Override
        public void write(String absolutePath, byte[] content) {
            sandbox.files()
                    .writeFile(WriteEntry.builder().path(absolutePath).data(content).build());
        }

        @Override
        public void close() {
            sandbox.close();
        }

        private static String output(List<OutputMessage> messages) {
            if (messages == null || messages.isEmpty()) return "";
            StringBuilder result = new StringBuilder();
            for (OutputMessage message : messages) {
                if (message != null && message.getText() != null) result.append(message.getText());
            }
            return result.toString();
        }
    }
}
