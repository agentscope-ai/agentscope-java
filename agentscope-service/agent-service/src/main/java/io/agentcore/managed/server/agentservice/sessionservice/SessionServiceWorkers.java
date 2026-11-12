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
package io.agentcore.managed.server.agentservice.sessionservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedAdministrativeTools;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedBatchCodec;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointLoop;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointOperations;
import io.agentcore.managed.server.runtime.managed.ManagedConsumerLoop;
import io.agentcore.managed.server.runtime.managed.ManagedDeliverySuspended;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessRuntimeToolEvents;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedLifecycleLoop;
import io.agentcore.managed.server.runtime.managed.ManagedLifecycles;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedResumePublisher;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSubagentExecutor;
import io.agentcore.managed.server.runtime.managed.ManagedSubagents;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor;
import io.agentcore.managed.server.runtime.managed.ManagedToolLoop;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultPublisher;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultRelay;
import io.agentcore.managed.server.runtime.managed.ManagedToolTransport;
import io.agentcore.managed.server.runtime.managed.ManagedTransportException;
import io.agentcore.managed.server.runtime.managed.RedisManagedInfrastructure;
import io.agentcore.managed.server.runtime.managed.RocketMqManagedMessageBus;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;

/** Owns durable tool inbox, result repair and wakeups independently of any Brain process. */
@Configuration(proxyBeanMethods = false)
public class SessionServiceWorkers {
    private static final Logger log = LoggerFactory.getLogger(SessionServiceWorkers.class);

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService sessionServiceControls() {
        return Executors.newScheduledThreadPool(4);
    }

    @Bean
    ManagedAdministrativeTools managedAdministrativeTools(
            DataSource source,
            ObjectMapper mapper,
            ManagedToolTransport commands,
            ManagedMcpCatalog targets,
            ManagedCatalog catalog) {
        return new ManagedAdministrativeTools(source, mapper, commands, targets, catalog);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedCheckpointLoop managedCheckpointLoop(
            ManagedCheckpointOperations operations,
            RedisManagedInfrastructure redis,
            ManagedAdministrativeTools tools,
            ManagedCatalog catalog) {
        return new ManagedCheckpointLoop(
                operations,
                redis,
                tools::executor,
                scope -> catalog.resolve(scope).session().ownerId(),
                2,
                Duration.ofSeconds(60),
                Duration.ofSeconds(15),
                (command, failure) ->
                        log.warn("Checkpoint administration pending: {}", failure.toString()));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedLifecycleLoop managedLifecycleLoop(
            ManagedLifecycles lifecycles,
            RedisManagedInfrastructure redis,
            ManagedAdministrativeTools tools,
            ObjectMapper mapper,
            ManagedJournalStore journal) {
        return new ManagedLifecycleLoop(
                lifecycles,
                redis,
                (command, check) -> {
                    String id =
                            "cleanup_"
                                    + ManagedScope.fingerprint(
                                            command.scope().messageGroup(), command.id());
                    try {
                        var task =
                                new ToolTask(
                                        id,
                                        "administrative",
                                        id,
                                        id,
                                        "sandbox.cleanup",
                                        mapper.writeValueAsString(command),
                                        0,
                                        "ready",
                                        null,
                                        null,
                                        null);
                        var executor = tools.executor(command.scope());
                        executor.start(command.scope(), task);
                        check.run();
                        var result = executor.query(command.scope(), task);
                        if (result.state() != ManagedToolExecutor.State.SUCCEEDED)
                            throw new ManagedTransportException(
                                    "Environment cleanup is not confirmed");
                        if ("deleted".equals(command.desired()))
                            journal.files().discardDeletedContent(command.scope());
                    } catch (RuntimeException failure) {
                        throw failure;
                    } catch (Exception invalid) {
                        throw new IllegalArgumentException("Invalid lifecycle command", invalid);
                    }
                },
                2,
                Duration.ofSeconds(60),
                Duration.ofSeconds(15),
                (command, failure) ->
                        log.debug("Lifecycle administration pending: {}", failure.toString()));
    }

    @Bean(destroyMethod = "close")
    ManagedToolTransport managedToolTransport(
            ManagedJournalStore journal, RocketMqManagedMessageBus bus, ManagedBatchCodec codec) {
        var transport = journal.transport();
        transport.start(bus, codec);
        return transport;
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedToolResultRelay sessionServiceToolResultRelay(
            ManagedJournalStore journal,
            ManagedBatchCodec codec,
            ManagedToolResultDelivery delivery) {
        return new ManagedToolResultRelay(journal, codec, delivery);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedConsumerLoop sessionServiceToolEvents(
            RocketMqManagedMessageBus bus,
            ManagedBatchCodec codec,
            ManagedJournalStore journal,
            ManagedToolResultRelay relay,
            ManagedMcpCatalog catalogs,
            ObjectProvider<ManagedResumePublisher> resumes,
            ManagedAdministrativeTools administrative,
            ObjectMapper mapper,
            @Value("${managed-agents.runtime.tool-event-concurrency:16}") int concurrency) {
        var events =
                new ManagedHarnessRuntimeToolEvents(
                        codec,
                        journal,
                        result -> {
                            relay.delivered(result);
                            resumes.ifAvailable(
                                    publisher -> publisher.wakeup(result.scope().messageGroup()));
                        });
        return new ManagedConsumerLoop(
                bus,
                Channel.TOOL_EVENTS,
                concurrency,
                ManagedConsumerLoop.bounded(
                        concurrency,
                        delivery -> {
                            try {
                                var event = codec.decodeToolEvent(delivery.message());
                                if (!catalogs.receive(event) && !administrative.receive(event))
                                    events.consume(delivery);
                            } catch (DataAccessException | ManagedTransportException unavailable) {
                                throw new ManagedDeliverySuspended(Duration.ofSeconds(1));
                            }
                        }),
                (delivery, failure) -> log.warn("Tool inbox pending: {}", failure.toString()));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(
            name = "managed-agents.brain.http-enabled",
            havingValue = "false",
            matchIfMissing = true)
    ManagedResumePublisher managedResumePublisher(
            ManagedJournalStore journal,
            RocketMqManagedMessageBus bus,
            RedisManagedInfrastructure redis,
            ObjectMapper mapper,
            @Value("${managed-agents.runtime.resume-repair-interval:30s}")
                    Duration repairInterval) {
        return new ManagedResumePublisher(
                journal.suspensions(), bus, redis, mapper, repairInterval);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedToolResultPublisher managedChildResults(
            ManagedJournalStore journal, RocketMqManagedMessageBus bus, ManagedBatchCodec codec) {
        return new ManagedToolResultPublisher(journal, bus, codec);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedToolLoop managedChildControl(
            ManagedJournalStore journal,
            RedisManagedInfrastructure redis,
            ManagedToolResultPublisher managedChildResults,
            ManagedSubagents children,
            ManagedAdmissionStore admission,
            ObjectMapper mapper,
            @Qualifier("sessionServiceControls") ScheduledExecutorService controls,
            @Value("${managed-agents.runtime.child-control-concurrency:4}") int concurrency) {
        var executor = new ManagedSubagentExecutor(children, admission, journal, mapper);
        var tools =
                new ManagedToolExecutions(
                        redis,
                        redis,
                        journal,
                        managedChildResults,
                        controls,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        Duration.ofMillis(200));
        return new ManagedToolLoop(
                journal,
                tools,
                concurrency,
                true,
                (scope, task) -> executor,
                (task, failure) -> log.warn("Child control pending: {}", failure.toString()));
    }
}
