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
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Repairable CAS snapshot cache. Only reads the event suffix after the saved watermark. */
@Service
public final class AgentSessionViewStore {
    private final BaseStore store;
    private final SessionEventLog events;

    public AgentSessionViewStore(BaseStore store, SessionEventLog events) {
        this.store = store;
        this.events = events;
    }

    public Map<String, Object> read(String session) {
        var namespace = List.of("runtime", "agent-api", "views", session);
        for (int attempt = 0; attempt < 8; attempt++) {
            var cached = store.get(namespace, "v2");
            var view = new AgentSessionView(cached == null ? Map.of() : cached.value());
            long through = events.highWatermark(session);
            if (view.sequence() > through) view = new AgentSessionView(Map.of());
            long initial = view.sequence();
            while (view.sequence() < through) {
                var batch = events.page(session, view.sequence(), through, 256);
                if (batch.isEmpty()) break;
                batch.forEach(view::apply);
            }
            if (view.sequence() == initial
                    || store.putIfVersion(
                            namespace, "v2", view.state(), cached == null ? 0 : cached.version()))
                return view.resources(session);
        }
        throw new IllegalStateException("Concurrent session projection updates; retry the read");
    }

    private static final List<String> PAGES = List.of("runtime", "agent-api", "resource-pages");
    private int cleanupOffset;

    /** Pages share an immutable short-lived resource snapshot, including while execution continues. */
    public Map<String, Object> page(String session, String resource, String after, int limit) {
        if (!Set.of(
                                "items",
                                "tools",
                                "turns",
                                "runs",
                                "required_actions",
                                "action_commands",
                                "inputs",
                                "artifacts",
                                "subagents")
                        .contains(resource)
                || limit < 1
                || limit > 1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resource page");
        Map<String, Object> snapshot;
        String id;
        int offset = 0;
        if (after == null) {
            var view = read(session);
            id = UUID.randomUUID().toString();
            snapshot =
                    Map.of(
                            "session",
                            session,
                            "resource",
                            resource,
                            "as_of",
                            view.get("as_of"),
                            "data",
                            view.get(resource),
                            "expires_at",
                            System.currentTimeMillis() + 900000);
            if (!store.putIfVersion(PAGES, id, snapshot, 0))
                throw new IllegalStateException("Could not persist resource page");
        } else {
            try {
                if (after.length() > 4096) throw new IllegalArgumentException();
                var cursor =
                        JsonUtils.getJsonCodec()
                                .fromJson(
                                        new String(
                                                Base64.getUrlDecoder().decode(after),
                                                StandardCharsets.UTF_8),
                                        Map.class);
                if (!session.equals(cursor.get("session"))
                        || !resource.equals(cursor.get("resource")))
                    throw new IllegalArgumentException();
                id = (String) cursor.get("snapshot");
                offset = ((Number) cursor.get("offset")).intValue();
            } catch (RuntimeException invalid) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid resource cursor");
            }
            var cached = store.get(PAGES, id);
            if (cached == null
                    || ((Number) cached.value().get("expires_at")).longValue()
                            < System.currentTimeMillis())
                throw new ResponseStatusException(
                        HttpStatus.GONE, "Resource page expired; restart pagination");
            snapshot = cached.value();
            if (!session.equals(snapshot.get("session"))
                    || !resource.equals(snapshot.get("resource")))
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Resource cursor scope mismatch");
        }
        var records = (List<?>) snapshot.get("data");
        if (offset < 0 || offset > records.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resource offset");
        int next = Math.min(records.size(), offset + limit);
        var result = new LinkedHashMap<String, Object>();
        result.put("data", new ArrayList<>(records.subList(offset, next)));
        result.put("as_of", snapshot.get("as_of"));
        result.put("has_more", next < records.size());
        result.put("expires_at", snapshot.get("expires_at"));
        result.put(
                "next_cursor",
                next < records.size()
                        ? Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        JsonUtils.getJsonCodec()
                                                .toJson(
                                                        Map.of(
                                                                "session",
                                                                session,
                                                                "resource",
                                                                resource,
                                                                "snapshot",
                                                                id,
                                                                "offset",
                                                                next))
                                                .getBytes(StandardCharsets.UTF_8))
                        : null);
        return result;
    }

    @Scheduled(fixedDelay = 60000)
    public synchronized void expirePages() {
        var batch = store.search(PAGES, 100, cleanupOffset);
        for (var item : batch)
            if (((Number) item.value().get("expires_at")).longValue() < System.currentTimeMillis())
                store.delete(PAGES, item.key());
        cleanupOffset = batch.size() < 100 ? 0 : cleanupOffset + 100;
    }
}
