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
package io.agentscope.builder.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import org.junit.jupiter.api.Test;

class BuilderConfigFilesystemModeTest {

    @Test
    void autoModeSelectsLocalFilesystemForInProcessStateStore() {
        BuilderConfig.FilesystemMode mode =
                BuilderConfig.resolveFilesystemMode("auto", new InMemoryAgentStateStore());

        assertThat(mode).isEqualTo(BuilderConfig.FilesystemMode.LOCAL);
    }

    @Test
    void autoModeSelectsRemoteFilesystemForDistributedStateStore() {
        AgentStateStore distributedStore = mock(AgentStateStore.class);

        BuilderConfig.FilesystemMode mode =
                BuilderConfig.resolveFilesystemMode("auto", distributedStore);

        assertThat(mode).isEqualTo(BuilderConfig.FilesystemMode.REMOTE);
    }

    @Test
    void rejectsRemoteFilesystemWithLocalStateStore() {
        assertThatThrownBy(
                        () ->
                                BuilderConfig.resolveFilesystemMode(
                                        "remote", new InMemoryAgentStateStore()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a distributed AgentStateStore");
    }

    @Test
    void rejectsUnknownFilesystemMode() {
        assertThatThrownBy(
                        () ->
                                BuilderConfig.resolveFilesystemMode(
                                        "unsupported", new InMemoryAgentStateStore()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auto, local, sandbox, remote");
    }

    @Test
    void configuresLocalFilesystemSpec() {
        HarnessAgent.Builder builder = mock(HarnessAgent.Builder.class);

        BuilderConfig.configureFilesystem(
                builder, BuilderConfig.FilesystemMode.LOCAL, mock(BaseStore.class), null);

        verify(builder).filesystem(any(LocalFilesystemSpec.class));
    }

    @Test
    void configuresRemoteFilesystemSpec() {
        HarnessAgent.Builder builder = mock(HarnessAgent.Builder.class);

        BuilderConfig.configureFilesystem(
                builder, BuilderConfig.FilesystemMode.REMOTE, mock(BaseStore.class), null);

        verify(builder).filesystem(any(RemoteFilesystemSpec.class));
    }

    @Test
    void configuresProvidedSandboxFilesystemSpec() {
        HarnessAgent.Builder builder = mock(HarnessAgent.Builder.class);
        SandboxFilesystemSpec sandboxFilesystemSpec = mock(SandboxFilesystemSpec.class);

        BuilderConfig.configureFilesystem(
                builder,
                BuilderConfig.FilesystemMode.SANDBOX,
                mock(BaseStore.class),
                sandboxFilesystemSpec);

        verify(builder).filesystem(same(sandboxFilesystemSpec));
    }
}
