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
package io.agentcore.managed.server.toolworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedAsyncToolExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedBatchCodec;
import io.agentcore.managed.server.runtime.managed.ManagedConsumerLoop;
import io.agentcore.managed.server.runtime.managed.ManagedExecutionTargets;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedLifecycles;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedProcessOwnership;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommitGate;
import io.agentcore.managed.server.runtime.managed.ManagedToolControlFanout;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor;
import io.agentcore.managed.server.runtime.managed.ManagedToolResult;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultPublisher;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerCommands;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerLoop;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerStore;
import io.agentcore.managed.server.runtime.managed.RedisManagedInfrastructure;
import io.agentcore.managed.server.runtime.managed.RocketMqManagedMessageBus;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ManagedToolWorkerConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ManagedToolWorkerConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper managedToolWorkerObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService managedToolWorkerRenewals(
            @Value("${managed-agents.runtime.tool-renewal-threads:16}") int concurrency) {
        return Executors.newScheduledThreadPool(concurrency);
    }

    @Bean
    ManagedToolWorkerStore managedToolWorkerStore(
            DataSource source,
            ObjectMapper mapper,
            ObjectProvider<ManagedProcessOwnership> ownership) {
        var store = new ManagedToolWorkerStore(source, mapper);
        ownership.ifAvailable(store::processOwnership);
        return store;
    }

    @Bean
    ManagedExecutionTargets managedToolWorkerCatalog(
            ManagedToolWorkerStore store, ObjectMapper mapper, VaultCrypto crypto) {
        return new ManagedToolWorkerCatalog(store, mapper, crypto);
    }

    @Bean(destroyMethod = "close")
    ManagedMcpExecutors managedMcpExecutors(
            ManagedToolWorkerStore store,
            ManagedToolResultPublisher results,
            ObjectMapper mapper,
            VaultCrypto crypto,
            @Value("${managed-agents.runtime.mcp.cached-clients:1024}") int capacity,
            @Value("${managed-agents.runtime.mcp.worker-threads:700}") int workers) {
        return new ManagedMcpExecutors(store, results, mapper, crypto, capacity, workers);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedToolWorkerLoop managedToolWorkerLoop(
            ManagedToolWorkerStore store,
            ManagedAsyncToolExecutions executions,
            ManagedToolResultPublisher results,
            ManagedSandboxProviders providers,
            ManagedMcpExecutors mcp,
            RocketMqManagedMessageBus bus,
            ManagedBatchCodec codec,
            @Value("${managed-agents.runtime.tool-concurrency:700}") int concurrency,
            @Value("${managed-agents.runtime.tool-inflight-capacity:16384}") int inflight,
            @Value("${managed-agents.runtime.tool-handoff-capacity:64}") int handoffs,
            @Value("${managed-agents.runtime.tool-repair-period:30s}") String repairPeriod) {
        var loop =
                new ManagedToolWorkerLoop(
                        store,
                        executions,
                        concurrency,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        (scope, task) -> provider(scope, task, store, results, providers, mcp),
                        (task, failure) ->
                                log.warn("Tool execution recovery pending: {}", failure.toString()),
                        inflight,
                        handoffs,
                        DurationStyle.detectAndParse(repairPeriod));
        loop.republication(
                (scope, id) -> {
                    var command = store.command(scope, id);
                    bus.publish(Channel.TOOL_EXECUTE, codec.encode(command));
                });
        return loop;
    }

    @Bean(destroyMethod = "close")
    ManagedToolControlFanout managedToolControlFanout(
            RedisManagedInfrastructure redis, ObjectMapper mapper, ManagedToolWorkerLoop loop) {
        return new ManagedToolControlFanout(redis, mapper, loop::signalLocal);
    }

    @Bean
    ManagedToolWorkerCommands managedToolWorkerCommands(
            ManagedToolWorkerStore store,
            ManagedBatchCodec codec,
            ManagedToolResultPublisher results,
            ManagedToolWorkerLoop loop,
            ManagedToolControlFanout controls,
            DataSource source,
            ObjectMapper mapper) {
        var commands =
                new ManagedToolWorkerCommands(
                        store,
                        codec,
                        results,
                        loop,
                        new ManagedToolCommitGate(source, mapper)::check);
        commands.controlWakeup(controls::wakeup);
        return commands;
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedConsumerLoop managedToolCommandsConsumer(
            RocketMqManagedMessageBus bus,
            ManagedToolWorkerCommands commands,
            @Value("${managed-agents.runtime.tool-callbacks:16}") int concurrency) {
        return new ManagedConsumerLoop(
                bus,
                Channel.TOOL_EXECUTE,
                concurrency,
                commands,
                (delivery, failure) ->
                        log.warn("Tool execution remains pending: {}", failure.toString()));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedConsumerLoop managedToolControlConsumer(
            RocketMqManagedMessageBus bus,
            ManagedToolWorkerCommands commands,
            @Value("${managed-agents.runtime.tool-control-concurrency:4}") int concurrency) {
        return new ManagedConsumerLoop(
                bus,
                Channel.TOOL_CONTROL,
                concurrency,
                commands,
                (delivery, failure) ->
                        log.warn("Tool control remains pending: {}", failure.toString()));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedToolResultPublisher managedToolResultPublisher(
            ManagedToolWorkerStore journal,
            RocketMqManagedMessageBus bus,
            ManagedBatchCodec codec) {
        return new ManagedToolResultPublisher(journal, bus, codec);
    }

    @Bean
    ManagedAsyncToolExecutions managedAsyncToolExecutions(
            RedisManagedInfrastructure redis,
            ManagedToolWorkerStore journal,
            ManagedToolResultPublisher results,
            ScheduledExecutorService managedToolWorkerRenewals) {
        return new ManagedAsyncToolExecutions(
                redis,
                journal,
                results,
                managedToolWorkerRenewals,
                Duration.ofSeconds(60),
                Duration.ofSeconds(15),
                Duration.ofSeconds(1));
    }

    private ManagedToolExecutor provider(
            ManagedScope scope,
            String id,
            ManagedToolWorkerStore store,
            ManagedToolResultPublisher results,
            ManagedSandboxProviders providers,
            ManagedMcpExecutors mcp) {
        var task = store.tool(scope, id).orElseThrow();
        if (task.name().startsWith("mcp.")) return mcp.executor(scope, id);
        if (!task.name().equals("sandbox.cleanup")) return providers.preparedExecutor(scope);
        return new ManagedToolExecutor() {
            public boolean idempotentStart() {
                return true;
            }

            public String start(ManagedScope owner, ManagedJournalStore.ToolTask value) {
                try {
                    var command =
                            new ObjectMapper()
                                    .readValue(value.arguments(), ManagedLifecycles.Command.class);
                    providers.cleanup(command, () -> {});
                    results.capture(
                            new ManagedToolResult(
                                    owner, value.id(), value.executionId(), "succeeded", "{}"));
                    return value.executionId();
                } catch (RuntimeException failure) {
                    throw failure;
                } catch (Exception invalid) {
                    throw new IllegalArgumentException("Invalid cleanup command", invalid);
                }
            }

            public Outcome query(ManagedScope owner, ManagedJournalStore.ToolTask value) {
                return store.stagedToolResult(owner, value.id())
                        .map(result -> new Outcome(State.SUCCEEDED, result.result()))
                        .orElse(new Outcome(State.NOT_FOUND, null));
            }

            public Outcome cancel(ManagedScope owner, ManagedJournalStore.ToolTask value) {
                return query(owner, value);
            }
        };
    }
}
