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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.server.agentservice.sessionservice.store.HarnessProtocolStore;
import io.agentcore.managed.server.runtime.managed.ManagedResumeSignals;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.web.auth.InternalTokenAuthFilter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Service sends hints from durable queue facts; no broker receipt depends on Brain availability. */
@Component
@ConditionalOnProperty(name = "managed-agents.brain.http-enabled", havingValue = "true")
public final class BrainTaskDispatcher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BrainTaskDispatcher.class);
    private final DataSource source;
    private final HarnessProtocolStore protocol;
    private final ObjectMapper mapper;
    private final URI callback;
    private final String token;
    private final String harness;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile boolean closed;
    private AutoCloseable subscription;

    public BrainTaskDispatcher(
            DataSource source,
            HarnessProtocolStore protocol,
            ObjectMapper mapper,
            @Value("${managed-agents.brain.callback-url}") String callback,
            @Value("${managed-agents.internal-token:${MANAGED_AGENTS_INTERNAL_TOKEN:}}")
                    String token,
            @Value("${managed-agents.runtime.harness-type:agentscope-java}") String harness) {
        this.source = source;
        this.protocol = protocol;
        this.mapper = mapper;
        this.callback = URI.create(callback);
        if (!Set.of("http", "https").contains(this.callback.getScheme())
                || this.callback.getHost() == null
                || this.callback.getUserInfo() != null
                || this.callback.getFragment() != null
                || this.callback.getQuery() != null
                || token.isBlank())
            throw new IllegalArgumentException("Invalid configured Brain callback");
        this.token = token;
        this.harness = harness;
    }

    @PostConstruct
    public void start() {
        subscription = ManagedResumeSignals.subscribe(source, ignored -> wake());
        worker.scheduleWithFixedDelay(this::wake, 0, 30, TimeUnit.SECONDS);
    }

    private void wake() {
        schedule(0);
    }

    private void schedule(long delay) {
        if (!closed && queued.compareAndSet(false, true))
            worker.schedule(this::dispatch, delay, TimeUnit.MILLISECONDS);
    }

    private void dispatch() {
        queued.set(false);
        if (closed) return;
        boolean retry = false;
        try {
            var request =
                    mapper.createObjectNode()
                            .put("protocolVersion", "0.1.0")
                            .put("harnessType", harness)
                            .put("limit", 8);
            var candidates = protocol.invoke("executions/next", request).required("deliveries");
            for (var candidate : candidates) {
                if (closed) break;
                // A failed hint must not pin the head of the durable queue or abort this batch.
                // Deferral changes only discovery timing, never ownership or completion.
                try {
                    var call =
                            HttpRequest.newBuilder(callback)
                                    .timeout(Duration.ofSeconds(2))
                                    .header(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, token)
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(candidate.toString()))
                                    .build();
                    var response = http.send(call, HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() != 202)
                        log.debug("Brain task hint rejected: status={}", response.statusCode());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception unavailable) {
                    log.warn(
                            "Brain task hint failed: session={}, cause={}",
                            candidate.path("scope").path("sessionId").asText(),
                            unavailable.getClass().getSimpleName());
                }
                try {
                    // If claim won the race, Service rejects defer. A 202 alone grants no lease.
                    defer(candidate);
                } catch (RuntimeException unavailable) {
                    log.warn(
                            "Brain task deferral will be repaired: {}",
                            unavailable.getClass().getSimpleName());
                }
                retry = true;
            }
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn(
                    "Brain task notification will be repaired: {}",
                    unavailable.getClass().getSimpleName());
            retry = true;
        }
        if (retry) schedule(1000);
    }

    private void defer(JsonNode candidate) {
        var request = candidate.deepCopy();
        var body = (ObjectNode) request;
        body.put("protocolVersion", "0.1.0")
                .put("deferralId", UUID.randomUUID().toString())
                .put("retryAfterMs", 1000)
                .put("reason", "temporarily_unavailable");
        try {
            protocol.invoke("deliveries/defer", body);
        } catch (ManagedStateException claimed) {
            if (!"execution_busy".equals(claimed.code())) throw claimed;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
        if (subscription != null)
            try {
                subscription.close();
            } catch (Exception failure) {
                log.debug("Notification subscription closed", failure);
            }
    }
}
