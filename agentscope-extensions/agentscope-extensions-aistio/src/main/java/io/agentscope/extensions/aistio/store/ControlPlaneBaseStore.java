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
package io.agentscope.extensions.aistio.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Control-plane hosted {@link BaseStore} over {@code /api/v1/dp/kv/*}.
 *
 * <p>HTTP failures and unexpected status codes throw {@link RuntimeException} — never silently
 * return empty results (except {@link #get} which maps 404 to {@code null}).
 */
public final class ControlPlaneBaseStore implements BaseStore {

    private static final ObjectMapper MAPPER = ControlPlaneHttpClient.mapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ControlPlaneHttpClient http;
    private final String agentName;
    private final String namespace;

    ControlPlaneBaseStore(ControlPlaneHttpClient http, String agentName, String namespace) {
        this.http = Objects.requireNonNull(http, "http");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    @Override
    public StoreItem get(List<String> ns, String key) {
        try {
            String path = "/api/v1/dp/kv/item?" + tenantQuery() + "&key=" + enc(key) + nsQuery(ns);
            ControlPlaneHttpClient.Response resp = http.send("GET", path, null);
            if (resp.status() == 404) {
                return null;
            }
            requireOk(resp, "get");
            return parseItem(resp.body());
        } catch (IOException | InterruptedException e) {
            rethrow(e);
            return null; // unreachable
        }
    }

    @Override
    public void put(List<String> ns, String key, Map<String, Object> value) {
        putInternal(ns, key, value, null);
    }

    @Override
    public boolean putIfVersion(
            List<String> ns, String key, Map<String, Object> value, long expectedVersion) {
        try {
            ControlPlaneHttpClient.Response resp = putRequest(ns, key, value, expectedVersion);
            if (resp.status() == 409) {
                return false;
            }
            requireOk(resp, "putIfVersion");
            return true;
        } catch (IOException | InterruptedException e) {
            rethrow(e);
            return false; // unreachable
        }
    }

    @Override
    public List<StoreItem> search(List<String> ns, int limit, int offset) {
        try {
            String path =
                    "/api/v1/dp/kv/search?"
                            + tenantQuery()
                            + nsQuery(ns)
                            + "&limit="
                            + limit
                            + "&offset="
                            + offset;
            ControlPlaneHttpClient.Response resp = http.send("GET", path, null);
            requireOk(resp, "search");
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode items = root.path("items");
            List<StoreItem> out = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode n : items) {
                    out.add(parseItemNode(n));
                }
            }
            return out;
        } catch (IOException | InterruptedException e) {
            rethrow(e);
            return List.of(); // unreachable
        }
    }

    @Override
    public void delete(List<String> ns, String key) {
        try {
            String path = "/api/v1/dp/kv/item?" + tenantQuery() + "&key=" + enc(key) + nsQuery(ns);
            ControlPlaneHttpClient.Response resp = http.send("DELETE", path, null);
            if (resp.status() == 204 || (resp.status() >= 200 && resp.status() < 300)) {
                return;
            }
            throw new RuntimeException(
                    "control-plane kv delete failed: HTTP " + resp.status() + " " + resp.body());
        } catch (IOException | InterruptedException e) {
            rethrow(e);
        }
    }

    private void putInternal(
            List<String> ns, String key, Map<String, Object> value, Long expectedVersion) {
        try {
            ControlPlaneHttpClient.Response resp = putRequest(ns, key, value, expectedVersion);
            requireOk(resp, "put");
        } catch (IOException | InterruptedException e) {
            rethrow(e);
        }
    }

    private ControlPlaneHttpClient.Response putRequest(
            List<String> ns, String key, Map<String, Object> value, Long expectedVersion)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentName", agentName);
        body.put("namespace", namespace);
        body.put("namespaceSegments", ns == null ? List.of() : ns);
        body.put("key", key);
        body.put("value", value == null ? Map.of() : value);
        if (expectedVersion != null) {
            body.put("expectedVersion", expectedVersion);
        }
        return http.send("PUT", "/api/v1/dp/kv/item", body);
    }

    private String tenantQuery() {
        return "agentName=" + enc(agentName) + "&namespace=" + enc(namespace);
    }

    private static String nsQuery(List<String> ns) {
        if (ns == null || ns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String segment : ns) {
            sb.append("&ns=").append(enc(segment));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static StoreItem parseItem(String body) throws IOException {
        return parseItemNode(MAPPER.readTree(body));
    }

    private static StoreItem parseItemNode(JsonNode n) throws IOException {
        String key = n.path("key").asText();
        long version = n.path("version").asLong(0L);
        Map<String, Object> value;
        JsonNode valueNode = n.get("value");
        if (valueNode == null || valueNode.isNull()) {
            value = Map.of();
        } else {
            value = MAPPER.convertValue(valueNode, MAP_TYPE);
        }
        return new StoreItem(key, value, version);
    }

    private static void requireOk(ControlPlaneHttpClient.Response resp, String op) {
        if (resp.status() >= 200 && resp.status() < 300) {
            return;
        }
        throw new RuntimeException(
                "control-plane kv " + op + " failed: HTTP " + resp.status() + " " + resp.body());
    }

    private static void rethrow(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        throw new RuntimeException("control-plane kv request failed: " + e.getMessage(), e);
    }
}
