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
package io.agentscope.harness.agent.sandbox.impl.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for issue #3544: both {@code additionalRunArgs} overloads on {@link
 * DockerFilesystemSpec} must append, so the result does not depend on which overload is called
 * first.
 */
class DockerFilesystemSpecTest {

    private static List<String> runArgs(DockerFilesystemSpec spec) {
        return ((DockerSandboxClientOptions) spec.clientOptions()).getAdditionalRunArgs();
    }

    @Test
    void listOverloadAppendsAfterVarargs() {
        DockerFilesystemSpec spec =
                new DockerFilesystemSpec()
                        .additionalRunArgs("--cap-add", "SYS_PTRACE")
                        .additionalRunArgs(List.of("--shm-size=1g"));

        assertEquals(List.of("--cap-add", "SYS_PTRACE", "--shm-size=1g"), runArgs(spec));
    }

    @Test
    void varargsOverloadAppendsAfterList() {
        DockerFilesystemSpec spec =
                new DockerFilesystemSpec()
                        .additionalRunArgs(List.of("--shm-size=1g"))
                        .additionalRunArgs("--cap-add", "SYS_PTRACE");

        assertEquals(List.of("--shm-size=1g", "--cap-add", "SYS_PTRACE"), runArgs(spec));
    }

    @Test
    void repeatedListCallsAccumulate() {
        DockerFilesystemSpec spec =
                new DockerFilesystemSpec()
                        .additionalRunArgs(List.of("--a"))
                        .additionalRunArgs(List.of("--b"));

        assertEquals(List.of("--a", "--b"), runArgs(spec));
    }

    @Test
    void listOverloadSkipsNullListAndBlankEntries() {
        DockerFilesystemSpec spec =
                new DockerFilesystemSpec()
                        .additionalRunArgs("--a")
                        .additionalRunArgs((List<String>) null)
                        .additionalRunArgs(Arrays.asList("--b", null, " ", "--c"));

        assertEquals(List.of("--a", "--b", "--c"), runArgs(spec));
    }

    @Test
    void optionsSetterStillReplaces() {
        DockerSandboxClientOptions options =
                new DockerSandboxClientOptions().additionalRunArgs("--a");

        options.setAdditionalRunArgs(List.of("--b"));

        assertEquals(List.of("--b"), options.getAdditionalRunArgs());
    }
}
