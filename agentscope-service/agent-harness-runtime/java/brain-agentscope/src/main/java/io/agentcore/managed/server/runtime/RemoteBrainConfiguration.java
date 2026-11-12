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
package io.agentcore.managed.server.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.SessionExecutionAccess;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.control.ControlPlaneClient;
import io.agentcore.managed.server.runtime.managed.AgentScopeInfrastructureConfiguration;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedBatchCodec;
import io.agentcore.managed.server.runtime.managed.ManagedBudgetStore;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointObjects;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessTurns;
import io.agentcore.managed.server.runtime.managed.ManagedHttpExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedHttpPreviews;
import io.agentcore.managed.server.runtime.managed.ManagedInputDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedMcpDiscovery;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeWriter;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSubagents;
import io.agentcore.managed.server.runtime.managed.ManagedToolRequests;
import io.agentcore.managed.server.runtime.managed.ManagedToolResult;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultDelivery;
import io.agentcore.managed.server.runtime.managed.RemoteRuntimeObjectStore;
import io.agentcore.managed.server.runtime.managed.RuntimeObjectStore;
import io.agentcore.managed.server.web.auth.InternalTokenStartupValidator;
import io.agentcore.managed.server.web.auth.JwtService;
import io.agentcore.managed.server.web.catalog.BaseStoreDefinitionStore;
import io.agentcore.managed.server.web.catalog.HarnessAgentBuildService;
import io.agentcore.managed.server.web.catalog.JevServiceSupport;
import io.agentcore.managed.server.web.catalog.LoadTestModels;
import io.agentcore.managed.server.web.catalog.ManagedAgentModels;
import io.agentcore.managed.server.web.config.DataSecurityConfig;
import io.agentcore.managed.server.web.config.HarnessRuntimeConfig;
import io.agentcore.managed.server.web.managed.MemoryMountService;
import io.agentcore.managed.server.web.managed.SessionResourceMountService;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import io.agentcore.managed.server.web.toolbus.ToolConfirmationMiddleware;
import io.agentcore.managed.server.web.toolbus.ToolEventBus;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Brain-only composition: no persistence scan, platform pool, broker or Redis client. */
@Configuration(proxyBeanMethods = false)
@Import({
    HarnessRuntimeConfig.class,
    DataSecurityConfig.class,
    JwtService.class,
    InternalTokenStartupValidator.class,
    AgentScopeInfrastructureConfiguration.class,
    VaultCrypto.class,
    ManagedCatalog.class,
    HarnessAgentBuildService.class,
    ManagedAgentModels.class,
    LoadTestModels.class,
    JevServiceSupport.class,
    BaseStoreDefinitionStore.class,
    ControlPlaneClient.class,
    ToolEventBus.class,
    ToolConfirmationMiddleware.class,
    SessionResourceMountService.class,
    BrainJsonConfiguration.class,
    BrainNotificationApi.class
})
public class RemoteBrainConfiguration {
    @Bean
    SessionExecutionAccess executionAccess(SessionServiceClient client, ObjectMapper mapper) {
        return new SessionExecutionAccess(client, mapper);
    }

    @Bean
    ManagedHttpExecutions httpExecutions(SessionExecutionAccess access) {
        return new ManagedHttpExecutions(access);
    }

    @Bean
    ManagedAdmissionStore admission(
            SessionServiceClient client, ObjectMapper mapper, ManagedHttpExecutions executions) {
        var store = new ManagedAdmissionStore(client, mapper, null);
        store.executionRequests(executions::request);
        return store;
    }

    @Bean
    RuntimeObjectStore objects(
            SessionServiceClient client,
            ObjectMapper mapper,
            @Value("${managed-agents.object-store.backend-id:runtime}") String backend) {
        return new RemoteRuntimeObjectStore(client, backend, mapper);
    }

    @Bean
    ManagedCheckpointObjects checkpointObjects(RuntimeObjectStore objects, ObjectMapper mapper) {
        return new ManagedCheckpointObjects(mapper, objects);
    }

    @Bean
    ManagedBudgetStore budgets(SessionServiceClient client, ObjectMapper mapper) {
        return new ManagedBudgetStore(client, mapper);
    }

    @Bean
    ManagedJournalStore journal(
            SessionServiceClient client,
            ObjectMapper mapper,
            ManagedBudgetStore budgets,
            ManagedCheckpointObjects objects) {
        return new ManagedJournalStore(client, mapper, budgets, objects);
    }

    @Bean(destroyMethod = "close")
    ManagedHttpPreviews previews(
            ManagedJournalStore journal,
            ManagedHttpExecutions executions,
            ObjectMapper mapper,
            @Value(
                            "${managed-agents.session-service.url:http://localhost:8084/internal/session-service/v0}")
                    String url,
            @Value("${managed-agents.internal-token:${MANAGED_AGENTS_INTERNAL_TOKEN:}}")
                    String token) {
        var previews =
                new ManagedHttpPreviews(
                        new SessionServiceClient(
                                URI.create(url), token, mapper, Duration.ofMillis(500)),
                        executions);
        journal.previewFactory(previews);
        return previews;
    }

    @Bean
    ManagedSubagents children(SessionServiceClient client, ObjectMapper mapper) {
        return new ManagedSubagents(client, mapper);
    }

    @Bean
    ManagedInputDelivery inputs(ManagedAdmissionStore admission) {
        return new ManagedInputDelivery(admission);
    }

    @Bean
    ManagedBatchCodec batchCodec(ObjectMapper mapper, RuntimeObjectStore objects) {
        return new ManagedBatchCodec(mapper, objects);
    }

    @Bean
    ManagedToolResultDelivery results(ManagedJournalStore journal, ManagedBatchCodec codec) {
        return new ManagedToolResultDelivery() {
            public void publish(
                    ManagedScope scope, String task, ManagedMessageBus.Message message) {
                throw new UnsupportedOperationException(
                        "Only Session Service accepts tool results");
            }

            public Optional<ManagedMessageBus.Message> await(
                    ManagedScope scope, String task, Duration block) {
                var result = journal.tool(scope, task).filter(value -> value.result() != null);
                if (result.isPresent()) {
                    var value = result.get();
                    return Optional.of(
                            codec.encode(
                                    new ManagedToolResult(
                                            scope,
                                            task,
                                            value.executionId(),
                                            value.status(),
                                            value.result())));
                }
                try {
                    Thread.sleep(Math.min(200, Math.max(1, block.toMillis())));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }
        };
    }

    @Bean
    ManagedMcpCatalog mcpCatalog(
            SessionServiceClient client, ObjectMapper mapper, VaultCrypto crypto) {
        return new ManagedMcpCatalog(client, mapper, crypto, new ManagedMcpDiscovery());
    }

    @Bean
    MemoryMountService memoryMounts() {
        return new MemoryMountService();
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService httpBrainControls() {
        return Executors.newScheduledThreadPool(8);
    }

    @Bean(destroyMethod = "close")
    SessionServiceBrainWorker brainWorker(
            SessionExecutionAccess access,
            ManagedHttpExecutions executions,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            ManagedRuntimeWriter writer,
            ObjectMapper mapper,
            ManagedCatalog catalog,
            HarnessAgentBuildService agents,
            ManagedSubagents children,
            ManagedToolRequests requests,
            ManagedBudgetStore budgets,
            ManagedInputDelivery inputs,
            @Qualifier("httpBrainControls") ScheduledExecutorService controls,
            @Value("${managed-agents.runtime.concurrency:16}") int concurrency,
            @Value("${managed-agents.instance-id:}") String instanceId) {
        var harness = new ManagedHarnessTurns(mapper, inputs, controls);
        return new SessionServiceBrainWorker(
                access,
                executions,
                admission,
                journal,
                writer,
                concurrency,
                instanceId,
                execution -> {
                    try {
                        if (harness.cancelBeforePreparation(
                                execution,
                                () ->
                                        agents.managedPinnedMcpTools(
                                                execution.scope(),
                                                catalog.resolve(execution.scope()),
                                                execution.turnId()))) return;
                        agents.prepareManagedToolsAsync(
                                        execution.scope(),
                                        catalog.resolve(execution.scope()),
                                        execution.turnId())
                                .block(Duration.ofSeconds(60));
                        var prepared =
                                ManagedHarnessRuntimeConfiguration.buildExecutionAgent(
                                        execution, catalog, agents, children, requests, budgets,
                                        admission, mapper);
                        try {
                            harness.run(execution, prepared.agent(), prepared.context());
                        } finally {
                            prepared.agent().close();
                        }
                    } catch (RuntimeException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new IllegalStateException("Native execution failed", failure);
                    }
                });
    }
}
