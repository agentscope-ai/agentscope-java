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
package io.agentscope.extensions.a2ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import io.agentscope.extensions.a2ui.envelope.A2uiEnvelopeValidator;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Conformance against the vendored official v1.0 example gallery: every example must pass the
 * protocol schemas we validate with, and the component graph it builds must pass the graph rules the
 * schemas cannot express.
 *
 * <p>{@code updateComponents} merges into the surface's component map — the gallery ships partial
 * updates that only make sense once applied in order — so the graph check accumulates components per
 * surface instead of judging each message on its own.
 */
class A2uiConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final A2uiCatalog CATALOG =
            A2uiCatalog.load(A2uiCatalog.BASIC_CATALOG_RESOURCE, null);
    private static final String EXAMPLES_RESOURCE = "a2ui/v1_0/examples";

    /** Property names the protocol uses to point at another component by id. */
    private static final Set<String> REFERENCE_KEYS =
            Set.of("child", "children", "trigger", "content", "componentId");

    /** The gallery files carry a display name and description beside {@code messages}. */
    private static JsonNode messagesOf(Path file) throws Exception {
        JsonNode document = MAPPER.readTree(Files.readString(file));
        return document.path("messages");
    }

    private static List<Path> examples() throws Exception {
        URL url = A2uiConformanceTest.class.getClassLoader().getResource(EXAMPLES_RESOURCE);
        assertTrue(url != null, "vendored example gallery is missing from the test resources");
        try (Stream<Path> files = Files.list(Paths.get(url.toURI()))) {
            return files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void everyOfficialExamplePassesTheProtocolSchemas() throws Exception {
        List<String> failures = new ArrayList<>();
        List<Path> files = examples();
        for (Path file : files) {
            ObjectNode wrapper = MAPPER.createObjectNode();
            wrapper.set("messages", messagesOf(file));
            List<Error> errors = CATALOG.schemas().messages().validate(wrapper);
            if (!errors.isEmpty()) {
                failures.add(file.getFileName() + " (" + errors.size() + "): " + errors.get(0));
            }
        }
        assertEquals(43, files.size(), "the vendored gallery must stay complete");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void everyOfficialExampleBuildsARenderableGraph() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Path file : examples()) {
            Map<String, Map<String, Map<String, Object>>> surfaces = new LinkedHashMap<>();
            for (JsonNode message : messagesOf(file)) {
                JsonNode body = message.path("createSurface");
                if (body.isMissingNode()) {
                    body = message.path("updateComponents");
                }
                if (body.isMissingNode()) {
                    continue;
                }
                Map<String, Map<String, Object>> components =
                        surfaces.computeIfAbsent(
                                body.path("surfaceId").asText(), key -> new LinkedHashMap<>());
                for (JsonNode component : body.path("components")) {
                    Map<String, Object> value = MAPPER.convertValue(component, Map.class);
                    components.put(String.valueOf(value.get("id")), value);
                }
            }
            surfaces.forEach(
                    (surfaceId, components) -> {
                        List<String> errors =
                                A2uiEnvelopeValidator.validate(
                                        renderedTree(components), CATALOG, 500);
                        if (!errors.isEmpty()) {
                            failures.add(
                                    file.getFileName()
                                            + " ["
                                            + surfaceId
                                            + "]: "
                                            + String.join(" | ", errors));
                        }
                    });
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * The components the renderer actually walks. {@code updateComponents} merges by id, so a later
     * message can leave an earlier one unreferenced; those leftovers are legal and are not held to
     * the reachability rule. A reference to an id that never arrives still fails, because the parent
     * holding it stays in the walked tree.
     */
    private static List<Map<String, Object>> renderedTree(
            Map<String, Map<String, Object>> components) {
        if (!components.containsKey("root")) {
            return List.copyOf(components.values());
        }
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add("root");
        while (!pending.isEmpty()) {
            String id = pending.pop();
            if (!reached.add(id)) {
                continue;
            }
            Map<String, Object> component = components.get(id);
            if (component == null) {
                continue;
            }
            component.forEach(
                    (key, value) -> {
                        if (!"id".equals(key) && !"component".equals(key)) {
                            references(value, key, pending);
                        }
                    });
        }
        List<Map<String, Object>> walked = new ArrayList<>();
        components.forEach(
                (id, component) -> {
                    if (reached.contains(id)) {
                        walked.add(component);
                    }
                });
        return walked;
    }

    private static void references(Object node, String key, Deque<String> out) {
        if (node instanceof String value) {
            if (REFERENCE_KEYS.contains(key)) {
                out.add(value);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                references(item, key, out);
            }
        } else if (node instanceof Map<?, ?> map) {
            map.forEach((nestedKey, value) -> references(value, String.valueOf(nestedKey), out));
        }
    }
}
