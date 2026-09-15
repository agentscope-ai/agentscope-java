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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import org.junit.jupiter.api.Test;

class DockerDaemonFilesystemSpecTest {

    @Test
    void buildsSandboxContextWithDockerDaemonClient() {
        DockerDaemonFilesystemSpec spec =
                new DockerDaemonFilesystemSpec()
                        .image("python:3.12-slim")
                        .workspaceRoot("/data")
                        .daemonUrl("tcp://127.0.0.1:2375")
                        .isolationScope(IsolationScope.SESSION);

        SandboxContext context = spec.toSandboxContext();

        assertInstanceOf(DockerDaemonSandboxClient.class, context.getClient());
        DockerDaemonSandboxClientOptions options =
                (DockerDaemonSandboxClientOptions) context.getClientOptions();
        assertEquals("python:3.12-slim", options.getImage());
        assertEquals("/data", options.getWorkspaceRoot());
        assertEquals("tcp://127.0.0.1:2375", options.getDaemonUrl());
        assertEquals(IsolationScope.SESSION, context.getIsolationScope());
        assertEquals("/workspace", context.getWorkspaceSpec().getRoot());
    }

    @Test
    void mountPassesThroughToOptions() {
        DockerDaemonFilesystemSpec spec =
                new DockerDaemonFilesystemSpec()
                        .mount("bind", "/host/artifacts", "/app/artifacts")
                        .mount("volume", "mydata", "/data", true);

        SandboxContext context = spec.toSandboxContext();
        DockerDaemonSandboxClientOptions options =
                (DockerDaemonSandboxClientOptions) context.getClientOptions();

        assertEquals(2, options.getMounts().size());
        assertEquals(
                new MountSpec(
                        "bind",
                        WorkspaceMountSupport.normalizedHostPath("/host/artifacts"),
                        "/app/artifacts",
                        false),
                options.getMounts().get(0));
        assertEquals(new MountSpec("volume", "mydata", "/data", true), options.getMounts().get(1));
    }
}
