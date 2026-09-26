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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ControlPlaneBaseStore} against an in-process KV stub. */
class ControlPlaneBaseStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "test-token";
    private static final String AGENT = "demo-agent";
    private static final String NS = "default";

    private HttpServer server;
    private String baseUrl;
    private ControlPlaneBaseStore store;
    private final ConcurrentHashMap<String, StubItem> items = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/dp/kv/item", this::handleItem);
        server.createContext("/api/v1/dp/kv/search", this::handleSearch);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        store =
                (ControlPlaneBaseStore)
                        ControlPlaneStores.create(baseUrl, TOKEN, AGENT, NS).baseStore();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void putGetDeleteRoundTrip() {
        List<String> ns = List.of("workspace", "memory");
        store.put(ns, "MEMORY.md", Map.of("content", "hello"));

        StoreItem item = store.get(ns, "MEMORY.md");
        assertNotNull(item);
        assertEquals("MEMORY.md", item.key());
        assertEquals("hello", item.value().get("content"));
        assertTrue(item.version() >= 1);

        store.delete(ns, "MEMORY.md");
        assertNull(store.get(ns, "MEMORY.md"));
    }

    @Test
    void getMissingReturnsNull() {
        assertNull(store.get(List.of("a"), "missing"));
    }

    @Test
    void putIncrementsVersion() {
        List<String> ns = List.of("ver");
        store.put(ns, "k", Map.of("v", 1));
        assertEquals(1L, store.get(ns, "k").version());
        store.put(ns, "k", Map.of("v", 2));
        assertEquals(2L, store.get(ns, "k").version());
    }

    @Test
    void putIfVersionCas() {
        List<String> ns = List.of("cas");
        // putIfVersion(0) = create-if-absent: succeeds when missing, fails when present.
        assertTrue(store.putIfVersion(ns, "k", Map.of("v", 1), 0L));
        StoreItem first = store.get(ns, "k");
        assertNotNull(first);
        assertEquals(1L, first.version());

        assertFalse(store.putIfVersion(ns, "k", Map.of("v", 2), 0L));
        assertTrue(store.putIfVersion(ns, "k", Map.of("v", 3), first.version()));

        StoreItem updated = store.get(ns, "k");
        assertNotNull(updated);
        assertEquals(3, ((Number) updated.value().get("v")).intValue());
        assertTrue(updated.version() > first.version());
    }

    @Test
    void deleteIsIdempotent() {
        List<String> ns = List.of("del");
        store.put(ns, "k", Map.of("v", 1));
        store.delete(ns, "k");
        assertNull(store.get(ns, "k"));
        store.delete(ns, "k");
        assertNull(store.get(ns, "k"));
    }

    @Test
    void searchReturnsItems() {
        store.put(List.of("search"), "a", Map.of("n", 1));
        store.put(List.of("search"), "b", Map.of("n", 2));
        List<StoreItem> found = store.search(List.of("search"), 10, 0);
        assertEquals(2, found.size());
    }

    @Test
    void searchIncludesChildNamespaceItems() {
        // Matches InMemoryStore prefix semantics: search(["a"]) returns child-namespace items.
        store.put(List.of("a"), "parent", Map.of("where", "a"));
        store.put(List.of("a", "b"), "child", Map.of("where", "a/b"));

        List<StoreItem> found = store.search(List.of("a"), 100, 0);
        Set<String> keys = found.stream().map(StoreItem::key).collect(Collectors.toSet());
        assertEquals(Set.of("parent", "child"), keys);
        assertEquals(2, found.size());
    }

    @Test
    void httpErrorThrows() {
        // Force an unexpected path by using a store against a dead port after stop.
        server.stop(0);
        server = null;
        assertThrows(RuntimeException.class, () -> store.get(List.of("x"), "y"));
    }

    private void handleItem(HttpExchange ex) throws IOException {
        if (!TOKEN.equals(ex.getRequestHeaders().getFirst("X-Builder-Internal-Token"))) {
            write(ex, 401, Map.of("error", "unauthorized"));
            return;
        }
        String method = ex.getRequestMethod();
        if ("GET".equals(method)) {
            Map<String, List<String>> q = parseQuery(ex.getRequestURI().getRawQuery());
            requireTenant(q);
            String key = first(q, "key");
            List<String> ns = q.getOrDefault("ns", List.of());
            StubItem item = items.get(itemKey(ns, key));
            if (item == null) {
                write(ex, 404, Map.of("error", "not found"));
                return;
            }
            write(ex, 200, item.toMap());
            return;
        }
        if ("PUT".equals(method)) {
            JsonNode body = MAPPER.readTree(ex.getRequestBody());
            assertTenant(body);
            List<String> ns = new ArrayList<>();
            for (JsonNode n : body.path("namespaceSegments")) {
                ns.add(n.asText());
            }
            String key = body.path("key").asText();
            @SuppressWarnings("unchecked")
            Map<String, Object> value = MAPPER.convertValue(body.path("value"), Map.class);
            String ik = itemKey(ns, key);
            if (body.has("expectedVersion")) {
                long expected = body.path("expectedVersion").asLong();
                StubItem cur = items.get(ik);
                long currentVersion = cur == null ? 0L : cur.version;
                if (currentVersion != expected) {
                    write(ex, 409, Map.of("currentVersion", currentVersion));
                    return;
                }
                long next = currentVersion + 1;
                items.put(ik, new StubItem(key, value, next));
                write(ex, 200, Map.of("version", next));
                return;
            }
            StubItem cur = items.get(ik);
            long next = (cur == null ? 0L : cur.version) + 1;
            items.put(ik, new StubItem(key, value, next));
            write(ex, 200, Map.of("version", next));
            return;
        }
        if ("DELETE".equals(method)) {
            Map<String, List<String>> q = parseQuery(ex.getRequestURI().getRawQuery());
            requireTenant(q);
            String key = first(q, "key");
            List<String> ns = q.getOrDefault("ns", List.of());
            items.remove(itemKey(ns, key));
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        write(ex, 405, Map.of("error", "method not allowed"));
    }

    private void handleSearch(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            write(ex, 405, Map.of("error", "method not allowed"));
            return;
        }
        Map<String, List<String>> q = parseQuery(ex.getRequestURI().getRawQuery());
        requireTenant(q);
        List<String> ns = q.getOrDefault("ns", List.of());
        // Prefix match includes child namespaces (InMemoryStore / ControlPlane canonical
        // semantics).
        String prefix = itemKey(ns, "");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, StubItem> e : items.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.add(e.getValue().toMap());
            }
        }
        write(ex, 200, Map.of("items", out));
    }

    private static void requireTenant(Map<String, List<String>> q) {
        if (!AGENT.equals(first(q, "agentName")) || !NS.equals(first(q, "namespace"))) {
            throw new IllegalStateException("tenant mismatch");
        }
    }

    private static void assertTenant(JsonNode body) {
        if (!AGENT.equals(body.path("agentName").asText())
                || !NS.equals(body.path("namespace").asText())) {
            throw new IllegalStateException("tenant mismatch");
        }
    }

    private static String itemKey(List<String> ns, String key) {
        return String.join("\u001f", ns) + "\u001f" + key;
    }

    private static String first(Map<String, List<String>> q, String name) {
        List<String> v = q.get(name);
        return v == null || v.isEmpty() ? "" : v.get(0);
    }

    private static Map<String, List<String>> parseQuery(String raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            k = URLDecoder.decode(k, StandardCharsets.UTF_8);
            v = URLDecoder.decode(v, StandardCharsets.UTF_8);
            out.computeIfAbsent(k, ignored -> new ArrayList<>()).add(v);
        }
        return out;
    }

    private static void write(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private record StubItem(String key, Map<String, Object> value, long version) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("value", value);
            m.put("version", version);
            return m;
        }
    }
}
