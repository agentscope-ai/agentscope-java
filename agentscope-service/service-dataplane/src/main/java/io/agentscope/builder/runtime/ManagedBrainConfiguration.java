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
package io.agentscope.builder.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedAdministrativeTools;
import io.agentscope.builder.runtime.managed.ManagedAdmissionStore;
import io.agentscope.builder.runtime.managed.ManagedBatchCodec;
import io.agentscope.builder.runtime.managed.ManagedBrainToolEvents;
import io.agentscope.builder.runtime.managed.ManagedBrainTurns;
import io.agentscope.builder.runtime.managed.ManagedBudgetStore;
import io.agentscope.builder.runtime.managed.ManagedCheckpointLoop;
import io.agentscope.builder.runtime.managed.ManagedCheckpointOperations;
import io.agentscope.builder.runtime.managed.ManagedConsumerLoop;
import io.agentscope.builder.runtime.managed.ManagedContinuationLoop;
import io.agentscope.builder.runtime.managed.ManagedDeliverySuspended;
import io.agentscope.builder.runtime.managed.ManagedHarnessTurns;
import io.agentscope.builder.runtime.managed.ManagedInputDelivery;
import io.agentscope.builder.runtime.managed.ManagedJournalStore;
import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentscope.builder.runtime.managed.ManagedLifecycleLoop;
import io.agentscope.builder.runtime.managed.ManagedLifecycles;
import io.agentscope.builder.runtime.managed.ManagedMcpCatalog;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedModelPolicy;
import io.agentscope.builder.runtime.managed.ManagedPushDelivery;
import io.agentscope.builder.runtime.managed.ManagedResumePublisher;
import io.agentscope.builder.runtime.managed.ManagedRuntimeWriter;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedSubagentExecution;
import io.agentscope.builder.runtime.managed.ManagedSubagentExecutor;
import io.agentscope.builder.runtime.managed.ManagedSubagents;
import io.agentscope.builder.runtime.managed.ManagedSuspensions;
import io.agentscope.builder.runtime.managed.ManagedToolExecutions;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor;
import io.agentscope.builder.runtime.managed.ManagedToolLoop;
import io.agentscope.builder.runtime.managed.ManagedToolRequests;
import io.agentscope.builder.runtime.managed.ManagedToolResultDelivery;
import io.agentscope.builder.runtime.managed.ManagedToolResultPublisher;
import io.agentscope.builder.runtime.managed.ManagedToolResultRelay;
import io.agentscope.builder.runtime.managed.ManagedToolTransport;
import io.agentscope.builder.runtime.managed.ManagedTransportException;
import io.agentscope.builder.runtime.managed.ManagedTurnAcknowledgements;
import io.agentscope.builder.runtime.managed.ManagedTurnExecution;
import io.agentscope.builder.runtime.managed.ManagedTurnPublisher.Command;
import io.agentscope.builder.runtime.managed.RedisManagedInfrastructure;
import io.agentscope.builder.runtime.managed.RocketMqManagedMessageBus;
import io.agentscope.builder.runtime.managed.RocketMqTurnReceipt;
import io.agentscope.builder.web.catalog.HarnessAgentBuildService;
import io.agentscope.builder.web.catalog.spec.AgentSpecCodec;
import io.agentscope.builder.web.managed.AgentVersionSnapshot;
import io.agentscope.builder.web.managed.ManagedSessionIdentity;
import io.agentscope.builder.web.managed.SessionAgentBuildSpec;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionModelPolicy;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import io.agentscope.harness.agent.subagent.SubagentExecution;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;

/** The Brain service holds SDK callbacks for Turns; HTTP handlers never select an execution replica. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "builder.managed.consumers.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ManagedBrainConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ManagedBrainConfiguration.class);

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService managedBrainRenewals(
            @Value("${builder.managed.concurrency:16}") int concurrency) {
        return Executors.newScheduledThreadPool(concurrency);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService managedBrainControls(
            @Value("${builder.managed.concurrency:16}") int concurrency) {
        return Executors.newScheduledThreadPool(concurrency);
    }

    @Bean(destroyMethod = "close")
    public ManagedBrainTurns.Handler managedBrainHandler(
            RocketMqManagedMessageBus bus,
            RedisManagedInfrastructure redis,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            ManagedRuntimeWriter writer,
            ManagedInputDelivery inputs,
            ObjectMapper mapper,
            ManagedCatalog catalog,
            ManagedSubagents children,
            ManagedToolRequests toolRequests,
            HarnessAgentBuildService agents,
            ManagedBudgetStore budgets,
            VaultCrypto crypto,
            @Qualifier("managedBrainRenewals") ScheduledExecutorService renewals,
            @Qualifier("managedBrainControls") ScheduledExecutorService controls,
            @Value("${builder.managed.lease-seconds:60}") long ttlSeconds,
            @Value("${builder.managed.renew-seconds:15}") long renewalSeconds,
            @Value("${builder.managed.concurrency:16}") int concurrency,
            @Value("${builder.managed.turn-suspend-seconds:60}") long suspendSeconds) {
        var ttl = Duration.ofSeconds(ttlSeconds);
        var brain =
                new ManagedBrainTurns(
                        bus,
                        redis,
                        admission,
                        journal,
                        writer,
                        mapper,
                        renewals,
                        ttl,
                        Duration.ofSeconds(renewalSeconds),
                        concurrency,
                        Duration.ofSeconds(suspendSeconds));
        var harness = new ManagedHarnessTurns(mapper, inputs, controls);
        ManagedBrainTurns.Runner runner =
                execution -> {
                    var resolved = catalog.resolve(execution.scope());
                    execution.toolConfiguration(agents.managedExecutionConfiguration(resolved));
                    var session = resolved.session();
                    var spec =
                            new SessionAgentBuildSpec(
                                    session.agentVersion(),
                                    session.environmentId(),
                                    resolved.environment(),
                                    session.agentOverridesJson(),
                                    session.memoryStoreIds(),
                                    session.vaultIds(),
                                    session.resources());
                    var agent = agents.getOrBuildAgent(session, spec);
                    var childDefinition = catalog.childDefinition(execution.scope());
                    var snapshot =
                            mapper.convertValue(
                                    resolved.agentSnapshot(), AgentVersionSnapshot.class);
                    var skills =
                            childDefinition == null
                                    ? AgentSpecCodec.workspaceSkillNames(snapshot.skills())
                                    : childDefinition.skills();
                    var context =
                            RuntimeContext.builder()
                                    .userId(session.ownerId())
                                    .sessionId(session.id())
                                    .put(ManagedScope.class, execution.scope())
                                    .put(ManagedTurnExecution.class, execution)
                                    .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                                    .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 600)
                                    .put(
                                            SubagentExecution.class,
                                            new ManagedSubagentExecution(
                                                    agent, skills, children, toolRequests, mapper))
                                    .put(
                                            ManagedSessionIdentity.class,
                                            new ManagedSessionIdentity(session.id()))
                                    .put(
                                            ManagedToolRequests.Operations.class,
                                            new ManagedToolRequests.Operations())
                                    .put(ToolCallParam.CALL_CONTEXT_KEY, true)
                                    .put(MemoryFlushMiddleware.INLINE_CONTEXT_KEY, true)
                                    .put(SessionRecorder.TURN_ID_KEY, execution.turnId())
                                    .put(
                                            SessionModelPolicy.CONTEXT_KEY,
                                            new ManagedModelPolicy(budgets, execution))
                                    .put(
                                            "managed.actor",
                                            mapper.readTree(
                                                    admission
                                                            .turn(
                                                                    execution.scope(),
                                                                    execution.turnId())
                                                            .actor()))
                                    .build();
                    if (childDefinition != null && execution.head().checkpoint() == null)
                        childDefinition.initialize(agent, context);
                    harness.run(execution, agent, context);
                };
        return new ManagedBrainTurns.Handler() {
            @Override
            public void consume(ManagedPushDelivery delivery) throws Exception {
                var identity = mapper.readTree(delivery.message().body());
                var incomingScope = mapper.treeToValue(identity.get("scope"), ManagedScope.class);
                String incomingTurn = identity.path("turnId").asText();
                if (!Set.of("succeeded", "failed", "cancelled")
                        .contains(admission.turn(incomingScope, incomingTurn).status()))
                    agents.prepareManagedTools(catalog.resolve(incomingScope), incomingTurn);
                brain.consume(delivery, runner);
            }

            @Override
            public void resume(ManagedSuspensions.Resume resume) throws Exception {
                agents.prepareManagedTools(catalog.resolve(resume.scope()), resume.turnId());
                brain.resume(resume, runner);
            }

            @Override
            public void close() {
                brain.close();
            }
        };
    }

    @Bean
    ManagedMcpCatalog managedMcpCatalog(
            DataSource source,
            ObjectMapper mapper,
            VaultCrypto crypto,
            ManagedToolTransport transport) {
        return new ManagedMcpCatalog(source, mapper, crypto, transport);
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
    ManagedToolResultRelay managedBrainToolResultRelay(
            ManagedJournalStore journal,
            ManagedBatchCodec codec,
            ManagedToolResultDelivery delivery) {
        return new ManagedToolResultRelay(journal, codec, delivery);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedConsumerLoop managedBrainToolEvents(
            RocketMqManagedMessageBus bus,
            ManagedBatchCodec codec,
            ManagedJournalStore journal,
            ManagedToolResultRelay relay,
            ManagedMcpCatalog catalogs,
            ManagedContinuationLoop continuations,
            ManagedAdministrativeTools administrative,
            @Value("${builder.managed.tool-event-concurrency:16}") int concurrency) {
        var events =
                new ManagedBrainToolEvents(
                        codec,
                        journal,
                        result -> {
                            relay.delivered(result);
                            continuations.wakeup();
                        });
        return new ManagedConsumerLoop(
                bus,
                Channel.TOOL_EVENTS,
                concurrency,
                delivery -> {
                    try {
                        var event = codec.decodeToolEvent(delivery.message());
                        if (!catalogs.receive(event) && !administrative.receive(event))
                            events.consume(delivery);
                    } catch (DataAccessException | ManagedTransportException unavailable) {
                        throw new ManagedDeliverySuspended(Duration.ofSeconds(1));
                    }
                },
                (delivery, failure) -> log.warn("Tool inbox pending: {}", failure.toString()));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedContinuationLoop managedContinuationLoop(
            ManagedJournalStore journal,
            @Qualifier("managedBrainHandler") ManagedBrainTurns.Handler handler,
            @Value("${builder.managed.concurrency:16}") int concurrency) {
        return new ManagedContinuationLoop(journal.suspensions(), handler, concurrency);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedResumePublisher managedResumePublisher(
            ManagedJournalStore journal,
            RocketMqManagedMessageBus bus,
            RedisManagedInfrastructure redis,
            ObjectMapper mapper) {
        return new ManagedResumePublisher(journal.suspensions(), bus, redis, mapper);
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
            @Qualifier("managedBrainControls") ScheduledExecutorService controls,
            @Value("${builder.managed.child-control-concurrency:4}") int concurrency) {
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

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedTurnAcknowledgements managedTurnAcknowledgements(
            DataSource source, ObjectMapper mapper, RocketMqManagedMessageBus bus) {
        var acknowledgements =
                new ManagedTurnAcknowledgements(
                        source,
                        mapper,
                        bus.turnTopic(),
                        bus.turnConsumerGroup(),
                        bus::acknowledgeTurn);
        bus.turnAcknowledgements(
                (view, delay) -> {
                    try {
                        var body = view.getBody().duplicate();
                        byte[] bytes = new byte[body.remaining()];
                        body.get(bytes);
                        var command = mapper.readValue(bytes, Command.class);
                        if (!command.turnId().equals(view.getProperties().get("ma_logical_id")))
                            throw new IllegalArgumentException("Turn receipt identity differs");
                        acknowledgements.suspended(
                                command,
                                RocketMqTurnReceipt.capture((MessageViewImpl) view),
                                delay);
                    } catch (IOException invalid) {
                        throw new IllegalArgumentException("Invalid Turn receipt envelope");
                    }
                });
        return acknowledgements;
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public ManagedConsumerLoop managedBrainConsumer(
            RocketMqManagedMessageBus bus,
            @Qualifier("managedBrainHandler") ManagedConsumerLoop.Handler handler,
            ManagedTurnAcknowledgements acknowledgements,
            @Value("${builder.managed.turn-callbacks:64}") int callbacks) {
        return consumer(bus, Channel.TURNS, handler, callbacks);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public ManagedConsumerLoop managedResumeConsumer(
            RocketMqManagedMessageBus bus,
            @Qualifier("managedBrainHandler") ManagedConsumerLoop.Handler handler,
            @Value("${builder.managed.resume-callbacks:64}") int callbacks) {
        return consumer(bus, Channel.TURN_RESUMES, handler, callbacks);
    }

    private ManagedConsumerLoop consumer(
            RocketMqManagedMessageBus bus,
            Channel channel,
            ManagedConsumerLoop.Handler handler,
            int concurrency) {
        return new ManagedConsumerLoop(
                bus,
                channel,
                concurrency,
                handler,
                (delivery, failure) ->
                        log.warn(
                                "Managed execution remains unacknowledged: channel={}, id={},"
                                        + " cause={}",
                                channel,
                                delivery == null ? "receive" : delivery.message().id(),
                                failure.toString()));
    }
}
