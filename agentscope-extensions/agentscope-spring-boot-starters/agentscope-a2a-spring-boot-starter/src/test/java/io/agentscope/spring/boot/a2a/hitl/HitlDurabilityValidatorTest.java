/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.a2a.hitl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.hitl.A2aHitlDurabilityBinding;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityCapability;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityVerification;
import io.agentscope.core.a2a.server.hitl.HitlResumeCoordinator;
import io.agentscope.core.a2a.server.hitl.HitlServerProperties;
import io.agentscope.core.a2a.server.hitl.HitlSessionLease;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.State;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class HitlDurabilityValidatorTest {

    @Test
    void disabledAndLocalModesDoNotInspectBindingOrRunner() {
        AgentRunner runner = mock(AgentRunner.class);
        A2aHitlDurabilityBinding binding = mock(A2aHitlDurabilityBinding.class);

        assertThat(HitlDurabilityValidator.validate(disabledProperties(), runner, binding))
                .isNull();
        assertThat(HitlDurabilityValidator.validate(localProperties(), runner, binding)).isNull();

        verifyNoInteractions(runner, binding);
    }

    @Test
    void missingBindingFailureNamesTheRedisCoordinationProperty() {
        assertThatThrownBy(
                        () ->
                                HitlDurabilityValidator.validate(
                                        durableProperties(), mock(AgentRunner.class), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one A2aHitlDurabilityBinding")
                .hasMessageContaining("agentscope.a2a.server.hitl.coordination-provider=redis");
    }

    @Test
    void durableModeRejectsUnsupportedRunner() {
        AgentRunner runner = runner(HitlDurabilityCapability.UNSUPPORTED, persistentStore());

        assertThatThrownBy(
                        () ->
                                HitlDurabilityValidator.validate(
                                        durableProperties(), runner, durableBinding()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("runner")
                .hasMessageContaining("DURABLE");
    }

    @Test
    void durableModeRejectsRunnerWithoutExposedStore() {
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.hitlDurabilityCapability()).thenReturn(HitlDurabilityCapability.DURABLE);
        when(runner.actualAgentStateStore()).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                HitlDurabilityValidator.validate(
                                        durableProperties(), runner, durableBinding()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("actual AgentStateStore");
    }

    @Test
    void durableModeRejectsInMemoryStore() {
        AgentRunner runner =
                runner(HitlDurabilityCapability.DURABLE, new InMemoryAgentStateStore());

        assertThatThrownBy(
                        () ->
                                HitlDurabilityValidator.validate(
                                        durableProperties(), runner, durableBinding()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InMemory")
                .hasMessageContaining("JsonFile");
    }

    @Test
    void durableModeRejectsJsonFileStore(@TempDir Path directory) {
        AgentRunner runner =
                runner(HitlDurabilityCapability.DURABLE, new JsonFileAgentStateStore(directory));

        assertThatThrownBy(
                        () ->
                                HitlDurabilityValidator.validate(
                                        durableProperties(), runner, durableBinding()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InMemory")
                .hasMessageContaining("JsonFile");
    }

    @Test
    void independentApplicationStorePassesAndLogsResponsibilityBoundary(CapturedOutput output) {
        MySqlLikeAgentStateStore stateStore = persistentStore();
        HitlDurabilityVerification result =
                HitlDurabilityValidator.validate(
                        durableProperties(),
                        runner(HitlDurabilityCapability.DURABLE, stateStore),
                        durableBinding());

        assertThat(result).isEqualTo(new HitlDurabilityVerification("test-provider", "test-store"));
        assertThat(output.getOut())
                .contains(
                        "A2A_HITL_CONTROL_PLANE_DURABLE_OK coordinationProvider=test-provider"
                                + " coordinationStoreId=test-store")
                .contains(
                        "A2A_HITL_AGENT_STATE_DECLARED storeType="
                                + MySqlLikeAgentStateStore.class.getName()
                                + " crossReplicaReachability=application-responsibility");
    }

    private static HitlServerProperties disabledProperties() {
        return HitlServerProperties.builder().enabled(false).build();
    }

    private static HitlServerProperties localProperties() {
        return HitlServerProperties.builder()
                .enabled(true)
                .durability(HitlServerProperties.Durability.LOCAL)
                .build();
    }

    private static HitlServerProperties durableProperties() {
        return HitlServerProperties.builder()
                .enabled(true)
                .durability(HitlServerProperties.Durability.DURABLE)
                .build();
    }

    private static AgentRunner runner(
            HitlDurabilityCapability capability, AgentStateStore stateStore) {
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.hitlDurabilityCapability()).thenReturn(capability);
        when(runner.actualAgentStateStore()).thenReturn(Optional.ofNullable(stateStore));
        return runner;
    }

    private static A2aHitlDurabilityBinding durableBinding() {
        A2aHitlDurabilityBinding binding = mock(A2aHitlDurabilityBinding.class);
        HitlResumeCoordinator coordinator = mock(HitlResumeCoordinator.class);
        HitlSessionLease lease = mock(HitlSessionLease.class);
        when(coordinator.durabilityCapability()).thenReturn(HitlDurabilityCapability.DURABLE);
        when(lease.durabilityCapability()).thenReturn(HitlDurabilityCapability.DURABLE);
        when(binding.taskStore()).thenReturn(new InMemoryTaskStore());
        when(binding.resumeCoordinator()).thenReturn(coordinator);
        when(binding.sessionLease()).thenReturn(lease);
        when(binding.verify())
                .thenReturn(new HitlDurabilityVerification("test-provider", "test-store"));
        return binding;
    }

    private static MySqlLikeAgentStateStore persistentStore() {
        return new MySqlLikeAgentStateStore();
    }

    private static final class MySqlLikeAgentStateStore implements AgentStateStore {
        @Override
        public void save(String userId, String sessionId, String key, State value) {}

        @Override
        public void save(
                String userId, String sessionId, String key, List<? extends State> values) {}

        @Override
        public <T extends State> Optional<T> get(
                String userId, String sessionId, String key, Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends State> List<T> getList(
                String userId, String sessionId, String key, Class<T> itemType) {
            return List.of();
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return false;
        }

        @Override
        public void delete(String userId, String sessionId) {}

        @Override
        public Set<String> listSessionIds(String userId) {
            return Set.of();
        }
    }
}
