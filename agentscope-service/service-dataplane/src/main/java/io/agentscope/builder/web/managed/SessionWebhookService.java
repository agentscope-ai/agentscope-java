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
package io.agentscope.builder.web.managed;

import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Durable at-least-once event notifications, independently leased across service replicas. */
@Service
public final class SessionWebhookService {
    private static final List<String> HOOKS = List.of("runtime", "agent-api", "webhooks");
    private final BaseStore store;
    private final SessionEventLog events;
    private final DataSessionService sessions;
    private final Set<String> allowedHosts;
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final String worker = UUID.randomUUID().toString();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private int offset;

    public SessionWebhookService(
            BaseStore store,
            SessionEventLog events,
            DataSessionService sessions,
            @Value("${builder.agent-api.webhooks.allowed-hosts:}") String allowedHosts) {
        this.store = store;
        this.events = events;
        this.sessions = sessions;
        this.allowedHosts =
                Set.copyOf(
                        Arrays.stream(allowedHosts.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .map(String::toLowerCase)
                                .toList());
    }

    public record Registration(String url, List<String> event_types) {}

    private URI destination(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !allowedHosts.contains(uri.getHost().toLowerCase()))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Webhook must use HTTPS on an operator-allowed host (port 443)");
        return uri;
    }

    public Map<String, Object> register(
            String user, String session, String key, Registration input) {
        sessions.get(user, session);
        if (key == null
                || key.isBlank()
                || key.length() > 256
                || input.url() == null
                || input.url().length() > 4096)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A valid key and URL are required");
        destination(input.url());
        if (input.event_types() != null && input.event_types().stream().anyMatch(Objects::isNull))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event_types");
        var types =
                input.event_types() == null
                        ? List.of(
                                "turn.completed",
                                "turn.failed",
                                "turn.requires_action",
                                "turn.interrupted")
                        : input.event_types().stream().distinct().sorted().toList();
        if (types.isEmpty()
                || types.size() > 100
                || types.stream().anyMatch(type -> !type.matches("[a-z_]+\\.(?:[a-z_]+|\\*)")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event_types");
        String id =
                "wh_"
                        + JournalSessionLog.hash(
                                (user + "\n" + session + "\n" + key)
                                        .getBytes(StandardCharsets.UTF_8));
        var existing = store.get(HOOKS, id);
        if (existing == null) {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            var hook = new LinkedHashMap<String, Object>();
            hook.put("id", id);
            hook.put("session_id", session);
            hook.put("user_id", user);
            hook.put("url", input.url());
            hook.put("event_types", types);
            hook.put(
                    "signing_secret",
                    Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
            hook.put("created_at", System.currentTimeMillis());
            hook.put("enabled", true);
            hook.put("cursor", events.highWatermark(session));
            hook.put("attempt", 0);
            hook.put("next_attempt_at", 0L);
            hook.put("lease_until", 0L);
            hook.put("status", "active");
            store.putIfVersion(HOOKS, id, hook, 0);
            existing = store.get(HOOKS, id);
        }
        if (existing == null)
            throw new IllegalStateException("Webhook store requires atomic writes");
        var hook = existing.value();
        if (!input.url().equals(hook.get("url")) || !types.equals(hook.get("event_types")))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Webhook key reused with different registration");
        var result = publicView(hook);
        result.put("signing_secret", hook.get("signing_secret"));
        return result;
    }

    private Map<String, Object> publicView(Map<String, Object> hook) {
        var result = new LinkedHashMap<>(hook);
        result.remove("signing_secret");
        result.remove("user_id");
        result.remove("worker");
        result.remove("lease_until");
        result.put(
                "cursor",
                SessionEventCursor.encode((String) hook.get("session_id"), number(hook, "cursor")));
        return result;
    }

    public Map<String, Object> list(String user, String session, int offset, int limit) {
        sessions.get(user, session);
        if (offset < 0 || limit < 1 || limit > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        // Filter the shared directory by owner and session before applying the caller page.
        var result = new ArrayList<Map<String, Object>>();
        int index = 0, skipped = 0;
        boolean more = false;
        for (; ; ) {
            var batch = store.search(HOOKS, 100, index);
            index += batch.size();
            for (var item : batch)
                if (session.equals(item.value().get("session_id"))
                        && user.equals(item.value().get("user_id"))) {
                    if (skipped++ < offset) continue;
                    if (result.size() == limit) {
                        more = true;
                        break;
                    }
                    result.add(publicView(item.value()));
                }
            if (more || batch.size() < 100) break;
        }
        return Map.of("data", result, "next_offset", offset + result.size(), "has_more", more);
    }

    private Map<String, Object> authorized(String user, String session, String id) {
        sessions.get(user, session);
        var hook = store.get(HOOKS, id);
        if (hook == null
                || !session.equals(hook.value().get("session_id"))
                || !user.equals(hook.value().get("user_id")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return hook.value();
    }

    public Map<String, Object> update(String user, String session, String id, boolean delete) {
        authorized(user, session, id);
        for (int i = 0; i < 16; i++) {
            var current = store.get(HOOKS, id);
            var hook = new LinkedHashMap<>(current.value());
            if (number(hook, "lease_until") > System.currentTimeMillis())
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Delivery is in flight; retry after it settles");
            if (!delete && !Boolean.TRUE.equals(hook.get("enabled")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Webhook is disabled");
            hook.put("enabled", !delete);
            hook.put("status", delete ? "disabled" : "active");
            hook.put("attempt", 0);
            hook.put("next_attempt_at", 0L);
            if (store.putIfVersion(HOOKS, id, hook, current.version())) return publicView(hook);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Webhook changed concurrently");
    }

    public Map<String, Object> deliveries(
            String user, String session, String id, int offset, int limit) {
        authorized(user, session, id);
        if (offset < 0 || limit < 1 || limit > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var batch =
                store.search(
                        List.of("runtime", "agent-api", "webhook-deliveries", id),
                        limit + 1,
                        offset);
        return Map.of(
                "data",
                batch.stream().limit(limit).map(item -> item.value()).toList(),
                "next_offset",
                offset + Math.min(limit, batch.size()),
                "has_more",
                batch.size() > limit);
    }

    private static long number(Map<String, Object> value, String key) {
        return ((Number) value.getOrDefault(key, 0L)).longValue();
    }

    @Scheduled(fixedDelayString = "${builder.agent-api.webhooks.poll-ms:2000}")
    public synchronized void dispatch() {
        if (allowedHosts.isEmpty() || active.size() >= 16) return;
        var batch = store.search(HOOKS, 32, offset);
        offset = batch.size() < 32 ? 0 : offset + 32;
        for (var item : batch) {
            if (active.size() >= 16) break;
            var hook = new LinkedHashMap<>(item.value());
            long now = System.currentTimeMillis();
            if (!Boolean.TRUE.equals(hook.get("enabled"))
                    || !"active".equals(hook.get("status"))
                    || number(hook, "next_attempt_at") > now
                    || number(hook, "lease_until") > now
                    || !active.add(item.key())) continue;
            hook.put("worker", worker);
            hook.put("lease_until", now + 60000);
            if (!store.putIfVersion(HOOKS, item.key(), hook, item.version())) {
                active.remove(item.key());
                continue;
            }
            Mono.fromRunnable(() -> deliver(item.key()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doFinally(signal -> active.remove(item.key()))
                    .subscribe(
                            ignored -> {},
                            error ->
                                    release(
                                            item.key(),
                                            Map.of(
                                                    "next_attempt_at",
                                                    System.currentTimeMillis() + 30000,
                                                    "last_error",
                                                    "delivery_worker_error")));
        }
    }

    private void deliver(String id) {
        var stored = store.get(HOOKS, id);
        if (stored == null) return;
        var hook = stored.value();
        String session = (String) hook.get("session_id");
        sessions.get((String) hook.get("user_id"), session);
        var destination = destination((String) hook.get("url"));
        var batch =
                events.page(session, number(hook, "cursor"), events.highWatermark(session), 256);
        SessionEventDto selected = null;
        long cursor = number(hook, "cursor");
        for (var event : batch) {
            if (AgentSessionView.isPublic(event)
                    && matches((List<?>) hook.get("event_types"), event.type())) {
                selected = event;
                break;
            }
            cursor = event.seq();
        }
        if (selected == null) {
            release(id, Map.of("cursor", cursor));
            return;
        }
        String delivery =
                JournalSessionLog.hash((id + ":" + selected.id()).getBytes(StandardCharsets.UTF_8));
        // A bounded notification points to the immutable event; large tool/file bodies stay in the
        // authenticated API.
        String body =
                JsonUtils.getJsonCodec()
                        .toJson(
                                Map.of(
                                        "id",
                                        selected.id(),
                                        "type",
                                        selected.type(),
                                        "session_id",
                                        session,
                                        "cursor",
                                        SessionEventCursor.encode(session, selected.seq()),
                                        "created_at",
                                        selected.createdAt(),
                                        "event_url",
                                        "/api/v1/agent-sessions/"
                                                + session
                                                + "/events/"
                                                + selected.id()));
        long timestamp = System.currentTimeMillis() / 1000;
        int attempt = (int) number(hook, "attempt") + 1, status = 0;
        String error = "";
        try {
            var request =
                    HttpRequest.newBuilder(destination)
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .header("X-AgentScope-Delivery", delivery)
                            .header("X-AgentScope-Timestamp", Long.toString(timestamp))
                            .header(
                                    "X-AgentScope-Signature",
                                    "v1="
                                            + signature(
                                                    (String) hook.get("signing_secret"),
                                                    timestamp,
                                                    body))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        } catch (Exception failure) {
            error = failure.getClass().getSimpleName();
        }
        boolean success = status >= 200 && status < 300;
        store.put(
                List.of("runtime", "agent-api", "webhook-deliveries", id),
                delivery + "_" + System.currentTimeMillis(),
                Map.of(
                        "delivery_id",
                        delivery,
                        "event_id",
                        selected.id(),
                        "attempt",
                        attempt,
                        "status_code",
                        status,
                        "status",
                        success ? "delivered" : "failed",
                        "error",
                        error,
                        "created_at",
                        System.currentTimeMillis()));
        var update = new LinkedHashMap<String, Object>();
        update.put("cursor", success ? selected.seq() : cursor);
        update.put("attempt", success ? 0 : attempt);
        update.put(
                "next_attempt_at",
                success
                        ? 0L
                        : System.currentTimeMillis()
                                + Math.min(300000L, 1000L << (Math.min(attempt, 8))));
        update.put("status", !success && attempt >= 8 ? "failed" : "active");
        update.put("last_status_code", status);
        update.put("last_error", error);
        release(id, update);
    }

    private boolean matches(List<?> types, String type) {
        return types.stream()
                .map(String::valueOf)
                .anyMatch(
                        pattern ->
                                pattern.equals(type)
                                        || pattern.endsWith(".*")
                                                && type.startsWith(
                                                        pattern.substring(
                                                                0, pattern.length() - 1)));
    }

    private void release(String id, Map<String, Object> changes) {
        for (int i = 0; i < 16; i++) {
            var current = store.get(HOOKS, id);
            if (current == null || !worker.equals(current.value().get("worker"))) return;
            var updated = new LinkedHashMap<>(current.value());
            updated.putAll(changes);
            updated.put("lease_until", 0L);
            updated.remove("worker");
            if (store.putIfVersion(HOOKS, id, updated, current.version())) return;
        }
    }

    public static String signature(String secret, long timestamp, String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot sign webhook", failure);
        }
    }
}
