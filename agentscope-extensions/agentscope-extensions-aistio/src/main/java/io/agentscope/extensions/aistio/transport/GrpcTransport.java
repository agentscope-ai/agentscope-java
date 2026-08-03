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
import io.agentscope.aistio.proto.Downstream;
import io.agentscope.aistio.proto.EventReport;
import io.agentscope.aistio.proto.Heartbeat;
import io.agentscope.aistio.proto.InventoryReport;
import io.agentscope.aistio.proto.SessionEventMsg;
import io.agentscope.aistio.proto.SessionReport;
import io.agentscope.aistio.proto.SessionSnapshot;
import io.agentscope.aistio.proto.Upstream;
import io.agentscope.aistio.proto.UpstreamMeta;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
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
 * <p>Reconnects with capped exponential backoff. While disconnected, sends are dropped rather than
 * buffered here — the bridge owns buffering, because only it knows which levels are safe to drop.
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

    /** Receives downstream {@code TeamEvent} notifications from the control plane. */
    @FunctionalInterface
    public interface TeamEventHandler {
        void onTeamEvent(
                String teamId, String eventType, String memberName, String taskId, byte[] payload);
    }

    private final String target;
    private final String agentName;
    private final String namespace;
    private final String instanceId;
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
    private volatile TeamEventHandler teamEventHandler;

    public GrpcTransport(
            String target,
            String agentName,
            String namespace,
            String instanceId,
            String runtime,
            String sdkVersion,
            Collection<String> capabilities,
            String sessionAffinity) {
        this.target = target;
        this.agentName = agentName;
        this.namespace = namespace;
        this.instanceId = instanceId;
        this.runtime = runtime;
        this.sdkVersion = sdkVersion;
        this.capabilities = List.copyOf(capabilities);
        this.sessionAffinity = sessionAffinity == null ? "" : sessionAffinity;
    }

    public void setSessionCommandHandler(SessionCommandHandler handler) {
        this.commandHandler = handler;
    }

    public void setTeamEventHandler(TeamEventHandler handler) {
        this.teamEventHandler = handler;
    }

    public boolean isConnected() {
        return connected.get();
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
                .setAgentName(agentName)
                .setInstanceId(instanceId)
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

    public void reportEvents(List<SessionEventMsg> events) {
        if (events.isEmpty()) {
            return;
        }
        send(
                Upstream.newBuilder()
                        .setMeta(meta())
                        .setEventReport(EventReport.newBuilder().addAllEvents(events).build())
                        .build());
    }

    public void reportContext(ContextReport report) {
        send(Upstream.newBuilder().setMeta(meta()).setContextReport(report).build());
    }

    public void reportInventory(InventoryReport report) {
        send(Upstream.newBuilder().setMeta(meta()).setInventory(report).build());
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

    private void send(Upstream message) {
        StreamObserver<Upstream> observer = stream.get();
        if (observer == null || !connected.get()) {
            return;
        }
        try {
            synchronized (this) {
                observer.onNext(message);
            }
        } catch (RuntimeException e) {
            // The stream broke mid-send; drop this report and let the reconnect path recover.
            LOG.log(Level.FINE, "aistio: ASDP send failed", e);
            scheduleReconnect();
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
            } else if (message.hasTeamEvent()) {
                TeamEventHandler handler = teamEventHandler;
                if (handler == null) {
                    return;
                }
                try {
                    var ev = message.getTeamEvent();
                    handler.onTeamEvent(
                            ev.getTeamId(),
                            ev.getEventType(),
                            ev.getMemberName(),
                            ev.getTaskId(),
                            ev.getPayload().toByteArray());
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "aistio: team event handler failed", e);
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
