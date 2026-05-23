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
package io.agentscope.harness.claw.app.toolbus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.UnifiedJedis;

/**
 * Redis-Pub/Sub-backed {@link ToolEventBus} for cross-replica SSE delivery in distributed
 * deployments.
 *
 * <p>Publish writes a JSON-serialised event to a single Redis channel ({@code claw:toolbus}); a
 * dedicated subscriber thread reads from the same channel and re-emits each event into a local
 * Reactor sink, so multiple per-session subscriptions across all replicas all see it.
 *
 * <p>Active only when {@code claw.session.redis.enabled=true} and a {@link UnifiedJedis} bean is
 * available (provided by {@link
 * io.agentscope.harness.claw.app.config.SessionBackendConfig}). When active, this bean is marked
 * {@link Primary}, so any existing {@link LocalToolEventBus} is shadowed — but both still get
 * registered, which is fine because the auto-wired consumer (ChatController) takes the primary.
 */
@Component
@ConditionalOnProperty(name = "claw.session.redis.enabled", havingValue = "true")
@ConditionalOnBean(UnifiedJedis.class)
@Primary
public class RedisToolEventBus implements ToolEventBus {

    private static final Logger log = LoggerFactory.getLogger(RedisToolEventBus.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UnifiedJedis jedis;
    private final String channel;
    private final Sinks.Many<ToolEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);
    private volatile Thread subscriberThread;
    private volatile JedisPubSub pubSub;

    public RedisToolEventBus(
            UnifiedJedis jedis,
            @Value("${claw.session.redis.toolbus-channel:claw:toolbus}") String channel) {
        this.jedis = jedis;
        this.channel = channel;
    }

    @PostConstruct
    public void start() {
        pubSub =
                new JedisPubSub() {
                    @Override
                    public void onMessage(String ch, String message) {
                        try {
                            ToolEvent ev = MAPPER.readValue(message, ToolEvent.class);
                            sink.tryEmitNext(ev);
                        } catch (IOException e) {
                            log.warn(
                                    "Failed to deserialise tool event from Redis on {}: {}",
                                    ch,
                                    e.getMessage());
                        }
                    }
                };
        subscriberThread =
                new Thread(
                        () -> {
                            try {
                                jedis.subscribe(pubSub, channel);
                            } catch (Exception e) {
                                if (!Thread.currentThread().isInterrupted()) {
                                    log.warn(
                                            "Redis tool-event subscriber exited: {}",
                                            e.getMessage());
                                }
                            }
                        },
                        "claw-toolbus-sub");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        log.info("RedisToolEventBus started on channel '{}'", channel);
    }

    @PreDestroy
    public void stop() {
        try {
            if (pubSub != null && pubSub.isSubscribed()) pubSub.unsubscribe();
        } catch (Exception ignored) {
            // best-effort
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
    }

    @Override
    public void publish(ToolEvent event) {
        try {
            jedis.publish(channel, MAPPER.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.warn("Failed to publish tool event to Redis: {}", e.getMessage());
        }
    }

    @Override
    public Flux<ToolEvent> subscribe(String sessionKey) {
        return sink.asFlux().filter(e -> sessionKey.equals(e.sessionKey()));
    }
}
