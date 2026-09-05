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
package io.agentscope.extensions.aistio.transport;

import io.agentscope.aistio.proto.AgentDataPlaneServiceGrpc;
import io.agentscope.aistio.proto.ConnectRequest;
import io.agentscope.aistio.proto.ContextReport;
import io.agentscope.aistio.proto.ConversationTurnCommand;
import io.agentscope.aistio.proto.ConversationTurnReport;
import io.agentscope.aistio.proto.Downstream;
import io.agentscope.aistio.proto.EventReport;
import io.agentscope.aistio.proto.EventReportAck;
import io.agentscope.aistio.proto.ExecutionAttemptCommand;
import io.agentscope.aistio.proto.ExecutionAttemptReport;
import io.agentscope.aistio.proto.Heartbeat;
import io.agentscope.aistio.proto.InventoryReport;
import io.agentscope.aistio.proto.SessionEventMsg;
import io.agentscope.aistio.proto.SessionReport;
import io.agentscope.aistio.proto.SessionSnapshot;
import io.agentscope.aistio.proto.Upstream;
import io.agentscope.aistio.proto.UpstreamMeta;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ASDP upstream channel: a single bidirectional gRPC stream multiplexing every report type, the
 * same shape the Go control plane and the Python SDK use.
 *
 * <p>Reconnects with capped exponential backoff. The bridge owns the durable event outbox and only
 * advances it after receiving {@link EventReportAck}; non-event telemetry remains best effort.
 */
public final class GrpcTransport implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(GrpcTransport.class.getName());

    private static final long RECONNECT_BASE_MS = 1_000L;
    private static final long RECONNECT_MAX_MS = 30_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    /** Receives {@code SessionCommand} pushed down by the control plane. */
    @FunctionalInterface
    public interface SessionCommandHandler {
        void onCommand(String sessionId, String command, byte[] params);
    }

    /** Receives a fenced execution-attempt command from the control plane. */
    @FunctionalInterface
    public interface ExecutionAttemptCommandHandler {
        void onExecutionAttempt(ExecutionAttemptCommand command);
    }

    /** Receives a fenced online conversation turn from the control plane. */
    @FunctionalInterface
    public interface ConversationTurnCommandHandler {
        void onConversationTurn(ConversationTurnCommand command);
    }

    /** Receives a durable commit acknowledgement for a Level-2 event report. */
    @FunctionalInterface
    public interface EventAckHandler {
        void onAck(EventReportAck ack);
    }

    private final String target;
    private volatile String credential;
    private volatile String agentId;
    private final String agentKey;
    private volatile String bindingId;
    private final String tenant;
    private final String namespace;
    private volatile String instanceKey;
    private volatile long generation;
    private final String runtime;
    private final String sdkVersion;
    private final List<String> capabilities;
    private final String sessionAffinity;

    private final AtomicReference<StreamObserver<Upstream>> stream = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "aistio-asdp");
                        t.setDaemon(true);
                        return t;
                    });

    private volatile ManagedChannel channel;
    private volatile SessionCommandHandler commandHandler;
    private volatile ExecutionAttemptCommandHandler executionAttemptHandler;
    private volatile ConversationTurnCommandHandler conversationTurnHandler;
    private volatile EventAckHandler eventAckHandler;

    public GrpcTransport(
            String target,
            String credential,
            String agentId,
            String agentKey,
            String bindingId,
            String tenant,
            String namespace,
            String instanceKey,
            long generation,
            String runtime,
            String sdkVersion,
            Collection<String> capabilities,
            String sessionAffinity) {
        this.target = target;
        this.credential = credential == null ? "" : credential;
        this.agentId = agentId;
        this.agentKey = agentKey;
        this.bindingId = bindingId;
        this.tenant = tenant;
        this.namespace = namespace;
        this.instanceKey = instanceKey;
        this.generation = generation;
        this.runtime = runtime;
        this.sdkVersion = sdkVersion;
        this.capabilities = List.copyOf(capabilities);
        this.sessionAffinity = sessionAffinity == null ? "" : sessionAffinity;
    }

    public void setSessionCommandHandler(SessionCommandHandler handler) {
        this.commandHandler = handler;
    }

    public void setExecutionAttemptHandler(ExecutionAttemptCommandHandler handler) {
        this.executionAttemptHandler = handler;
    }

    public void setConversationTurnHandler(ConversationTurnCommandHandler handler) {
        this.conversationTurnHandler = handler;
    }

    public void setEventAckHandler(EventAckHandler handler) {
        this.eventAckHandler = handler;
    }

    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Refreshes the fenced Catalog identity used by the next connection attempt. HTTP
     * self-registration can advance an instance generation while this transport is reconnecting
     * after a control-plane outage; keeping these values immutable would make every later
     * handshake stale until the whole Agent process restarted.
     */
    public void updateIdentity(
            String credential,
            String agentId,
            String bindingId,
            String instanceKey,
            long generation) {
        if (agentId == null
                || agentId.isBlank()
                || bindingId == null
                || bindingId.isBlank()
                || instanceKey == null
                || instanceKey.isBlank()
                || generation <= 0) {
            return;
        }
        this.credential = credential == null ? "" : credential;
        this.agentId = agentId;
        this.bindingId = bindingId;
        this.instanceKey = instanceKey;
        this.generation = generation;
    }

    public void start() {
        if (stopped.get()) {
            throw new IllegalStateException("transport already stopped");
        }
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        connect();
        scheduler.scheduleWithFixedDelay(
                this::sendHeartbeat,
                HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private void connect() {
        if (stopped.get()) {
            return;
        }
        try {
            AgentDataPlaneServiceGrpc.AgentDataPlaneServiceStub stub =
                    AgentDataPlaneServiceGrpc.newStub(channel);
            if (!credential.isBlank()) {
                Metadata metadata = new Metadata();
                metadata.put(
                        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                        "Bearer " + credential);
                stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
            }
            StreamObserver<Upstream> requests = stub.connect(new DownstreamObserver());
            stream.set(requests);
            requests.onNext(
                    Upstream.newBuilder()
                            .setMeta(meta())
                            .setConnect(
                                    ConnectRequest.newBuilder()
                                            .setRuntime(runtime)
                                            .setSdkVersion(sdkVersion)
                                            .addAllCapabilities(capabilities)
                                            .setSessionAffinity(sessionAffinity)
                                            .build())
                            .build());
            connected.set(true);
            reconnectAttempts.set(0);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "aistio: ASDP connect failed", e);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        connected.set(false);
        stream.set(null);
        if (stopped.get()) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        long delay = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * (1L << Math.min(attempt, 5)));
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private UpstreamMeta meta() {
        return UpstreamMeta.newBuilder()
                .setAgentId(agentId)
                .setAgentKey(agentKey)
                .setBindingId(bindingId)
                .setTenant(tenant)
                .setInstanceKey(instanceKey)
                .setGeneration(generation)
                .setNamespace(namespace)
                .setTimestamp(System.currentTimeMillis())
                .build();
    }

    // ─── reports ───

    public void reportSessions(List<SessionSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        send(
                Upstream.newBuilder()
                        .setMeta(meta())
                        .setSessionReport(
                                SessionReport.newBuilder().addAllSessions(snapshots).build())
                        .build());
    }

    public boolean reportEvents(String reportId, List<SessionEventMsg> events) {
        if (events.isEmpty()) {
            return true;
        }
        return send(
                Upstream.newBuilder()
                        .setMeta(meta())
                        .setEventReport(
                                EventReport.newBuilder()
                                        .setReportId(reportId)
                                        .addAllEvents(events)
                                        .build())
                        .build());
    }

    public void reportContext(ContextReport report) {
        send(Upstream.newBuilder().setMeta(meta()).setContextReport(report).build());
    }

    public void reportInventory(InventoryReport report) {
        send(Upstream.newBuilder().setMeta(meta()).setInventory(report).build());
    }

    public void reportExecutionAttempt(ExecutionAttemptReport report) {
        send(Upstream.newBuilder().setMeta(meta()).setExecutionAttempt(report).build());
    }

    public void reportConversationTurn(ConversationTurnReport report) {
        send(Upstream.newBuilder().setMeta(meta()).setConversationTurn(report).build());
    }

    private void sendHeartbeat() {
        if (!connected.get()) {
            return;
        }
        send(
                Upstream.newBuilder()
                        .setMeta(meta())
                        .setHeartbeat(
                                Heartbeat.newBuilder()
                                        .setTimestamp(System.currentTimeMillis())
                                        .build())
                        .build());
    }

    private boolean send(Upstream message) {
        StreamObserver<Upstream> observer = stream.get();
        if (observer == null || !connected.get()) {
            return false;
        }
        try {
            synchronized (this) {
                observer.onNext(message);
            }
            return true;
        } catch (RuntimeException e) {
            // The bridge retains acknowledged event reports and retries them after reconnect.
            LOG.log(Level.FINE, "aistio: ASDP send failed", e);
            scheduleReconnect();
            return false;
        }
    }

    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        connected.set(false);
        StreamObserver<Upstream> observer = stream.getAndSet(null);
        if (observer != null) {
            try {
                observer.onCompleted();
            } catch (RuntimeException ignored) {
                // Already broken; nothing useful to do while shutting down.
            }
        }
        scheduler.shutdownNow();
        ManagedChannel ch = channel;
        if (ch != null) {
            ch.shutdown();
            try {
                ch.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final class DownstreamObserver implements StreamObserver<Downstream> {

        @Override
        public void onNext(Downstream message) {
            if (message.hasSessionCmd()) {
                SessionCommandHandler handler = commandHandler;
                if (handler == null) {
                    return;
                }
                try {
                    handler.onCommand(
                            message.getSessionCmd().getSessionId(),
                            message.getSessionCmd().getCommand(),
                            message.getSessionCmd().getParams().toByteArray());
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "aistio: session command handler failed", e);
                }
            } else if (message.hasExecutionAttempt()) {
                ExecutionAttemptCommandHandler handler = executionAttemptHandler;
                if (handler == null) {
                    return;
                }
                try {
                    handler.onExecutionAttempt(message.getExecutionAttempt());
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "aistio: ExecutionAttempt command handler failed", e);
                }
            } else if (message.hasConversationTurn()) {
                ConversationTurnCommandHandler handler = conversationTurnHandler;
                if (handler == null) {
                    return;
                }
                try {
                    handler.onConversationTurn(message.getConversationTurn());
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "aistio: conversation turn handler failed", e);
                }
            } else if (message.hasEventAck()) {
                EventAckHandler handler = eventAckHandler;
                if (handler == null) {
                    return;
                }
                try {
                    handler.onAck(message.getEventAck());
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "aistio: event acknowledgement handler failed", e);
                }
            } else if (message.hasConnectAck() && !message.getConnectAck().getAccepted()) {
                LOG.log(
                        Level.WARNING,
                        "aistio: control plane rejected connect: {0}",
                        message.getConnectAck().getRejectReason());
            }
        }

        @Override
        public void onError(Throwable t) {
            LOG.log(Level.FINE, "aistio: ASDP stream error", t);
            scheduleReconnect();
        }

        @Override
        public void onCompleted() {
            scheduleReconnect();
        }
    }
}
