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
package io.agentscope.extensions.a2ui.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The A2UI v1.0 JSON Schemas bound to one catalog, resolved entirely from vendored classpath
 * documents — no network fetch happens at validation time.
 *
 * <p>Two details of the official documents drive the setup. References are written relative to a
 * document's own {@code $id} ({@code common_types.json#/$defs/…}, {@code catalog.json#/$defs/…}),
 * and the published sibling paths do not all exist, so each document is registered under every URI
 * its references can resolve to; {@code catalog.json} is the placeholder for "the catalog in play"
 * and holds the merged view, which is how one message schema validates a surface that mixes the
 * custom catalog with the basic one. Each component is then checked against the source document that
 * declares it, so a custom catalog cannot redefine a def the basic components rely on. And the
 * extension-key pattern uses UAX&nbsp;#31 identifier properties, which the JDK regex engine does not
 * implement, so those two property names are mapped to their JDK equivalents on load. The vendored
 * documents are the official v1.0 ones, with one deliberate divergence: {@code TextField} requires
 * {@code value}, which the specification asks for but the published renderer bundles do not yet
 * enforce — an input with no binding to write into is not a usable input.
 */
public final class A2uiSchemas {

    /** Canonical base of the vendored v1.0 protocol documents. */
    public static final String PROTOCOL_BASE = "https://a2ui.org/specification/v1_0/";

    private static final String RESOURCE_ROOT = "a2ui/v1_0/";
    private static final String COMMON_TYPES_URI = PROTOCOL_BASE + "common_types.json";
    private static final String CATALOG_PLACEHOLDER_URI = PROTOCOL_BASE + "catalog.json";
    private static final String LOCAL_CATALOG_URI = PROTOCOL_BASE + "catalogs/local/catalog.json";
    private static final String WRAPPER_URI =
            PROTOCOL_BASE + "json/agent_to_renderer_list_wrapper.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final A2uiCatalog catalog;
    private final SchemaRegistry registry;
    private final Map<String, Schema> componentSchemas = new ConcurrentHashMap<>();

    A2uiSchemas(A2uiCatalog catalog) {
        this.catalog = catalog;
        this.registry = buildRegistry(catalog);
    }

    private static SchemaRegistry buildRegistry(A2uiCatalog catalog) {
        String commonTypes = jdkIdentifierProperties(resource("common_types.json"));

        Map<String, String> documents = new HashMap<>();
        documents.put(COMMON_TYPES_URI, commonTypes);
        documents.put(CATALOG_PLACEHOLDER_URI, absoluteRefs(catalog.document()).toString());
        catalog.sourceDocuments()
                .forEach(
                        (catalogId, source) -> {
                            String uri = catalogUri(catalogId);
                            String document = absoluteRefs(source).toString();
                            documents.put(uri, document);
                            String declaredId = source.path("$id").asText(null);
                            if (declaredId != null && declaredId.startsWith("http")) {
                                documents.put(declaredId, document);
                            }
                            // A catalog's own relative refs resolve beside its id, not beside the
                            // protocol root.
                            documents.put(
                                    uri.substring(0, uri.lastIndexOf('/') + 1)
                                            + "common_types.json",
                                    commonTypes);
                        });
        documents.put(PROTOCOL_BASE + "agent_to_renderer.json", resource("agent_to_renderer.json"));
        documents.put(
                PROTOCOL_BASE + "json/agent_to_renderer_list.json",
                resource("agent_to_renderer_list.json"));
        documents.put(WRAPPER_URI, resource("agent_to_renderer_list_wrapper.json"));

        return SchemaRegistry.builder()
                .defaultDialectId(SpecificationVersion.DRAFT_2020_12.getDialectId())
                .schemaRegistryConfig(
                        // Rejections go back to the model as text; keep them in one language
                        // regardless of the host's default locale.
                        SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build())
                .schemas(documents)
                .build();
    }

    /**
     * Registration key for a source document. Catalog ids are often URNs rather than URLs, and after
     * {@link #absoluteRefs} nothing resolves relatively any more, so a non-URL id only needs a stable
     * key of its own. At most one catalog can lack a URL id — the basic one always has its own.
     */
    private static String catalogUri(String catalogId) {
        return catalogId.startsWith("http") ? catalogId : LOCAL_CATALOG_URI;
    }

    /**
     * The schema one submitted component is checked against: the protocol's common properties, the
     * catalog entry for its name, and a closed property set.
     *
     * <p>Validating against the catalog's {@code anyComponent} union instead would report every
     * branch's failure for a single mistake — dozens of lines of "must be const Text/Image/…" that
     * bury the one actionable error. Dispatching on the declared name keeps the rejection short
     * enough for the render sub-agent to act on.
     *
     * @return the schema, or {@code null} when the name is not catalogued
     */
    public Schema component(String name) {
        if (catalog.componentSchema(name) == null) {
            return null;
        }
        return componentSchemas.computeIfAbsent(name, this::buildComponentSchema);
    }

    private Schema buildComponentSchema(String name) {
        ObjectNode schema = MAPPER.createObjectNode();
        ArrayNode allOf = schema.putArray("allOf");
        allOf.addObject().put("$ref", COMMON_TYPES_URI + "#/$defs/ComponentCommon");
        allOf.addObject()
                .put("$ref", catalogUri(catalog.catalogIdOf(name)) + "#/components/" + name);
        ObjectNode notSurface = allOf.addObject();
        notSurface.put("type", "object");
        notSurface
                .putObject("properties")
                .putObject("component")
                .putObject("not")
                .put("const", "Surface");
        schema.put("unevaluatedProperties", false);
        return registry.getSchema(schema.toString());
    }

    /** The official transport wrapper schema, for checking a whole emitted envelope. */
    public Schema messages() {
        return registry.getSchema(SchemaLocation.of(WRAPPER_URI));
    }

    /**
     * Rewrites a catalog's relative document references to absolute ones, so resolution does not
     * depend on the catalog declaring a dereferenceable {@code $id}. {@code catalog.json} means the
     * catalog document itself and becomes a local reference.
     */
    private static ObjectNode absoluteRefs(JsonNode document) {
        ObjectNode copy = document.deepCopy();
        rewriteRefs(copy);
        return copy;
    }

    private static void rewriteRefs(JsonNode node) {
        if (node.isArray()) {
            node.forEach(A2uiSchemas::rewriteRefs);
            return;
        }
        if (!node.isObject()) {
            return;
        }
        ObjectNode object = (ObjectNode) node;
        JsonNode ref = object.get("$ref");
        if (ref != null && ref.isTextual()) {
            String value = ref.asText();
            int hash = value.indexOf('#');
            String documentPart = hash < 0 ? value : value.substring(0, hash);
            String pointer = hash < 0 ? "" : value.substring(hash);
            if (documentPart.equals("catalog.json")) {
                object.put("$ref", pointer.isEmpty() ? "#" : pointer);
            } else if (documentPart.endsWith("common_types.json")) {
                object.put("$ref", COMMON_TYPES_URI + pointer);
            }
        }
        Iterator<JsonNode> children = object.elements();
        while (children.hasNext()) {
            rewriteRefs(children.next());
        }
    }

    /**
     * The JDK regex engine has no UAX #31 {@code XID_Start}/{@code XID_Continue} properties; its
     * Unicode identifier properties are the closest equivalent and accept every key we emit.
     */
    private static String jdkIdentifierProperties(String schema) {
        return schema.replace("\\p{XID_Start}", "\\p{javaUnicodeIdentifierStart}")
                .replace("\\p{XID_Continue}", "\\p{javaUnicodeIdentifierPart}");
    }

    private static String resource(String name) {
        String path = RESOURCE_ROOT + name;
        try (InputStream in = A2uiSchemas.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                        "A2UI protocol schema missing on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read A2UI protocol schema " + path, e);
        }
    }
}
