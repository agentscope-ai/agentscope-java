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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.RemoteApiVersion;
import java.net.URI;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class DockerDaemonClientFactoryTest {

    @Test
    void resolveDaemonUrlPrefersOptionOverEnv() {
        assertEquals(
                "tcp://10.0.0.1:2375",
                DockerDaemonClientFactory.resolveDaemonUrl(
                        "tcp://10.0.0.1:2375", "tcp://10.0.0.2:2375"));
    }

    @Test
    void resolveDaemonUrlFallsBackToEnv() {
        assertEquals(
                "tcp://10.0.0.2:2375",
                DockerDaemonClientFactory.resolveDaemonUrl(null, "tcp://10.0.0.2:2375"));
    }

    @Test
    void resolveDaemonUrlReturnsNullWhenBothAbsent() {
        assertNull(DockerDaemonClientFactory.resolveDaemonUrl(null, null));
        assertNull(DockerDaemonClientFactory.resolveDaemonUrl("  ", ""));
    }

    @Test
    void buildConfigFreezesDaemonUrl() {
        DockerDaemonSandboxState state = new DockerDaemonSandboxState();
        state.setDaemonUrl("tcp://127.0.0.1:2375");

        DefaultDockerClientConfig config = DockerDaemonClientFactory.buildConfig(state);

        assertEquals(URI.create("tcp://127.0.0.1:2375"), config.getDockerHost());
        assertEquals(RemoteApiVersion.parseConfig("1.45"), config.getApiVersion());
    }

    @Test
    void buildConfigUsesDefaultHostWithoutDaemonUrl() {
        Assumptions.assumeTrue(
                System.getenv("DOCKER_HOST") == null, "DOCKER_HOST not set in environment");

        DefaultDockerClientConfig config =
                DockerDaemonClientFactory.buildConfig(new DockerDaemonSandboxState());

        assertNotNull(config.getDockerHost());
    }

    @Test
    void buildClientReturnsUsableClient() throws Exception {
        DockerDaemonSandboxState state = new DockerDaemonSandboxState();
        state.setDaemonUrl("tcp://127.0.0.1:2375");

        DockerClient client = DockerDaemonClientFactory.buildClient(state);

        assertNotNull(client);
        client.close();
    }
}
