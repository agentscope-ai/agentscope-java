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
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedBudgetStore;
import io.agentcore.managed.server.runtime.managed.ManagedConsumerLoop;
import io.agentcore.managed.server.runtime.managed.ManagedDeliverySuspended;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessRuntimeTurns;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessTurns;
import io.agentcore.managed.server.runtime.managed.ManagedInputDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedMcpDiscovery;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedModelPolicy;
import io.agentcore.managed.server.runtime.managed.ManagedPushDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedReceiveCapacity;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeLog;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeWriter;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSubagentExecution;
import io.agentcore.managed.server.runtime.managed.ManagedSubagents;
import io.agentcore.managed.server.runtime.managed.ManagedToolRequests;
import io.agentcore.managed.server.runtime.managed.ManagedToolTransport;
import io.agentcore.managed.server.runtime.managed.ManagedTurnAcknowledgements;
import io.agentcore.managed.server.runtime.managed.ManagedTurnExecution;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPreparation;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher.Command;
import io.agentcore.managed.server.runtime.managed.RedisManagedInfrastructure;
import io.agentcore.managed.server.runtime.managed.RocketMqManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.RocketMqTurnReceipt;
import io.agentcore.managed.server.web.catalog.HarnessAgentBuildService;
import io.agentcore.managed.server.web.catalog.spec.AgentSpecCodec;
import io.agentcore.managed.server.web.managed.AgentVersionSnapshot;
import io.agentcore.managed.server.web.managed.ManagedSessionIdentity;
import io.agentcore.managed.server.web.managed.SessionAgentBuildSpec;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionModelPolicy;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentExecution;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** The HarnessRuntime service holds SDK callbacks for Turns; HTTP handlers never select an execution replica. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "managed-agents.runtime.consumers.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ManagedHarnessRuntimeConfiguration {
    private static final Logger log =
            LoggerFactory.getLogger(ManagedHarnessRuntimeConfiguration.class);

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService managedHarnessRuntimeRenewals(
            @Value("${managed-agents.runtime.renewal-threads:32}") int concurrency) {
        return Executors.newScheduledThreadPool(concurrency);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService managedHarnessRuntimeControls(
            @Value("${managed-agents.runtime.control-threads:32}") int concurrency) {
        return Executors.newScheduledThreadPool(concurrency);
    }

    @Bean(destroyMethod = "close")
    public ManagedHarnessRuntimeTurns.Handler managedHarnessRuntimeHandler(
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
            @Qualifier("managedHarnessRuntimeRenewals") ScheduledExecutorService renewals,
            @Qualifier("managedHarnessRuntimeControls") ScheduledExecutorService controls,
            @Value("${managed-agents.runtime.lease-seconds:60}") long ttlSeconds,
            @Value("${managed-agents.runtime.renew-seconds:15}") long renewalSeconds,
            @Value("${managed-agents.runtime.concurrency:16}") int concurrency,
            @Value("${managed-agents.runtime.turn-suspend-seconds:60}") long suspendSeconds,
            @Value("${managed-agents.runtime.resume-handoff-capacity:64}") int handoffCapacity,
            @Value("${managed-agents.runtime.resume-prepare-timeout:60s}")
                    Duration prepareTimeout) {
        ManagedMessageBus.positiveMillis(prepareTimeout);
        var ttl = Duration.ofSeconds(ttlSeconds);
        var harnessRuntime =
                new ManagedHarnessRuntimeTurns(
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
                        Duration.ofSeconds(suspendSeconds),
                        handoffCapacity);
        var harness = new ManagedHarnessTurns(mapper, inputs, controls);
        ManagedHarnessRuntimeTurns.AsyncRunner runner =
                (execution, scheduler, interrupted) -> {
                    Mono<Void> preparedRun =
                            Mono.usingWhen(
                                            Mono.fromCallable(
                                                            () ->
                                                                    buildExecutionAgent(
                                                                            execution,
                                                                            catalog,
                                                                            agents,
                                                                            children,
                                                                            toolRequests,
                                                                            budgets,
                                                                            admission,
                                                                            mapper))
                                                    .subscribeOn(scheduler),
                                            prepared ->
                                                    harness.runAsync(
                                                            execution,
                                                            prepared.agent(),
                                                            prepared.context(),
                                                            scheduler,
                                                            interrupted),
                                            prepared ->
                                                    Mono.<Void>fromRunnable(prepared.agent()::close)
                                                            .subscribeOn(scheduler),
                                            (prepared, error) ->
                                                    Mono.<Void>fromRunnable(prepared.agent()::close)
                                                            .subscribeOn(scheduler),
                                            // Shutdown has fenced execution; cleanup needs an
                                            // independent worker.
                                            prepared ->
                                                    Mono.<Void>fromRunnable(prepared.agent()::close)
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic()))
                                    .doOnDiscard(
                                            PreparedAgent.class,
                                            prepared ->
                                                    Schedulers.boundedElastic()
                                                            .schedule(prepared.agent()::close));
                    return Mono.fromCallable(
                                    () ->
                                            harness.cancelBeforePreparation(
                                                    execution,
                                                    () ->
                                                            agents.managedPinnedMcpTools(
                                                                    execution.scope(),
                                                                    catalog.resolve(
                                                                            execution.scope()),
                                                                    execution.turnId())))
                            .subscribeOn(scheduler)
                            .flatMap(cancelled -> cancelled ? Mono.empty() : preparedRun);
                };
        return new ManagedHarnessRuntimeTurns.Handler() {
            private final ManagedTurnPreparation preparation = new ManagedTurnPreparation();

            @Override
            public CompletionStage<Void> prepare(ManagedScope scope, String turnId) {
                return Mono.fromCallable(
                                () ->
                                        journal.cancelRequested(scope, turnId)
                                                && !journal.toolsUnsettled(scope, turnId))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(
                                cancelled ->
                                        cancelled
                                                ? Mono.<Void>empty()
                                                : Mono.fromCallable(() -> catalog.resolve(scope))
                                                        .subscribeOn(Schedulers.boundedElastic())
                                                        .flatMap(
                                                                resolved ->
                                                                        agents
                                                                                .prepareManagedToolsAsync(
                                                                                        scope,
                                                                                        resolved,
                                                                                        turnId)))
                        .toFuture();
            }

            @Override
            public ManagedReceiveCapacity receiveCapacity(Channel channel) {
                return (channel == Channel.TURN_RESUMES || channel == Channel.SESSION_READY)
                        ? harnessRuntime.receiveCapacity(channel)
                        : null;
            }

            @Override
            public void consume(ManagedPushDelivery delivery) throws Exception {
                if (delivery.channel() == Channel.TURN_RESUMES
                        || delivery.channel() == Channel.SESSION_READY) {
                    harnessRuntime.consume(
                            delivery,
                            (ManagedHarnessRuntimeTurns.AsyncRunner)
                                    (execution, scheduler, interrupted) ->
                                            Mono.defer(
                                                    () -> {
                                                        long started = System.nanoTime();
                                                        var prepared = new AtomicBoolean();
                                                        ManagedRuntimeLog.info(
                                                                log,
                                                                "harness_runtime.prepare.started",
                                                                execution.scope(),
                                                                execution.turnId());
                                                        return Mono.fromCompletionStage(
                                                                        () ->
                                                                                prepare(
                                                                                        execution
                                                                                                .scope(),
                                                                                        execution
                                                                                                .turnId()))
                                                                .timeout(prepareTimeout)
                                                                .takeUntilOther(
                                                                        interrupted.flatMap(
                                                                                Mono::error))
                                                                .doOnSuccess(
                                                                        ignored ->
                                                                                prepared.set(true))
                                                                .doFinally(
                                                                        signal ->
                                                                                ManagedRuntimeLog
                                                                                        .info(
                                                                                                log,
                                                                                                "harness_runtime.prepare.finished",
                                                                                                execution
                                                                                                        .scope(),
                                                                                                execution
                                                                                                        .turnId(),
                                                                                                "success",
                                                                                                prepared
                                                                                                        .get(),
                                                                                                "duration_ms",
                                                                                                TimeUnit
                                                                                                        .NANOSECONDS
                                                                                                        .toMillis(
                                                                                                                System
                                                                                                                                .nanoTime()
                                                                                                                        - started)))
                                                                .publishOn(scheduler)
                                                                .then(
                                                                        Mono.defer(
                                                                                () -> {
                                                                                    execution
                                                                                            .guard()
                                                                                            .check();
                                                                                    return runner
                                                                                            .runAsync(
                                                                                                    execution,
                                                                                                    scheduler,
                                                                                                    interrupted);
                                                                                }));
                                                    }));
                    return;
                }
                var identity = mapper.readTree(delivery.message().body());
                var incomingScope = mapper.treeToValue(identity.get("scope"), ManagedScope.class);
                String incomingTurn = identity.path("turnId").asText();
                String key = delivery.message().messageGroup() + ":" + delivery.message().id();
                try {
                    preparation.require(
                            key,
                            () -> {
                                if (Set.of("succeeded", "failed", "cancelled")
                                        .contains(
                                                admission
                                                        .turn(incomingScope, incomingTurn)
                                                        .status()))
                                    return CompletableFuture.completedFuture(null);
                                return prepare(incomingScope, incomingTurn);
                            });
                    harnessRuntime.consume(delivery, runner);
                    preparation.forget(key);
                } catch (ManagedDeliverySuspended waiting) {
                    throw waiting;
                } catch (Exception failure) {
                    preparation.forget(key);
                    throw failure;
                }
            }

            @Override
            public void advance(ManagedScope scope, String turnId) throws Exception {
                harnessRuntime.advance(scope, turnId, runner);
            }

            @Override
            public void close() {
                preparation.close();
                harnessRuntime.close();
            }
        };
    }

    record PreparedAgent(HarnessAgent agent, RuntimeContext context) {}

    static PreparedAgent buildExecutionAgent(
            ManagedTurnExecution execution,
            ManagedCatalog catalog,
            HarnessAgentBuildService agents,
            ManagedSubagents children,
            ManagedToolRequests toolRequests,
            ManagedBudgetStore budgets,
            ManagedAdmissionStore admission,
            ObjectMapper mapper)
            throws Exception {
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
        var agent = agents.buildExecutionAgent(session, spec, execution);
        try {
            var childDefinition = catalog.childDefinition(execution.scope());
            var snapshot =
                    mapper.convertValue(resolved.agentSnapshot(), AgentVersionSnapshot.class);
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
                            .put(SessionRecorder.TURN_ID_KEY, execution.turnId())
                            .put(
                                    SessionModelPolicy.CONTEXT_KEY,
                                    new ManagedModelPolicy(budgets, execution))
                            .put(
                                    "managed.actor",
                                    mapper.readTree(
                                            admission
                                                    .turn(execution.scope(), execution.turnId())
                                                    .actor()))
                            .build();
            if (childDefinition != null && execution.head().checkpoint() == null)
                childDefinition.initialize(agent, context);
            return new PreparedAgent(agent, context);
        } catch (Throwable failure) {
            try {
                agent.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Bean
    ManagedMcpCatalog managedMcpCatalog(
            SessionServiceClient client,
            ObjectMapper mapper,
            VaultCrypto crypto,
            @Value("${managed-agents.runtime.mcp.discovery-cache-ttl:60s}") Duration cacheTtl,
            @Value("${managed-agents.runtime.mcp.discovery-cache-entries:4096}") int cacheEntries) {
        return new ManagedMcpCatalog(
                client, mapper, crypto, new ManagedMcpDiscovery(cacheTtl, cacheEntries));
    }

    @Bean(destroyMethod = "close")
    ManagedToolTransport managedToolTransport(ManagedJournalStore journal) {
        return journal.transport();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedTurnAcknowledgements managedTurnAcknowledgements(
            SessionServiceClient client, ObjectMapper mapper, RocketMqManagedMessageBus bus) {
        var acknowledgements =
                new ManagedTurnAcknowledgements(
                        client,
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
    @ConditionalOnProperty(
            name = "managed-agents.runtime.session-queue.enabled",
            havingValue = "false",
            matchIfMissing = true)
    public ManagedConsumerLoop managedHarnessRuntimeConsumer(
            RocketMqManagedMessageBus bus,
            @Qualifier("managedHarnessRuntimeHandler") ManagedConsumerLoop.Handler handler,
            ManagedTurnAcknowledgements acknowledgements,
            @Value("${managed-agents.runtime.turn-callbacks:64}") int callbacks) {
        return consumer(bus, Channel.TURNS, handler, callbacks);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public ManagedConsumerLoop managedResumeConsumer(
            RocketMqManagedMessageBus bus,
            @Qualifier("managedHarnessRuntimeHandler") ManagedConsumerLoop.Handler handler,
            @Value("${managed-agents.runtime.resume-callbacks:64}") int callbacks) {
        return consumer(bus, Channel.TURN_RESUMES, handler, callbacks);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(
            name = "managed-agents.runtime.session-queue.enabled",
            havingValue = "true")
    ManagedConsumerLoop managedSessionReadyConsumer(
            RocketMqManagedMessageBus bus,
            @Qualifier("managedHarnessRuntimeHandler") ManagedConsumerLoop.Handler handler,
            @Value("${managed-agents.runtime.ready-callbacks:64}") int callbacks) {
        return consumer(bus, Channel.SESSION_READY, handler, callbacks);
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
