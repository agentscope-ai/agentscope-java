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

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Separate control and blocking-read connections keep XREAD from delaying lease renewal. */
public final class RedisManagedInfrastructure
        implements ManagedLeaseStore, ManagedInputInbox, AutoCloseable {
    private static final String RATE =
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], 2000) end
            return count
            """;
    private final AtomicLong rateWarningSecond = new AtomicLong();

    /** Soft application limit; Redis errors do not stop admission. Execution leases remain strict. */
    public boolean allowApplicationRequest(String group, int limit) {
        long second = System.currentTimeMillis() / 1000;
        try {
            Long count =
                    control.async()
                            .<Long>eval(
                                    RATE,
                                    ScriptOutputType.INTEGER,
                                    new String[] {"ma:admission-rate:" + group + ":" + second})
                            .get(200, TimeUnit.MILLISECONDS);
            return count == null || count <= limit;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ManagedTransportException("Application rate check interrupted", interrupted);
        } catch (ExecutionException | TimeoutException | RuntimeException unavailable) {
            if (rateWarningSecond.getAndSet(second) != second)
                LoggerFactory.getLogger(RedisManagedInfrastructure.class)
                        .warn(
                                "Application rate check unavailable; admitting without rate"
                                        + " enforcement");
            return true;
        }
    }

    private static final String RENEW =
            """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            """;
    private static final String RELEASE =
            """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """;
    private static final String APPEND =
            """
            local old = redis.call('HGET', KEYS[2], ARGV[1])
            if old then
                local split = string.find(old, '|', 1, true)
                if string.sub(old, split + 1) ~= ARGV[2] then return {'conflict', ''} end
                return {'ok', string.sub(old, 1, split - 1)}
            end
            local id = redis.call('XADD', KEYS[1], '*',
                'inputId', ARGV[1], 'sequence', ARGV[3], 'type', ARGV[4],
                'actionId', ARGV[5], 'payloadReference', ARGV[6])
            redis.call('HSET', KEYS[2], ARGV[1], id .. '|' .. ARGV[2])
            if ARGV[4] == 'CANCEL' then redis.call('SET', KEYS[3], ARGV[1]) end
            return {'ok', id}
            """;
    private static final String EXPIRE =
            """
            for _, key in ipairs(KEYS) do redis.call('PEXPIRE', key, ARGV[1]) end
            return 1
            """;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> control;
    private final Semaphore readCapacity;
    private final ConcurrentLinkedQueue<StatefulRedisConnection<String, String>> idle =
            new ConcurrentLinkedQueue<>();
    private final Set<StatefulRedisConnection<String, String>> readers =
            ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private static final String BUS_BROADCAST = "ma:harness:notifications";
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<String>>> broadcasts =
            new ConcurrentHashMap<>();
    private StatefulRedisPubSubConnection<String, String> pubsub;

    public RedisManagedInfrastructure(String uri, int maxBlockingReads) {
        if (maxBlockingReads < 1)
            throw new IllegalArgumentException("Blocking read capacity must be positive");
        client = RedisClient.create(uri);
        client.setDefaultTimeout(Duration.ofSeconds(5));
        try {
            control = client.connect();
        } catch (RuntimeException unavailable) {
            client.shutdown();
            throw unavailable;
        }
        readCapacity = new Semaphore(maxBlockingReads);
    }

    @Override
    public Optional<Lease> acquire(String key, Duration ttl) {
        var lease = new Lease(key, UUID.randomUUID().toString());
        String response =
                commands()
                        .set(
                                key,
                                lease.token(),
                                SetArgs.Builder.nx().px(ManagedMessageBus.positiveMillis(ttl)));
        return "OK".equals(response) ? Optional.of(lease) : Optional.empty();
    }

    @Override
    public boolean renew(Lease lease, Duration ttl) {
        Long result =
                commands()
                        .eval(
                                RENEW,
                                ScriptOutputType.INTEGER,
                                new String[] {lease.key()},
                                lease.token(),
                                Long.toString(ManagedMessageBus.positiveMillis(ttl)));
        return result != null && result == 1;
    }

    @Override
    public boolean owns(Lease lease) {
        return lease.token().equals(commands().get(lease.key()));
    }

    @Override
    public boolean release(Lease lease) {
        Long result =
                commands()
                        .eval(
                                RELEASE,
                                ScriptOutputType.INTEGER,
                                new String[] {lease.key()},
                                lease.token());
        return result != null && result == 1;
    }

    @Override
    public String append(ManagedScope scope, String turnId, Signal signal) {
        List<String> result =
                commands()
                        .eval(
                                APPEND,
                                ScriptOutputType.MULTI,
                                inputKeys(scope, turnId),
                                signal.inputId(),
                                fingerprint(signal),
                                Long.toString(signal.sequence()),
                                signal.type().name(),
                                signal.actionId() == null ? "" : signal.actionId(),
                                signal.payloadReference());
        if ("conflict".equals(result.get(0)))
            throw new IllegalArgumentException("Input identity reused with different signal");
        return result.get(1);
    }

    @Override
    public List<Entry> read(
            ManagedScope scope, String turnId, String after, int limit, Duration block) {
        return readStream(inputKeys(scope, turnId)[0], after, limit, block).stream()
                .map(
                        message -> {
                            var body = message.getBody();
                            String action = body.get("actionId");
                            return new Entry(
                                    message.getId(),
                                    new Signal(
                                            body.get("inputId"),
                                            Long.parseLong(body.get("sequence")),
                                            Type.valueOf(body.get("type")),
                                            action == null || action.isEmpty() ? null : action,
                                            body.get("payloadReference")));
                        })
                .toList();
    }

    List<StreamMessage<String, String>> readStream(
            String key, String after, int limit, Duration block) {
        checkOpen();
        if (limit < 1
                || block == null
                || block.isNegative()
                || block.compareTo(Duration.ofSeconds(2)) > 0)
            throw new IllegalArgumentException(
                    "Input read block must be between zero and 2 seconds");
        if (!readCapacity.tryAcquire()) throw new ManagedReadCapacityException();
        StatefulRedisConnection<String, String> reader = null;
        boolean reusable = false;
        try {
            reader = idle.poll();
            if (reader == null || !reader.isOpen()) {
                if (reader != null) readers.remove(reader);
                reader = client.connect();
                readers.add(reader);
            }
            var args = new XReadArgs().count(limit);
            if (!block.isZero()) args.block(block);
            var messages = reader.sync().xread(args, XReadArgs.StreamOffset.from(key, after));
            reusable = true;
            if (messages == null) return List.of();
            return List.copyOf(messages);
        } finally {
            if (reader != null) {
                if (reusable && reader.isOpen() && !closed.get()) idle.offer(reader);
                else {
                    readers.remove(reader);
                    reader.close();
                }
            }
            readCapacity.release();
        }
    }

    @Override
    public boolean cancelled(ManagedScope scope, String turnId) {
        return commands().get(inputKeys(scope, turnId)[2]) != null;
    }

    @Override
    public void expire(ManagedScope scope, String turnId, Duration retention) {
        commands()
                .eval(
                        EXPIRE,
                        ScriptOutputType.INTEGER,
                        inputKeys(scope, turnId),
                        Long.toString(ManagedMessageBus.positiveMillis(retention)));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        readers.forEach(StatefulRedisConnection::close);
        idle.clear();
        if (pubsub != null) pubsub.close();
        broadcasts.clear();
        control.close();
        client.shutdown();
    }

    RedisCommands<String, String> busCommands() {
        return commands();
    }

    void busPublish(String key, String payload) {
        commands().publish(BUS_BROADCAST, key + "\n" + payload);
    }

    synchronized AutoCloseable busListen(String key, Consumer<String> listener) {
        checkOpen();
        if (pubsub == null) {
            pubsub = client.connectPubSub();
            pubsub.addListener(
                    new RedisPubSubAdapter<>() {
                        @Override
                        public void message(String channel, String message) {
                            int split = message.indexOf('\n');
                            if (split < 1) return;
                            var targets = broadcasts.get(message.substring(0, split));
                            if (targets != null)
                                for (var target : targets)
                                    target.accept(message.substring(split + 1));
                        }
                    });
            pubsub.sync().subscribe(BUS_BROADCAST);
        }
        broadcasts.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () ->
                broadcasts.computeIfPresent(
                        key,
                        (name, current) -> {
                            current.remove(listener);
                            return current.isEmpty() ? null : current;
                        });
    }

    AutoCloseable previewListen(String channel, Consumer<String> receive) {
        var connection = client.connectPubSub();
        connection.addListener(
                new RedisPubSubAdapter<String, String>() {
                    @Override
                    public void message(String name, String payload) {
                        if (channel.equals(name)) receive.accept(payload);
                    }
                });
        try {
            connection.sync().subscribe(channel);
        } catch (RuntimeException unavailable) {
            connection.close();
            throw unavailable;
        }
        return connection::close;
    }

    private RedisCommands<String, String> commands() {
        checkOpen();
        return control.sync();
    }

    private void checkOpen() {
        if (closed.get()) throw new ManagedTransportException("Redis infrastructure is closed");
    }

    private static String[] inputKeys(ManagedScope scope, String turnId) {
        ManagedScope.requireText(turnId, "turn id");
        return new String[] {
            scope.key("inputs:" + turnId),
            scope.key("input-seen:" + turnId),
            scope.key("cancel:" + turnId)
        };
    }

    private static String fingerprint(Signal signal) {
        return ManagedScope.fingerprint(
                signal.inputId(),
                Long.toString(signal.sequence()),
                signal.type().name(),
                signal.actionId() == null ? "" : signal.actionId(),
                signal.payloadReference());
    }
}
