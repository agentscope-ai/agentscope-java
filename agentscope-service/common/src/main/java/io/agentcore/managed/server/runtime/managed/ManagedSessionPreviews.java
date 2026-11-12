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
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ScriptOutputType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Ephemeral, authorized online text previews. No SQL, history, durable MQ or replay buffers. */
public final class ManagedSessionPreviews implements AutoCloseable {
    interface Transport {
        List<String> interest(ManagedScope scope);

        void register(ManagedScope scope, String token);

        void remove(ManagedScope scope, String token);

        void publish(String node, String payload);

        AutoCloseable listen(String node, java.util.function.Consumer<String> receive);
    }

    private static final int MAX_FRAME = 16 * 1024;
    private static final int MAX_BUFFER = 256 * 1024;
    private final String node = UUID.randomUUID().toString();
    private final ObjectMapper mapper;
    private final Transport transport;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor sender =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(256),
                    task -> {
                        var t = new Thread(task, "managed-preview-publisher");
                        t.setDaemon(true);
                        return t;
                    });
    private final ScheduledExecutorService renewal =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        var t = new Thread(task, "managed-preview-interest");
                        t.setDaemon(true);
                        return t;
                    });
    private AutoCloseable listener;

    public ManagedSessionPreviews(RedisManagedInfrastructure redis, ObjectMapper mapper) {
        this(new RedisTransport(redis), mapper);
    }

    ManagedSessionPreviews(Transport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
        renewal.scheduleWithFixedDelay(
                () ->
                        connections
                                .values()
                                .forEach(
                                        c -> {
                                            try {
                                                transport.register(c.scope, c.token);
                                            } catch (RuntimeException ignored) {
                                                c.disable();
                                            }
                                        }),
                10,
                10,
                TimeUnit.SECONDS);
    }

    public synchronized Connection open(ManagedScope scope) {
        var connection = new Connection(scope, node + ":" + UUID.randomUUID());
        try {
            if (listener == null) listener = transport.listen(node, this::receive);
            connections.put(connection.token, connection);
            transport.register(scope, connection.token);
        } catch (RuntimeException unavailable) {
            connection.disable();
        }
        return connection;
    }

    Producer begin(ManagedScope scope, String turn, String call, long epoch, String purpose) {
        List<String> targets = List.of();
        if ("REASONING".equals(purpose))
            try {
                targets = transport.interest(scope);
            } catch (RuntimeException ignored) {
            }
        return new Producer(scope, turn, call, epoch, targets);
    }

    public boolean hasInterest(ManagedScope scope) {
        try {
            return !transport.interest(scope).isEmpty();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Called only after the Service has checked the active execution and model-call fence. */
    public boolean publishText(
            ManagedScope scope,
            String turn,
            String call,
            long epoch,
            String message,
            long sequence,
            List<String> chunks) {
        if (sequence < 0
                || chunks.isEmpty()
                || chunks.size() > 16
                || sequence > Long.MAX_VALUE - chunks.size() - 1) return false;
        var producer = begin(scope, turn, call, epoch, "REASONING");
        producer.sequence = sequence;
        // Sequence zero includes event_start. Later batches cannot introduce a late
        // subscriber to the middle of a message; its connection rejects missing starts.
        if (sequence != 0) producer.message = message;
        producer.accept(new TextDelta(message, chunks));
        return !producer.targets.isEmpty() && !producer.stopped.get();
    }

    public record TextDelta(String messageId, List<String> text) {}

    final class Producer implements Consumer<TextDelta> {
        private final ManagedScope scope;
        private final String turn, call;
        private final long epoch;
        private final List<String> targets;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private long sequence;
        private String message;

        Producer(ManagedScope scope, String turn, String call, long epoch, List<String> targets) {
            this.scope = scope;
            this.turn = turn;
            this.call = call;
            this.epoch = epoch;
            this.targets = List.copyOf(targets);
        }

        @Override
        public synchronized void accept(TextDelta chunk) {
            if (targets.isEmpty() || stopped.get() || chunk.text() == null) return;
            if (message != null && !message.equals(chunk.messageId())) {
                stopped.set(true);
                return;
            }
            for (String text : chunk.text()) {
                if (text == null || text.isEmpty()) continue;
                if (text.length() > MAX_FRAME / 4) {
                    stopped.set(true);
                    return;
                }
                if (message == null) {
                    message = chunk.messageId();
                    emit(
                            Map.of(
                                    "type",
                                    "event_start",
                                    "event",
                                    Map.of(
                                            "type",
                                            "agent.message",
                                            "id",
                                            eventId(scope, turn, message))));
                }
                emit(
                        Map.of(
                                "type",
                                "event_delta",
                                "event_id",
                                eventId(scope, turn, message),
                                "delta",
                                Map.of(
                                        "type",
                                        "content_delta",
                                        "index",
                                        0,
                                        "content",
                                        Map.of("type", "text", "text", text))));
            }
        }

        private void emit(Map<String, Object> event) {
            if (stopped.get()) return;
            try {
                String payload =
                        mapper.writeValueAsString(
                                Map.of(
                                        "scope",
                                        scope.messageGroup(),
                                        "call",
                                        ManagedScope.fingerprint(turn, call, message),
                                        "epoch",
                                        epoch,
                                        "sequence",
                                        sequence++,
                                        "endId",
                                        modelEndId(scope, turn, call),
                                        "targets",
                                        targets,
                                        "event",
                                        event));
                if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME) {
                    stopped.set(true);
                    return;
                }
                sender.execute(
                        () -> {
                            try {
                                for (String targetNode :
                                        targets.stream()
                                                .map(t -> t.substring(0, t.indexOf(':')))
                                                .distinct()
                                                .toList()) transport.publish(targetNode, payload);
                            } catch (RuntimeException ignored) {
                                stopped.set(true);
                            }
                        });
            } catch (Exception ignored) {
                stopped.set(true);
            }
        }
    }

    public final class Connection implements AutoCloseable {
        private final ManagedScope scope;
        private final String token;
        private final Sinks.Many<String> queue =
                Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(64));
        private final LinkedHashMap<String, State> states = new LinkedHashMap<>();
        private final Set<String> terminal = ConcurrentHashMap.newKeySet();
        private boolean disabled;
        private long epoch;
        private int buffered;

        private Connection(ManagedScope scope, String token) {
            this.scope = scope;
            this.token = token;
        }

        private final class State {
            long next;
            String eventId, endId;
            boolean stopped;
        }

        public Flux<ServerSentEvent<String>> events() {
            return queue.asFlux()
                    .doOnNext(
                            s -> {
                                synchronized (this) {
                                    buffered -= s.length();
                                }
                            })
                    .map(
                            s -> {
                                try {
                                    return ServerSentEvent.builder(s)
                                            .event(
                                                    String.valueOf(
                                                            mapper.readTree(s)
                                                                    .path("type")
                                                                    .asText()))
                                            .build();
                                } catch (Exception invalid) {
                                    throw new IllegalStateException(invalid);
                                }
                            });
        }

        synchronized void receive(Map<?, ?> envelope) throws Exception {
            if (disabled || !scope.messageGroup().equals(envelope.get("scope"))) return;
            long incoming = ((Number) envelope.get("epoch")).longValue();
            if (incoming < epoch) return;
            if (incoming > epoch) {
                states.values().forEach(state -> state.stopped = true);
                epoch = incoming;
            }
            String call = String.valueOf(envelope.get("call"));
            var event = (Map<?, ?>) envelope.get("event");
            boolean start = "event_start".equals(event.get("type"));
            String id =
                    start
                            ? String.valueOf(((Map<?, ?>) event.get("event")).get("id"))
                            : String.valueOf(event.get("event_id"));
            String endId = String.valueOf(envelope.get("endId"));
            if (terminal.contains(id) || terminal.contains(endId)) return;
            State state = states.get(call);
            if (state == null) {
                if (!start || states.size() >= 128) return;
                state = new State();
                state.eventId = id;
                state.endId = endId;
                states.put(call, state);
            }
            if (state.stopped || !id.equals(state.eventId)) return;
            long seq = ((Number) envelope.get("sequence")).longValue();
            if (seq != state.next++) {
                state.stopped = true;
                return;
            }
            String json = mapper.writeValueAsString(event);
            if (buffered + json.length() > MAX_BUFFER) {
                state.stopped = true;
                return;
            }
            buffered += json.length();
            if (queue.tryEmitNext(json).isFailure()) {
                buffered -= json.length();
                state.stopped = true;
            }
        }

        public synchronized void reconcile(Map<String, Object> event) {
            String type = String.valueOf(event.get("type"));
            if (Set.of("agent.message", "span.model_request_end").contains(type)) {
                if (terminal.size() >= 512) {
                    disable();
                    return;
                }
                String id = String.valueOf(event.get("id"));
                terminal.add(id);
                states.values().stream()
                        .filter(s -> id.equals(s.eventId) || id.equals(s.endId))
                        .forEach(s -> s.stopped = true);
            } else if (Set.of("session.status_idle", "session.status_terminated").contains(type))
                states.values()
                        .forEach(
                                s -> {
                                    s.stopped = true;
                                    terminal.add(s.eventId);
                                });
        }

        public synchronized boolean deliver(ServerSentEvent<String> frame) {
            if (frame.data() == null) return true;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> event = mapper.readValue(frame.data(), Map.class);
                String type = String.valueOf(event.get("type"));
                if (!Set.of("event_start", "event_delta").contains(type)) {
                    reconcile(event);
                    return true;
                }
                String id =
                        "event_start".equals(type)
                                ? String.valueOf(((Map<?, ?>) event.get("event")).get("id"))
                                : String.valueOf(event.get("event_id"));
                return !terminal.contains(id)
                        && states.values().stream()
                                .filter(state -> id.equals(state.eventId))
                                .noneMatch(state -> terminal.contains(state.endId));
            } catch (Exception invalid) {
                return false;
            }
        }

        synchronized void disable() {
            disabled = true;
            states.values().forEach(s -> s.stopped = true);
        }

        @Override
        public void close() {
            connections.remove(token);
            disable();
            queue.tryEmitComplete();
            try {
                transport.remove(scope, token);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void receive(String payload) {
        if (payload.length() > MAX_FRAME) return;
        try {
            var frame = mapper.readValue(payload, Map.class);
            for (Object target : (List<?>) frame.get("targets")) {
                var connection = connections.get(String.valueOf(target));
                if (connection != null) connection.receive(frame);
            }
        } catch (Exception ignored) {
            // A subsequent sequence gap stops only the affected preview.
        }
    }

    static String eventId(ManagedScope scope, String turn, String message) {
        return "evt_"
                + ManagedScope.fingerprint(scope.messageGroup(), "message:" + turn + ":" + message);
    }

    static String modelEndId(ManagedScope scope, String turn, String call) {
        return "evt_" + ManagedScope.fingerprint(scope.messageGroup(), "model_end", turn, call);
    }

    @Override
    public void close() {
        renewal.shutdownNow();
        sender.shutdownNow();
        new ArrayList<>(connections.values()).forEach(Connection::close);
        try {
            if (listener != null) listener.close();
        } catch (Exception ignored) {
        }
    }

    private static final class RedisTransport implements Transport {
        private final RedisManagedInfrastructure redis;

        RedisTransport(RedisManagedInfrastructure redis) {
            this.redis = redis;
        }

        private String key(ManagedScope scope) {
            return "ma:preview:interest:" + scope.messageGroup();
        }

        @Override
        public List<String> interest(ManagedScope scope) {
            try {
                List<Object> values =
                        redis.busCommands()
                                .getStatefulConnection()
                                .async()
                                .<List<Object>>eval(
                                        "local t=redis.call('TIME');local"
                                            + " now=t[1]*1000+math.floor(t[2]/1000);redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now);return"
                                            + " redis.call('ZRANGE',KEYS[1],0,127)",
                                        ScriptOutputType.MULTI,
                                        new String[] {key(scope)})
                                .get(100, TimeUnit.MILLISECONDS);
                return values.stream().map(String::valueOf).toList();
            } catch (Exception unavailable) {
                return List.of();
            }
        }

        @Override
        public void register(ManagedScope scope, String token) {
            try {
                redis.busCommands()
                        .getStatefulConnection()
                        .async()
                        .eval(
                                "local t=redis.call('TIME');local"
                                    + " now=t[1]*1000+math.floor(t[2]/1000);redis.call('ZADD',KEYS[1],now+30000,ARGV[1]);redis.call('PEXPIRE',KEYS[1],40000);return"
                                    + " 1",
                                ScriptOutputType.INTEGER,
                                new String[] {key(scope)},
                                token)
                        .get(150, TimeUnit.MILLISECONDS);
            } catch (Exception unavailable) {
                throw new ManagedTransportException("Preview interest unavailable", unavailable);
            }
        }

        @Override
        public void remove(ManagedScope scope, String token) {
            redis.busCommands().getStatefulConnection().async().zrem(key(scope), token);
        }

        @Override
        public void publish(String node, String payload) {
            redis.busCommands().publish("ma:preview:node:" + node, payload);
        }

        @Override
        public AutoCloseable listen(String node, java.util.function.Consumer<String> receive) {
            return redis.previewListen("ma:preview:node:" + node, receive);
        }
    }
}
