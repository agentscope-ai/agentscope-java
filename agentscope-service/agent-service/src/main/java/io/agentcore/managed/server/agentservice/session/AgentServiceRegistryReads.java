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
package io.agentcore.managed.server.agentservice.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionPermissionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnRepository;
import io.agentcore.managed.server.runtime.protocol.RuntimePayloadDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Keyset registry views read only indexed grants and a bounded page of shared Agent Service records. */
@Service
public class AgentServiceRegistryReads {
    private final AgentServiceSessionPermissionRepository permissions;
    private final AgentServiceSessionRepository sessions;
    private final AgentServiceTurnRepository turns;
    private final AgentServiceSessionAccess access;
    private final AgentServiceSessionProvisioning provisioning;
    private final AgentServiceTurnAdmission admission;
    private final ObjectMapper mapper;

    public AgentServiceRegistryReads(
            AgentServiceSessionPermissionRepository permissions,
            AgentServiceSessionRepository sessions,
            AgentServiceTurnRepository turns,
            AgentServiceSessionAccess access,
            AgentServiceSessionProvisioning provisioning,
            AgentServiceTurnAdmission admission,
            ObjectMapper mapper) {
        this.permissions = permissions;
        this.sessions = sessions;
        this.turns = turns;
        this.access = access;
        this.provisioning = provisioning;
        this.admission = admission;
        this.mapper = mapper;
    }

    public Map<String, Object> sessions(
            String after, int limit, String status, String application, HttpHeaders headers) {
        return sessions(after, limit, status, application, headers, "desc");
    }

    public Map<String, Object> sessions(
            String after,
            int limit,
            String status,
            String application,
            HttpHeaders headers,
            String order) {
        validateLimit(limit);
        if (!Set.of("asc", "desc").contains(order))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Session order");
        if (!Set.of("active", "archived", "all").contains(status))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid Session status filter");
        application = optional(application, 256);
        String tenant = optional(headers.getFirst("X-Managed-Agents-Tenant"), 128);
        String namespace = optional(headers.getFirst("X-Managed-Agents-Namespace"), 128);
        var principal = access.principal(headers);
        long now = System.currentTimeMillis();
        if ("key".equals(principal.get("kind"))
                && !permissions.existsByPrincipalKindAndPrincipalIdAndValidUntilGreaterThan(
                        "key", principal.get("id"), now))
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Application key has no current Session read grant");
        if (!permissions
                .expiredPolicies(
                        principal.get("kind"),
                        principal.get("id"),
                        tenant,
                        namespace,
                        status,
                        application,
                        now,
                        PageRequest.of(0, 1))
                .isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Session read authorization projection expired");
        String scope =
                RuntimePayloadDigest.of(
                        mapper,
                        Map.of(
                                "kind",
                                "sessions:" + order,
                                "principal",
                                principal,
                                "tenant",
                                tenant == null ? "" : tenant,
                                "namespace",
                                namespace == null ? "" : namespace,
                                "status",
                                status,
                                "application",
                                application == null ? "" : application));
        var cursor = decode(scope, after);
        long time =
                cursor == null
                        ? (order.equals("asc") ? 0 : Long.MAX_VALUE)
                        : number(cursor.get("time"));
        String id = cursor == null ? "" : text(cursor.get("id"), 256);
        var rows =
                permissions.page(
                        principal.get("kind"),
                        principal.get("id"),
                        tenant,
                        namespace,
                        status,
                        application,
                        now,
                        order.equals("asc"),
                        time,
                        id,
                        PageRequest.of(0, limit + 1));
        boolean more = rows.size() > limit;
        var selected = rows.subList(0, Math.min(rows.size(), limit));
        // Indexed grants speed selection; the current authoritative projection still gates each
        // row.
        access.authorizePage(selected, headers, "read");
        var result = new LinkedHashMap<String, Object>();
        result.put("data", selected.stream().map(provisioning::view).toList());
        result.put("has_more", more);
        result.put(
                "next_cursor",
                more
                        ? encode(
                                scope,
                                Map.of(
                                        "time",
                                        selected.get(selected.size() - 1).createdAt,
                                        "id",
                                        selected.get(selected.size() - 1).id))
                        : null);
        return result;
    }

    public Map<String, Object> turns(String session, String after, int limit, HttpHeaders headers) {
        return turns(session, after, limit, "asc", null, headers);
    }

    public Map<String, Object> turns(
            String session,
            String after,
            int limit,
            String order,
            String status,
            HttpHeaders headers) {
        validateLimit(limit);
        if (!Set.of("asc", "desc").contains(order)
                || status != null
                        && !Set.of(
                                        "queued",
                                        "running",
                                        "requires_action",
                                        "cancel_requested",
                                        "completed",
                                        "cancelled",
                                        "failed",
                                        "interrupted")
                                .contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Turn filter");
        if (status != null && !"desc".equals(order))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Turn status requires descending order");
        access.authorize(session, headers, "read");
        var filters = new LinkedHashMap<String, Object>();
        filters.put("kind", "turns");
        filters.put("session", session);
        if (!"asc".equals(order)) filters.put("order", order);
        if (status != null) filters.put("status", status);
        String scope = RuntimePayloadDigest.of(mapper, filters);
        var cursor = decode(scope, after);
        boolean descending = "desc".equals(order);
        long sequence =
                cursor == null ? descending ? Long.MAX_VALUE : 0 : number(cursor.get("sequence"));
        if (cursor != null && sequence > sessions.findById(session).orElseThrow().nextTurnSequence)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Turn cursor is ahead of Session history");
        var rows =
                descending
                        ? turns.newest(session, status, sequence, PageRequest.of(0, limit + 1))
                        : turns
                                .findBySessionIdAndAdmissionSequenceGreaterThanOrderByAdmissionSequenceAsc(
                                        session, sequence, PageRequest.of(0, limit + 1));
        boolean more = rows.size() > limit;
        var selected = rows.subList(0, Math.min(rows.size(), limit));
        var result = new LinkedHashMap<String, Object>();
        result.put("data", selected.stream().map(admission::view).toList());
        result.put("has_more", more);
        result.put(
                "next_cursor",
                more
                        ? encode(
                                scope,
                                Map.of(
                                        "sequence",
                                        selected.get(selected.size() - 1).admissionSequence))
                        : null);
        return result;
    }

    private String encode(String scope, Map<String, Object> value) {
        try {
            var cursor = new LinkedHashMap<String, Object>(value);
            cursor.put("v", 1);
            cursor.put("scope", scope);
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mapper.writeValueAsBytes(new TreeMap<>(cursor)));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot encode registry cursor", error);
        }
    }

    private Map<?, ?> decode(String scope, String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (cursor.length() > 1024) throw new IllegalArgumentException();
            var value = mapper.readValue(Base64.getUrlDecoder().decode(cursor), Map.class);
            if (!Integer.valueOf(1).equals(value.get("v")) || !scope.equals(value.get("scope")))
                throw new IllegalArgumentException();
            return value;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid registry cursor");
        }
    }

    private static long number(Object value) {
        if (!(value instanceof Number n) || n.longValue() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid registry cursor");
        return n.longValue();
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String s) || s.isBlank() || s.length() > max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid registry cursor");
        return s;
    }

    private static String optional(String value, int max) {
        if (value == null) return null;
        if (value.isBlank() || value.length() > max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid registry filter");
        return value;
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..200");
    }
}
