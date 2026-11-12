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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.XAddArgs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Scoped Harness primitives; user runtime input acceptance uses the separate durable Input journal. */
public final class ManagedRedisMessageBus implements MessageBus {
    private static final String DRAIN =
            "local v=redis.call('LRANGE',KEYS[1],0,tonumber(ARGV[1])-1);"
                    + " redis.call('LTRIM',KEYS[1],ARGV[1],-1); return v";
    private static final String PUSH =
            "if redis.call('LLEN',KEYS[1])>=tonumber(ARGV[1]) then return 0 end;"
                    + " redis.call('RPUSH',KEYS[1],ARGV[2]); return 1";
    private final ManagedScope scope;
    private final RedisManagedInfrastructure redis;
    private final ObjectMapper mapper;

    public ManagedRedisMessageBus(
            ManagedScope scope, RedisManagedInfrastructure redis, ObjectMapper mapper) {
        this.scope = scope;
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return blocking(
                () -> {
                    String id = UUID.randomUUID().toString();
                    Long appended =
                            redis.busCommands()
                                    .eval(
                                            PUSH,
                                            ScriptOutputType.INTEGER,
                                            new String[] {key("queue", key)},
                                            "10000",
                                            json(new BusEntry(id, payload)));
                    if (appended == null || appended != 1)
                        throw new ManagedStateException("bus_capacity", "Harness queue is full");
                    return id;
                });
    }

    @Override
    public Mono<List<BusEntry>> queueDrain(String key, int count) {
        bounds(count);
        return blocking(
                () -> {
                    List<String> values =
                            redis.busCommands()
                                    .eval(
                                            DRAIN,
                                            ScriptOutputType.MULTI,
                                            new String[] {key("queue", key)},
                                            Integer.toString(count));
                    return values.stream().map(this::entry).toList();
                });
    }

    @Override
    public Mono<Void> queueDelete(String key) {
        return blocking(() -> redis.busCommands().del(key("queue", key))).then();
    }

    @Override
    public Mono<Boolean> queuePeek(String key) {
        return blocking(() -> redis.busCommands().llen(key("queue", key)) > 0);
    }

    @Override
    public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        return blocking(
                () -> {
                    var args = new XAddArgs();
                    if (maxLen > 0) args.maxlen(maxLen).approximateTrimming(true);
                    return redis.busCommands()
                            .xadd(key("log", key), args, Map.of("payload", json(payload)));
                });
    }

    @Override
    public Mono<List<BusEntry>> logRead(String key, String since, int count) {
        bounds(count);
        return blocking(
                () ->
                        redis
                                .busCommands()
                                .xrange(
                                        key("log", key),
                                        since == null
                                                ? Range.unbounded()
                                                : Range.from(
                                                        Range.Boundary.excluding(since),
                                                        Range.Boundary.unbounded()),
                                        Limit.from(count))
                                .stream()
                                .map(
                                        value ->
                                                new BusEntry(
                                                        value.getId(),
                                                        payload(value.getBody().get("payload"))))
                                .toList());
    }

    @Override
    public Mono<Void> logTrim(String key) {
        return blocking(() -> redis.busCommands().del(key("log", key))).then();
    }

    @Override
    public Mono<Void> publish(String key, Map<String, Object> payload) {
        return blocking(
                        () -> {
                            redis.busPublish(key("broadcast", key), json(payload));
                            return 0;
                        })
                .then();
    }

    @Override
    public Flux<Map<String, Object>> subscribe(String key) {
        return Flux.<Map<String, Object>>create(
                        sink -> {
                            try {
                                var registration =
                                        redis.busListen(
                                                key("broadcast", key),
                                                value -> {
                                                    try {
                                                        sink.next(payload(value));
                                                    } catch (RuntimeException invalid) {
                                                        sink.error(invalid);
                                                    }
                                                });
                                sink.onDispose(
                                        () -> {
                                            try {
                                                registration.close();
                                            } catch (Exception failure) {
                                                sink.error(failure);
                                            }
                                        });
                            } catch (RuntimeException unavailable) {
                                sink.error(unavailable);
                            }
                        },
                        FluxSink.OverflowStrategy.LATEST)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String key(String mode, String key) {
        return scope.key("harness:" + mode + ":" + ManagedScope.fingerprint(key));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid Harness message", invalid);
        }
    }

    private BusEntry entry(String value) {
        try {
            return mapper.readValue(value, BusEntry.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid Harness queue entry", invalid);
        }
    }

    private Map<String, Object> payload(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid Harness message", invalid);
        }
    }

    private static <T> Mono<T> blocking(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    private static void bounds(int count) {
        if (count < 1 || count > 4096)
            throw new IllegalArgumentException("Invalid Harness read limit");
    }
}
