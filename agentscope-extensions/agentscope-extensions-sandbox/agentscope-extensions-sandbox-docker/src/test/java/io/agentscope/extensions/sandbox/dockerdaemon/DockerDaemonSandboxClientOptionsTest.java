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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerDaemonSandboxClientOptionsTest {

    @Test
    void hasDockerDaemonDefaults() {
        DockerDaemonSandboxClientOptions options = new DockerDaemonSandboxClientOptions();

        assertEquals("docker-daemon", options.getType());
        assertEquals("ubuntu:22.04", options.getImage());
        assertEquals("/workspace", options.getWorkspaceRoot());
        assertNull(options.getMemorySizeBytes());
        assertNull(options.getCpuCount());
        assertNull(options.getNetwork());
        assertNull(options.getDaemonUrl());
        assertArrayEquals(new int[0], options.getExposedPorts());
    }

    @Test
    void fluentSettersRoundTrip() {
        DockerDaemonSandboxClientOptions options =
                new DockerDaemonSandboxClientOptions()
                        .image("python:3.12-slim")
                        .workspaceRoot("/data")
                        .memorySizeBytes(512L * 1024 * 1024)
                        .cpuCount(2L)
                        .exposedPorts(8080, 9090)
                        .network("bridge")
                        .daemonUrl("tcp://192.168.1.10:2375");

        assertEquals("python:3.12-slim", options.getImage());
        assertEquals("/data", options.getWorkspaceRoot());
        assertEquals(512L * 1024 * 1024, options.getMemorySizeBytes());
        assertEquals(2L, options.getCpuCount());
        assertArrayEquals(new int[] {8080, 9090}, options.getExposedPorts());
        assertEquals("bridge", options.getNetwork());
        assertEquals("tcp://192.168.1.10:2375", options.getDaemonUrl());
    }

    @Test
    void blankDaemonUrlAndNetworkNormalizeToNull() {
        DockerDaemonSandboxClientOptions options = new DockerDaemonSandboxClientOptions();
        options.setDaemonUrl("   ");
        options.setNetwork("  ");

        assertNull(options.getDaemonUrl());
        assertNull(options.getNetwork());
    }

    @Test
    void createClientReturnsDockerDaemonClient() {
        SandboxClient<?> client = new DockerDaemonSandboxClientOptions().createClient();

        assertInstanceOf(DockerDaemonSandboxClient.class, client);
    }

    @Test
    void mountAccumulatesTypedSpecs() {
        DockerDaemonSandboxClientOptions options =
                new DockerDaemonSandboxClientOptions()
                        .mount("bind", "/host/artifacts", "/app/artifacts")
                        .mount("bind", "/host/config", "/etc/app", true)
                        .mount("volume", "mydata", "/data")
                        .mount("tmpfs", null, "/tmp/shm");

        List<MountSpec> mounts = options.getMounts();
        assertEquals(4, mounts.size());
        assertEquals(
                new MountSpec(
                        "bind",
                        WorkspaceMountSupport.normalizedHostPath("/host/artifacts"),
                        "/app/artifacts",
                        false),
                mounts.get(0));
        assertEquals(
                new MountSpec(
                        "bind",
                        WorkspaceMountSupport.normalizedHostPath("/host/config"),
                        "/etc/app",
                        true),
                mounts.get(1));
        assertEquals(new MountSpec("volume", "mydata", "/data", false), mounts.get(2));
        assertEquals(new MountSpec("tmpfs", null, "/tmp/shm", false), mounts.get(3));
    }

    @Test
    void mountRejectsUnknownType() {
        DockerDaemonSandboxClientOptions options = new DockerDaemonSandboxClientOptions();
        assertThrows(IllegalArgumentException.class, () -> options.mount("nfs", "/x", "/y"));
    }

    @Test
    void mountRejectsBlankSource() {
        DockerDaemonSandboxClientOptions options = new DockerDaemonSandboxClientOptions();
        assertThrows(IllegalArgumentException.class, () -> options.mount("bind", "   ", "/y"));
    }

    @Test
    void mountRejectsBlankTarget() {
        DockerDaemonSandboxClientOptions options = new DockerDaemonSandboxClientOptions();
        assertThrows(IllegalArgumentException.class, () -> options.mount("volume", "mydata", " "));
    }

    @Test
    void mountNormalizesBindSource() {
        DockerDaemonSandboxClientOptions options = new DockerDaemonSandboxClientOptions();
        options.mount("bind", "  /host/artifacts  ", "/app/artifacts");

        assertEquals(
                WorkspaceMountSupport.normalizedHostPath("/host/artifacts"),
                options.getMounts().get(0).source());
    }

    @Test
    void toDockerJavaMountConvertsAllTypes() {
        Mount bindMount =
                MountSpec.of("bind", "/host/artifacts", "/app/artifacts", true).toDockerJavaMount();
        assertEquals(MountType.BIND, bindMount.getType());
        assertEquals(
                WorkspaceMountSupport.normalizedHostPath("/host/artifacts"), bindMount.getSource());
        assertEquals("/app/artifacts", bindMount.getTarget());
        assertEquals(Boolean.TRUE, bindMount.getReadOnly());

        Mount volumeMount = MountSpec.of("volume", "mydata", "/data", false).toDockerJavaMount();
        assertEquals(MountType.VOLUME, volumeMount.getType());
        assertEquals("mydata", volumeMount.getSource());
        assertEquals("/data", volumeMount.getTarget());
        assertEquals(Boolean.FALSE, volumeMount.getReadOnly());

        Mount tmpfsMount = MountSpec.of("tmpfs", null, "/tmp/shm", false).toDockerJavaMount();
        assertEquals(MountType.TMPFS, tmpfsMount.getType());
        assertNull(tmpfsMount.getSource());
        assertEquals("/tmp/shm", tmpfsMount.getTarget());
    }

    @Test
    void toDockerJavaMountRejectsUnsupportedType() {
        MountSpec spec = new MountSpec("nfs", "/host/data", "/data", false);

        assertThrows(
                IllegalStateException.class, () -> spec.toDockerJavaMount(), "Unsupported type");
    }
}
