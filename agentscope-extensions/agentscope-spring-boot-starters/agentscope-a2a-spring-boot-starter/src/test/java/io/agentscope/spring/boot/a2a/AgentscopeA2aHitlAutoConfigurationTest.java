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
package io.agentscope.spring.boot.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.hitl.A2aHitlDurabilityBinding;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityCapability;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityVerification;
import io.agentscope.core.a2a.server.hitl.HitlResumeCoordinator;
import io.agentscope.core.a2a.server.hitl.HitlSessionLease;
import io.agentscope.core.a2a.server.hitl.HitlTurnAdmission;
import io.agentscope.core.a2a.server.hitl.SanitizingTaskStore;
import io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class AgentscopeA2aHitlAutoConfigurationTest {

    @Test
    void defaultDisabledDoesNotRequireOrStartDurableBinding() {
        A2aHitlDurabilityBinding binding = mock(A2aHitlDurabilityBinding.class);

        baseRunner()
                .withBean(A2aHitlDurabilityBinding.class, () -> binding)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(AgentScopeA2aServer.class);
                            verifyNoInteractions(binding);
                        });

        verify(binding, never()).start();
    }

    @Test
    void explicitLocalDoesNotRequireDurableBinding() {
        baseRunner()
                .withPropertyValues(
                        "agentscope.a2a.server.hitl.enabled=true",
                        "agentscope.a2a.server.hitl.durability=local")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(AgentScopeA2aServer.class);
                        });
    }

    @Test
    void durableWithoutBindingFailsWithActionableProperty() {
        durableRunner()
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .hasMessageContaining(
                                                "exactly one A2aHitlDurabilityBinding")
                                        .hasMessageContaining(
                                                "agentscope.a2a.server.hitl.coordination-provider=redis"));
    }

    @Test
    void durableWithMultipleBindingsFails() {
        durableRunner()
                .withBean("bindingOne", A2aHitlDurabilityBinding.class, this::durableBinding)
                .withBean("bindingTwo", A2aHitlDurabilityBinding.class, this::durableBinding)
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .hasMessageContaining(
                                                "exactly one A2aHitlDurabilityBinding")
                                        .hasMessageContaining("found 2"));
    }

    @Test
    void durableUsesAllComponentsFromTheSameBindingAndManagesLifecycle() {
        TaskStore taskStore = new InMemoryTaskStore();
        HitlResumeCoordinator coordinator = durableCoordinator();
        HitlSessionLease lease = durableLease();
        A2aHitlDurabilityBinding binding = durableBinding(taskStore, coordinator, lease);

        durableRunner()
                .withBean(A2aHitlDurabilityBinding.class, () -> binding)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            AgentScopeA2aServer server = context.getBean(AgentScopeA2aServer.class);
                            HitlTurnAdmission admission = admission(server);
                            SanitizingTaskStore selectedTaskStore =
                                    (SanitizingTaskStore)
                                            ReflectionTestUtils.getField(admission, "taskStore");

                            assertThat(selectedTaskStore.delegate()).isSameAs(taskStore);
                            assertThat(ReflectionTestUtils.getField(admission, "coordinator"))
                                    .isSameAs(coordinator);
                            assertThat(ReflectionTestUtils.getField(admission, "sessionLease"))
                                    .isSameAs(lease);

                            InOrder order = inOrder(binding);
                            order.verify(binding).verify();
                            order.verify(binding).start();
                        });

        verify(binding).close();
    }

    @Test
    void verifyFailureNeverStartsBinding() {
        A2aHitlDurabilityBinding binding = durableBinding();
        when(binding.verify()).thenThrow(new IllegalStateException("topology mismatch"));

        durableRunner()
                .withBean(A2aHitlDurabilityBinding.class, () -> binding)
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .hasMessageContaining("topology mismatch"));

        verify(binding, never()).start();
        verify(binding).close();
    }

    @Test
    void standaloneTaskStoreCannotBeMixedWithDurableBinding() {
        rejectsStandaloneComponent(TaskStore.class, new InMemoryTaskStore());
    }

    @Test
    void standaloneCoordinatorCannotBeMixedWithDurableBinding() {
        rejectsStandaloneComponent(HitlResumeCoordinator.class, durableCoordinator());
    }

    @Test
    void standaloneLeaseCannotBeMixedWithDurableBinding() {
        rejectsStandaloneComponent(HitlSessionLease.class, durableLease());
    }

    private <T> void rejectsStandaloneComponent(Class<T> type, T component) {
        durableRunner()
                .withBean(A2aHitlDurabilityBinding.class, this::durableBinding)
                .withBean(type, () -> component)
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .hasMessageContaining(
                                                "durable components must come only from"
                                                        + " A2aHitlDurabilityBinding"));
    }

    private WebApplicationContextRunner baseRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                .withBean(AgentRunner.class, this::localRunner)
                .withPropertyValues("agentscope.a2a.server.enabled=true", "server.port=8080");
    }

    private WebApplicationContextRunner durableRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                .withBean(AgentRunner.class, this::durableAgentRunner)
                .withPropertyValues(
                        "agentscope.a2a.server.enabled=true",
                        "agentscope.a2a.server.hitl.enabled=true",
                        "agentscope.a2a.server.hitl.durability=durable",
                        "server.port=8080");
    }

    private AgentRunner localRunner() {
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.getAgentName()).thenReturn("local-agent");
        when(runner.getAgentDescription()).thenReturn("local agent");
        return runner;
    }

    private AgentRunner durableAgentRunner() {
        AgentRunner runner = localRunner();
        when(runner.hitlDurabilityCapability()).thenReturn(HitlDurabilityCapability.DURABLE);
        when(runner.actualAgentStateStore()).thenReturn(Optional.of(new PersistentStateStore()));
        return runner;
    }

    private A2aHitlDurabilityBinding durableBinding() {
        return durableBinding(new InMemoryTaskStore(), durableCoordinator(), durableLease());
    }

    private A2aHitlDurabilityBinding durableBinding(
            TaskStore taskStore, HitlResumeCoordinator coordinator, HitlSessionLease lease) {
        A2aHitlDurabilityBinding binding = mock(A2aHitlDurabilityBinding.class);
        when(binding.taskStore()).thenReturn(taskStore);
        when(binding.resumeCoordinator()).thenReturn(coordinator);
        when(binding.sessionLease()).thenReturn(lease);
        when(binding.verify())
                .thenReturn(new HitlDurabilityVerification("test-provider", "test-store"));
        return binding;
    }

    private HitlResumeCoordinator durableCoordinator() {
        HitlResumeCoordinator coordinator = mock(HitlResumeCoordinator.class);
        when(coordinator.durabilityCapability()).thenReturn(HitlDurabilityCapability.DURABLE);
        return coordinator;
    }

    private HitlSessionLease durableLease() {
        HitlSessionLease lease = mock(HitlSessionLease.class);
        when(lease.durabilityCapability()).thenReturn(HitlDurabilityCapability.DURABLE);
        return lease;
    }

    private HitlTurnAdmission admission(AgentScopeA2aServer server) {
        JsonRpcTransportWrapper wrapper =
                server.getTransportWrapper(
                        TransportProtocol.JSONRPC.asString(), JsonRpcTransportWrapper.class);
        Object jsonRpcHandler = ReflectionTestUtils.getField(wrapper, "jsonRpcHandler");
        AgentScopeA2aRequestHandler requestHandler =
                (AgentScopeA2aRequestHandler)
                        ReflectionTestUtils.getField(jsonRpcHandler, "requestHandler");
        return (HitlTurnAdmission)
                ReflectionTestUtils.getField(requestHandler, "hitlTurnAdmission");
    }

    private static final class PersistentStateStore implements AgentStateStore {
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
