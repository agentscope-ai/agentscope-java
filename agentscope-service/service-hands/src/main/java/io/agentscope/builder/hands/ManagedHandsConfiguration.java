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
package io.agentscope.builder.hands;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedBatchCodec;
import io.agentscope.builder.runtime.managed.ManagedConsumerLoop;
import io.agentscope.builder.runtime.managed.ManagedExecutionTargets;
import io.agentscope.builder.runtime.managed.ManagedHandsCommands;
import io.agentscope.builder.runtime.managed.ManagedHandsLoop;
import io.agentscope.builder.runtime.managed.ManagedHandsStore;
import io.agentscope.builder.runtime.managed.ManagedJournalStore;
import io.agentscope.builder.runtime.managed.ManagedLifecycles;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedToolCommitGate;
import io.agentscope.builder.runtime.managed.ManagedToolExecutions;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor;
import io.agentscope.builder.runtime.managed.ManagedToolResult;
import io.agentscope.builder.runtime.managed.ManagedToolResultPublisher;
import io.agentscope.builder.runtime.managed.RedisManagedInfrastructure;
import io.agentscope.builder.runtime.managed.RocketMqManagedMessageBus;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ManagedHandsConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ManagedHandsConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper managedHandsObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService managedHandsRenewals(
            @Value("${builder.managed.tool-concurrency:16}") int concurrency) {
        return Executors.newScheduledThreadPool(concurrency);
    }

    @Bean
    ManagedHandsStore managedHandsStore(DataSource source, ObjectMapper mapper) {
        return new ManagedHandsStore(source, mapper);
    }

    @Bean
    ManagedExecutionTargets managedHandsCatalog(
            ManagedHandsStore store, ObjectMapper mapper, VaultCrypto crypto) {
        return new ManagedHandsCatalog(store, mapper, crypto);
    }

    @Bean(destroyMethod = "close")
    ManagedMcpExecutors managedMcpExecutors(
            ManagedHandsStore store,
            ManagedToolResultPublisher results,
            ObjectMapper mapper,
            VaultCrypto crypto,
            @Value("${builder.managed.mcp.cached-clients:1024}") int capacity) {
        return new ManagedMcpExecutors(store, results, mapper, crypto, capacity);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedHandsLoop managedHandsLoop(
            ManagedHandsStore store,
            ManagedToolExecutions executions,
            ManagedToolResultPublisher results,
            ManagedSandboxProviders providers,
            ManagedMcpExecutors mcp,
            @Value("${builder.managed.tool-concurrency:16}") int concurrency) {
        return new ManagedHandsLoop(
                store,
                executions,
                concurrency,
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                (scope, task) -> provider(scope, task, store, results, providers, mcp),
                (task, failure) ->
                        log.warn("Tool execution recovery pending: {}", failure.toString()));
    }

    @Bean
    ManagedHandsCommands managedHandsCommands(
            ManagedHandsStore store,
            ManagedBatchCodec codec,
            ManagedToolResultPublisher results,
            ManagedHandsLoop loop,
            DataSource source,
            ObjectMapper mapper) {
        return new ManagedHandsCommands(
                store,
                codec,
                results,
                loop::wakeup,
                new ManagedToolCommitGate(source, mapper)::check);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedConsumerLoop managedToolCommandsConsumer(
            RocketMqManagedMessageBus bus,
            ManagedHandsCommands commands,
            @Value("${builder.managed.tool-concurrency:16}") int concurrency) {
        return new ManagedConsumerLoop(
                bus,
                Channel.TOOL_EXECUTE,
                concurrency,
                commands::consume,
                (delivery, failure) ->
                        log.warn("Tool execution remains pending: {}", failure.toString()));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedConsumerLoop managedToolControlConsumer(
            RocketMqManagedMessageBus bus,
            ManagedHandsCommands commands,
            @Value("${builder.managed.tool-control-concurrency:4}") int concurrency) {
        return new ManagedConsumerLoop(
                bus,
                Channel.TOOL_CONTROL,
                concurrency,
                commands::consume,
                (delivery, failure) ->
                        log.warn("Tool control remains pending: {}", failure.toString()));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedToolResultPublisher managedToolResultPublisher(
            ManagedHandsStore journal, RocketMqManagedMessageBus bus, ManagedBatchCodec codec) {
        return new ManagedToolResultPublisher(journal, bus, codec);
    }

    @Bean
    ManagedToolExecutions managedToolExecutions(
            RedisManagedInfrastructure redis,
            ManagedHandsStore journal,
            ManagedToolResultPublisher results,
            ScheduledExecutorService managedHandsRenewals) {
        return new ManagedToolExecutions(
                redis,
                redis,
                journal,
                results,
                managedHandsRenewals,
                Duration.ofSeconds(60),
                Duration.ofSeconds(15),
                Duration.ofSeconds(1));
    }

    private ManagedToolExecutor provider(
            ManagedScope scope,
            String id,
            ManagedHandsStore store,
            ManagedToolResultPublisher results,
            ManagedSandboxProviders providers,
            ManagedMcpExecutors mcp) {
        var task = store.tool(scope, id).orElseThrow();
        if (task.name().startsWith("mcp.")) return mcp.executor(scope, id);
        if (!task.name().equals("sandbox.cleanup")) return providers.executor(scope);
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
