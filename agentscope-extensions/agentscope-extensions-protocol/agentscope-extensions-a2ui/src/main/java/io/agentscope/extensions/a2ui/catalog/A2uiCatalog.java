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
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A component catalog in the A2UI v1.0 catalog document format: {@code components} maps a component
 * name to its JSON Schema, {@code functions} maps a function name to its schema, and {@code
 * instructions} carries the authoring guide. Loaded from a classpath resource, an {@code http(s)}
 * URL or a local file; an unreachable remote/file source falls back to the vendored basic catalog,
 * while a missing classpath resource fails fast.
 *
 * <p>A custom document is layered <em>over</em> the vendored basic catalog rather than replacing it:
 * the basic components and functions stay available (a custom entry of the same name wins), and each
 * component keeps the id of the catalog that owns it ({@link #catalogIdOf(String)}) so the envelope
 * can stamp the ones that differ from the surface default. The official schemas are never copied
 * into the custom document.
 *
 * <p>Doubles as the validation whitelist and the guide the render sub-agent is prompted with ({@link
 * #guideText()}, composed into its SYSTEM prompt unless {@code catalogInPrompt} is off, in which
 * case the {@code a2ui_catalog} tool serves it on demand).
 */
public final class A2uiCatalog {

    private static final Logger log = LoggerFactory.getLogger(A2uiCatalog.class);

    /** Vendored A2UI v1.0 basic catalog; also the fallback for unreachable sources. */
    public static final String BASIC_CATALOG_RESOURCE = "a2ui/v1_0/catalogs/basic/catalog.json";

    /** The basic catalog's own id — the {@code catalogId} its components are stamped with. */
    public static final String BASIC_CATALOG_ID =
            "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json";

    /** Canonical protocol types every v1.0 catalog may reference. */
    public static final String COMMON_TYPES_RESOURCE = "a2ui/v1_0/common_types.json";

    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DESCRIPTION_LIMIT = 200;
    private static final int SHAPE_LIMIT = 420;

    private final String catalogId;
    private final String protocolVersion;
    private final String instructions;
    private final ObjectNode document;
    private final Map<String, JsonNode> components;
    private final Set<String> functions;
    private final JsonNode commonTypes;
    private final Map<String, String> componentOwners;
    private final Map<String, ObjectNode> sourceDocuments;
    private volatile A2uiSchemas schemas;

    private A2uiCatalog(
            String catalogId,
            String protocolVersion,
            String instructions,
            ObjectNode document,
            Map<String, JsonNode> components,
            Set<String> functions,
            JsonNode commonTypes,
            Map<String, String> componentOwners,
            Map<String, ObjectNode> sourceDocuments) {
        this.catalogId = catalogId;
        this.protocolVersion = protocolVersion;
        this.instructions = instructions;
        this.document = document;
        this.components = Collections.unmodifiableMap(components);
        this.functions = Collections.unmodifiableSet(functions);
        this.commonTypes = commonTypes;
        this.componentOwners = Collections.unmodifiableMap(componentOwners);
        this.sourceDocuments = Collections.unmodifiableMap(sourceDocuments);
    }

    /**
     * Loads a catalog document. Throws {@link IllegalStateException} when a classpath resource is
     * missing or the document is unparseable — misconfiguration should fail fast at wiring time.
     *
     * @param resourcePath classpath path, {@code classpath:} path, {@code http(s)} URL or file path;
     *     {@code null}/blank selects {@link #BASIC_CATALOG_RESOURCE}
     * @param configuredCatalogId overrides the document's own {@code catalogId} when non-blank
     */
    public static A2uiCatalog load(String resourcePath, String configuredCatalogId) {
        String requested = resourcePath == null ? "" : resourcePath.trim();
        String spec = requested.isEmpty() ? BASIC_CATALOG_RESOURCE : requested;
        String json;
        if (isRemote(spec)) {
            json = readOrFallback(spec, () -> readRemote(spec));
        } else if (isFile(spec)) {
            json = readOrFallback(spec, () -> readFile(spec));
        } else {
            json = readClasspath(stripClasspathPrefix(spec));
        }
        return parse(json, configuredCatalogId);
    }

    private static String readOrFallback(String spec, Reader reader) {
        try {
            return reader.read();
        } catch (Exception e) {
            log.warn(
                    "A2UI catalog source {} is unreachable ({}); falling back to the vendored basic"
                            + " catalog",
                    spec,
                    e.getMessage());
            return readClasspath(BASIC_CATALOG_RESOURCE);
        }
    }

    private interface Reader {
        String read() throws Exception;
    }

    private static boolean isRemote(String spec) {
        String lower = spec.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isFile(String spec) {
        return spec.startsWith("file:") || spec.startsWith("/") || spec.matches("^[A-Za-z]:.*");
    }

    private static String stripClasspathPrefix(String spec) {
        return spec.startsWith("classpath:") ? spec.substring("classpath:".length()) : spec;
    }

    private static String readClasspath(String resourcePath) {
        try (InputStream in =
                A2uiCatalog.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException(
                        "A2UI catalog resource not found on classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load A2UI catalog from classpath:" + resourcePath, e);
        }
    }

    private static String readFile(String spec) {
        try {
            Path path = spec.startsWith("file:") ? Paths.get(URI.create(spec)) : Paths.get(spec);
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read A2UI catalog file " + spec, e);
        }
    }

    private static String readRemote(String spec) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(spec).toURL().openConnection();
            int timeout = (int) Math.min(REMOTE_TIMEOUT.toMillis(), Integer.MAX_VALUE);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) {
                throw new IllegalStateException("HTTP " + conn.getResponseCode());
            }
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch A2UI catalog " + spec, e);
        }
    }

    private static A2uiCatalog parse(String json, String configuredCatalogId) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) {
                throw new IllegalStateException("catalog document must be a JSON object");
            }
            ObjectNode document = (ObjectNode) root;
            String declaredId = text(document, "catalogId", text(document, "$id", null));
            String catalogId =
                    configuredCatalogId != null && !configuredCatalogId.isBlank()
                            ? configuredCatalogId
                            : declaredId;
            if (catalogId == null || catalogId.isBlank()) {
                throw new IllegalStateException(
                        "catalog document declares neither catalogId nor $id");
            }
            Map<String, JsonNode> components = entries(document, "components");
            if (components.isEmpty()) {
                throw new IllegalStateException("catalog document declares no components");
            }
            Map<String, JsonNode> functions = entries(document, "functions");
            Map<String, String> owners = new LinkedHashMap<>();
            components.keySet().forEach(name -> owners.put(name, catalogId));
            Map<String, ObjectNode> sources = new LinkedHashMap<>();
            sources.put(catalogId, document);
            String instructions = text(document, "instructions", "");

            // The document's own id decides whether it *is* the basic catalog; a configured
            // override only renames the surface default, it does not turn basic into custom.
            if (!BASIC_CATALOG_ID.equals(declaredId)) {
                ObjectNode basic =
                        (ObjectNode) MAPPER.readTree(readClasspath(BASIC_CATALOG_RESOURCE));
                sources.put(BASIC_CATALOG_ID, basic);
                entries(basic, "components")
                        .forEach(
                                (name, schema) -> {
                                    if (components.putIfAbsent(name, schema) == null) {
                                        owners.put(name, BASIC_CATALOG_ID);
                                    }
                                });
                entries(basic, "functions").forEach(functions::putIfAbsent);
                mergeInto(document, basic, components, functions);
                String basicInstructions = text(basic, "instructions", "");
                if (!basicInstructions.isBlank()) {
                    instructions =
                            instructions.isBlank()
                                    ? basicInstructions
                                    : instructions.strip() + "\n\n" + basicInstructions.strip();
                }
            }
            JsonNode commonTypes = MAPPER.readTree(readClasspath(COMMON_TYPES_RESOURCE));
            return new A2uiCatalog(
                    catalogId,
                    text(document, "protocolVersion", "1.0"),
                    instructions,
                    document,
                    components,
                    functions.keySet(),
                    commonTypes,
                    owners,
                    sources);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse A2UI catalog document", e);
        }
    }

    private static Map<String, JsonNode> entries(JsonNode document, String field) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        document.path(field).fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /**
     * Writes the merged component/function maps back into the custom document and rebuilds the two
     * union defs from them. The protocol's {@code Component} and {@code FunctionCall} resolve their
     * unions through {@code catalog.json}, so a mixed surface only validates as a whole when that
     * document lists every catalogued name.
     */
    private static void mergeInto(
            ObjectNode document,
            ObjectNode basic,
            Map<String, JsonNode> components,
            Map<String, JsonNode> functions) {
        ObjectNode mergedComponents = document.putObject("components");
        components.forEach(mergedComponents::set);
        ObjectNode mergedFunctions = document.putObject("functions");
        functions.forEach(mergedFunctions::set);
        JsonNode declaredDefs = document.path("$defs");
        ObjectNode defs =
                declaredDefs.isObject() ? (ObjectNode) declaredDefs : document.putObject("$defs");
        basic.path("$defs")
                .fields()
                .forEachRemaining(
                        e -> {
                            if (!defs.has(e.getKey())) {
                                defs.set(e.getKey(), e.getValue());
                            }
                        });
        defs.set("anyComponent", union("components", components.keySet()));
        defs.set("anyFunction", union("functions", functions.keySet()));
    }

    private static ObjectNode union(String section, Set<String> names) {
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode oneOf = node.putArray("oneOf");
        names.forEach(name -> oneOf.addObject().put("$ref", "#/" + section + "/" + name));
        return node;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    /**
     * A copy of this catalog offering only the named components, for an agent whose interfaces never
     * need the rest. The guide and the validation whitelist both derive from the component map, so
     * one pruning narrows what the model is told about and what a submission may use — the same
     * coupling the official catalog transformers keep.
     *
     * <p>The unmerged {@link #sourceDocuments()} stay whole: they resolve the {@code $ref}s of the
     * components that remain. Only the merged document's {@code components} and {@code anyComponent}
     * union are filtered, so a whole-envelope check rejects a pruned name too.
     *
     * @param allowed component names to keep; {@code null}, empty or all-blank keeps everything
     * @throws IllegalArgumentException when a name is not catalogued — a typo should fail at wiring
     *     time rather than silently shrink the catalog
     */
    public A2uiCatalog retainComponents(Collection<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return this;
        }
        Set<String> keep = new LinkedHashSet<>();
        for (String name : allowed) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = name.trim();
            if (!components.containsKey(trimmed)) {
                throw new IllegalArgumentException(
                        "Unknown A2UI component \""
                                + trimmed
                                + "\"; available: "
                                + componentNames());
            }
            keep.add(trimmed);
        }
        if (keep.isEmpty() || keep.size() == components.size()) {
            return this;
        }
        Map<String, JsonNode> keptComponents = new LinkedHashMap<>();
        Map<String, String> keptOwners = new LinkedHashMap<>();
        components.forEach(
                (name, schema) -> {
                    if (keep.contains(name)) {
                        keptComponents.put(name, schema);
                        keptOwners.put(name, componentOwners.get(name));
                    }
                });
        ObjectNode pruned = document.deepCopy();
        ObjectNode section = pruned.putObject("components");
        keptComponents.forEach(section::set);
        JsonNode declaredDefs = pruned.path("$defs");
        ObjectNode defs =
                declaredDefs.isObject() ? (ObjectNode) declaredDefs : pruned.putObject("$defs");
        defs.set("anyComponent", union("components", keptComponents.keySet()));
        return new A2uiCatalog(
                catalogId,
                protocolVersion,
                instructions,
                pruned,
                keptComponents,
                functions,
                commonTypes,
                keptOwners,
                sourceDocuments);
    }

    public String catalogId() {
        return catalogId;
    }

    public String protocolVersion() {
        return protocolVersion;
    }

    /** The authoring guide: this catalog's own, followed by the basic catalog's when merged in. */
    public String instructions() {
        return instructions;
    }

    public Set<String> componentNames() {
        return components.keySet();
    }

    public Set<String> functionNames() {
        return functions;
    }

    /** The component's JSON Schema as declared, or {@code null} when not catalogued. */
    public JsonNode componentSchema(String name) {
        return components.get(name);
    }

    /**
     * Property names the component declares, including those inherited through {@code allOf} and
     * canonical-type references. Used to tell a genuinely unknown property from the cascade noise a
     * closed schema emits when a declared property fails its own check.
     */
    public Set<String> declaredProperties(String name) {
        JsonNode schema = components.get(name);
        if (schema == null) {
            return Set.of();
        }
        Map<String, Resolved> props = new LinkedHashMap<>();
        collect(schema, document, props, new LinkedHashSet<>(), new LinkedHashSet<>());
        return props.keySet();
    }

    /** The whole catalog document, for registration as a validation schema resource. */
    public ObjectNode document() {
        return document;
    }

    /**
     * The id of the catalog that owns a component: this catalog's own id for the entries it
     * declares, {@link #BASIC_CATALOG_ID} for the ones merged in from the basic catalog. The
     * envelope stamps it onto every component whose owner differs from the surface default.
     *
     * @return the owning catalog id, or {@code null} when the name is not catalogued
     */
    public String catalogIdOf(String componentName) {
        return componentOwners.get(componentName);
    }

    /**
     * The unmerged source documents keyed by catalog id, so each component can be validated against
     * the defs of the catalog that actually declares it.
     */
    public Map<String, ObjectNode> sourceDocuments() {
        return sourceDocuments;
    }

    /**
     * The v1.0 JSON Schemas bound to this catalog. Built on first use so a catalog that is only read
     * for its guide never pays registry setup.
     */
    public A2uiSchemas schemas() {
        A2uiSchemas local = schemas;
        if (local == null) {
            synchronized (this) {
                local = schemas;
                if (local == null) {
                    local = new A2uiSchemas(this);
                    schemas = local;
                }
            }
        }
        return local;
    }

    /**
     * Markdown guide for the render sub-agent: the submission shape, one entry per component, then
     * the catalog's own {@code instructions}. Channel-agnostic — it states the contract without
     * naming a tool, so it reads the same in a SYSTEM prompt and as an {@code a2ui_catalog} result.
     */
    public String guideText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# A2UI Component Catalog (")
                .append(catalogId)
                .append(", protocol ")
                .append(protocolVersion)
                .append(")\n\n");
        sb.append(
                "Submit a flat `components` array. Each entry is `{id, component, ...props}` —"
                    + " properties sit directly beside `component`, never inside a `props` object."
                    + " Children are referenced by id (`children: [\"a\", \"b\"]` or `child:"
                    + " \"a\"`), never inlined as objects. Exactly one entry must have `id:"
                    + " \"root\"`; every other entry must be reachable from it. Ids must be"
                    + " unique.\n\n");
        sb.append(
                "A type written `string | binding | call` accepts a literal, a data binding"
                    + " `{\"@path\": \"/x\"}` or a function call `{\"@call\": \"fn\", \"args\":"
                    + " {...}}`; bind inputs to the data model rather than hard-coding their"
                    + " values. Inside a `shape:` line, `binding` and `call` stand for those two"
                    + " objects.\n\n");
        appendCommonProperties(sb);
        for (Map.Entry<String, JsonNode> entry : components.entrySet()) {
            appendComponent(sb, entry.getKey(), entry.getValue());
        }
        if (!functions.isEmpty()) {
            sb.append("## Functions\n\n").append(String.join(", ", functions)).append("\n\n");
            appendSystemFunctions(sb);
        }
        if (!instructions.isBlank()) {
            sb.append(
                    "Where the guide below shows whole protocol messages, submit only their"
                            + " `components` and `dataModel` parts.\n\n");
            sb.append(instructions.strip()).append('\n');
        }
        return sb.toString();
    }

    private void appendComponent(StringBuilder sb, String name, JsonNode schema) {
        sb.append("### ").append(name).append('\n');
        String description = text(schema, "description", null);
        if (description != null && !description.isBlank()) {
            sb.append(clip(description, DESCRIPTION_LIMIT)).append('\n');
        }
        Map<String, Resolved> props = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();
        collect(schema, document, props, required, new LinkedHashSet<>());
        props.remove("component");
        // Documented once in the common section, which also names the components that accept it.
        props.remove("checks");
        if (props.isEmpty()) {
            sb.append("- (no properties)\n");
        }
        for (Map.Entry<String, Resolved> prop : props.entrySet()) {
            appendProp(
                    sb,
                    prop.getKey(),
                    prop.getValue().node(),
                    prop.getValue().base(),
                    required.contains(prop.getKey()));
        }
        sb.append('\n');
    }

    /** One line of the guide's property table: name, type label, enum, description, nested shape. */
    private void appendProp(
            StringBuilder sb, String name, JsonNode declared, JsonNode base, boolean required) {
        Resolved resolved = deref(declared, base);
        JsonNode node = resolved.node();
        sb.append("- `").append(name).append("` (").append(label(node, resolved.base(), 0));
        if (required) {
            sb.append(", required");
        }
        sb.append(')');
        JsonNode enumValues = node.get("enum");
        if (enumValues != null && enumValues.isArray()) {
            List<String> values = new ArrayList<>();
            enumValues.forEach(value -> values.add(value.asText()));
            sb.append(" [").append(String.join(", ", values)).append(']');
        }
        String propDescription = text(declared, "description", null);
        if (propDescription == null) {
            propDescription = text(node, "description", null);
        }
        if (propDescription != null && !propDescription.isBlank()) {
            sb.append(" — ").append(clip(propDescription, DESCRIPTION_LIMIT));
        }
        String shape = shapeOf(node, resolved.base());
        if (shape != null) {
            sb.append("\n  shape: ").append(shape);
        }
        sb.append('\n');
    }

    /**
     * The protocol's component-level mixins, documented once instead of on every component that
     * inherits them. They live in {@code common_types.json} rather than in the catalog, so the
     * per-component walk never sees them — which left accessibility and the vendor-extension slot
     * invisible to the model even though renderers honour both.
     */
    private void appendCommonProperties(StringBuilder sb) {
        JsonNode common = commonTypes.path("$defs").path("ComponentCommon").path("properties");
        if (!common.isObject()) {
            return;
        }
        sb.append("## Common component properties\n\nEvery component accepts these:\n");
        for (String name : List.of("catalogId", "accessibility", "metadata")) {
            JsonNode declared = common.path(name);
            if (!declared.isMissingNode()) {
                appendProp(sb, name, declared, commonTypes, false);
            }
        }
        List<String> checkable = new ArrayList<>();
        components.forEach(
                (name, schema) -> {
                    if (inheritsCheckable(schema)) {
                        checkable.add(name);
                    }
                });
        JsonNode checks =
                commonTypes.path("$defs").path("Checkable").path("properties").path("checks");
        if (!checkable.isEmpty() && !checks.isMissingNode()) {
            sb.append("\nOnly these components also accept `checks`: ")
                    .append(String.join(", ", checkable))
                    .append(".\n");
            appendProp(sb, "checks", checks, commonTypes, false);
        }
        sb.append('\n');
    }

    /** Whether a component schema pulls in the protocol's {@code Checkable} mixin. */
    private static boolean inheritsCheckable(JsonNode schema) {
        for (JsonNode member : schema.path("allOf")) {
            JsonNode ref = member.path("$ref");
            if (ref.isTextual() && ref.asText().endsWith("#/$defs/Checkable")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The protocol's system functions, which no catalog lists because every catalog has them. Only
     * {@code @index} is defined in v1.0, and only inside a list template's iteration scope.
     */
    private void appendSystemFunctions(StringBuilder sb) {
        JsonNode index = commonTypes.path("$defs").path("IndexSystemFunction");
        if (!index.isObject()) {
            return;
        }
        sb.append("System function, available in every catalog: `@index` — ")
                .append(clip(text(index, "description", ""), DESCRIPTION_LIMIT))
                .append('\n');
        String shape = shapeOf(index.path("args"), commonTypes);
        if (shape != null) {
            sb.append("  shape: ").append(shape).append('\n');
        }
        sb.append("  usage: {\"@call\": \"@index\"} or {\"@call\": \"@index\", \"args\":")
                .append(" {\"offset\": 1}}; inside a List template it is the item's 0-based")
                .append(" position, so formatString(\"#${@index(offset: 1)}\") numbers rows from")
                .append(" 1.\n\n");
    }

    /** A schema node together with the document its bare {@code #/…} pointers resolve against. */
    private record Resolved(JsonNode node, JsonNode base) {}

    /** Merges {@code properties}/{@code required} across allOf and local/common-type references. */
    private void collect(
            JsonNode schema,
            JsonNode base,
            Map<String, Resolved> props,
            Set<String> required,
            Set<JsonNode> visited) {
        Resolved resolved = deref(schema, base);
        JsonNode node = resolved.node();
        if (node == null || !node.isObject() || !visited.add(node)) {
            return;
        }
        JsonNode allOf = node.get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (JsonNode member : allOf) {
                collect(member, resolved.base(), props, required, visited);
            }
        }
        JsonNode properties = node.get("properties");
        if (properties != null && properties.isObject()) {
            properties
                    .fields()
                    .forEachRemaining(
                            entry ->
                                    props.put(
                                            entry.getKey(),
                                            new Resolved(entry.getValue(), resolved.base())));
        }
        JsonNode requiredNode = node.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            requiredNode.forEach(value -> required.add(value.asText()));
        }
    }

    /**
     * The document a reference points into. A bare pointer stays in the document it was written in —
     * {@code common_types.json}'s own {@code #/$defs/…} refs must not be looked up in the catalog.
     */
    private JsonNode refBase(String ref, JsonNode from) {
        int hash = ref.indexOf('#');
        String documentPart = hash < 0 ? ref : ref.substring(0, hash);
        if (documentPart.isEmpty()) {
            return from;
        }
        return documentPart.endsWith("catalog.json") ? document : commonTypes;
    }

    private JsonNode resolveRef(String ref, JsonNode from) {
        int hash = ref.indexOf('#');
        String pointer = hash < 0 ? "" : ref.substring(hash + 1);
        JsonNode base = refBase(ref, from);
        return pointer.isEmpty() ? base : base.at(pointer);
    }

    /** Follows {@code $ref} chains so type/enum/description read off the final target. */
    private Resolved deref(JsonNode schema, JsonNode base) {
        JsonNode current = schema;
        JsonNode from = base;
        for (int i = 0; i < 6 && current != null && current.isObject(); i++) {
            JsonNode ref = current.get("$ref");
            if (ref == null || !ref.isTextual()) {
                break;
            }
            JsonNode target = resolveRef(ref.asText(), from);
            if (target == null || target.isMissingNode()) {
                break;
            }
            from = refBase(ref.asText(), from);
            current = target;
        }
        return new Resolved(current, from);
    }

    private static JsonNode unionOf(JsonNode node) {
        JsonNode oneOf = node.get("oneOf");
        if (oneOf != null && oneOf.isArray() && !oneOf.isEmpty()) {
            return oneOf;
        }
        JsonNode anyOf = node.get("anyOf");
        return anyOf != null && anyOf.isArray() && !anyOf.isEmpty() ? anyOf : null;
    }

    /**
     * Type label for the guide's property table. The protocol's dynamic types are unions of a
     * literal, a binding and a call, so they read as {@code number | binding | call} instead of
     * being mistaken for strings; a structural union names its branches ({@code {event} |
     * {functionCall}}).
     */
    private String label(JsonNode schema, JsonNode base, int depth) {
        Resolved resolved = deref(schema, base);
        JsonNode node = resolved.node();
        JsonNode from = resolved.base();
        if (node == null || !node.isObject() || depth > 4) {
            return "any";
        }
        Map<String, Resolved> props = new LinkedHashMap<>();
        collect(node, from, props, new LinkedHashSet<>(), new LinkedHashSet<>());
        if (props.containsKey("@path")) {
            return "binding";
        }
        if (props.containsKey("@call")) {
            return "call";
        }
        JsonNode union = unionOf(node);
        if (union != null) {
            Set<String> labels = new LinkedHashSet<>();
            for (JsonNode branch : union) {
                Resolved member = deref(branch, from);
                String branchLabel = label(member.node(), member.base(), depth + 1);
                if ("object".equals(branchLabel) && member.node().has("properties")) {
                    branchLabel = "{" + String.join(", ", fieldNames(member.node())) + "}";
                }
                labels.add(branchLabel);
            }
            return labels.isEmpty() ? "any" : String.join(" | ", labels);
        }
        JsonNode type = node.get("type");
        if (type != null && type.isTextual()) {
            return "array".equals(type.asText()) ? arrayLabel(node, from) : type.asText();
        }
        JsonNode allOf = node.get("allOf");
        if (allOf != null && allOf.isArray()) {
            // Members that only constrain (if/then, minimum, …) carry no type of their own.
            Set<String> labels = new LinkedHashSet<>();
            for (JsonNode member : allOf) {
                String memberLabel = label(member, from, depth + 1);
                if (!"any".equals(memberLabel)) {
                    labels.add(memberLabel);
                }
            }
            if (!labels.isEmpty()) {
                return String.join(" & ", labels);
            }
        }
        if (node.has("const")) {
            return "const " + node.get("const").asText();
        }
        return "any";
    }

    /** {@code string[]} when the items are a scalar type, plain {@code array} otherwise. */
    private String arrayLabel(JsonNode node, JsonNode base) {
        JsonNode items = node.get("items");
        if (items == null || !items.isObject()) {
            return "array";
        }
        JsonNode type = deref(items, base).node().get("type");
        if (type == null || !type.isTextual()) {
            return "array";
        }
        String name = type.asText();
        return "object".equals(name) || "array".equals(name) ? "array" : name + "[]";
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.get("properties").fieldNames().forEachRemaining(names::add);
        if (names.size() <= 4) {
            return names;
        }
        List<String> head = new ArrayList<>(names.subList(0, 3));
        head.add("…");
        return head;
    }

    /**
     * Structural sketch of a property, or {@code null} when its type label already says everything.
     * Nested shapes must reach the model somehow: a prop table alone leaves it guessing at object
     * layouts.
     */
    private String shapeOf(JsonNode schema, JsonNode base) {
        JsonNode sketch = compact(schema, base, 0);
        if (sketch == null || !sketch.isObject()) {
            return null;
        }
        if (!sketch.has("properties")
                && !sketch.has("items")
                && !sketch.has("oneOf")
                && !sketch.has("anyOf")) {
            return null;
        }
        return clip(sketch.toString(), SHAPE_LIMIT);
    }

    /**
     * Refs resolved, prose and constraints dropped, dynamic unions collapsed to their type label.
     * Raw schema JSON spends the clip budget on descriptions the property table already carries and
     * gets cut off before the structure ends.
     */
    private JsonNode compact(JsonNode schema, JsonNode base, int depth) {
        Resolved resolved = deref(schema, base);
        JsonNode node = resolved.node();
        JsonNode from = resolved.base();
        if (node == null || !node.isObject() || depth > 6) {
            return null;
        }
        Map<String, Resolved> props = new LinkedHashMap<>();
        collect(node, from, props, new LinkedHashSet<>(), new LinkedHashSet<>());
        if (props.containsKey("@path")) {
            return TextNode.valueOf("binding");
        }
        if (props.containsKey("@call")) {
            return TextNode.valueOf("call");
        }
        JsonNode union = unionOf(node);
        if (union != null) {
            String label = label(node, from, 0);
            if (!label.contains("{")) {
                return TextNode.valueOf(label);
            }
            ArrayNode branches = MAPPER.createArrayNode();
            for (JsonNode branch : union) {
                JsonNode sketch = compact(branch, from, depth + 1);
                if (sketch != null) {
                    branches.add(sketch);
                }
            }
            ObjectNode out = MAPPER.createObjectNode();
            out.set(node.has("oneOf") ? "oneOf" : "anyOf", branches);
            return out;
        }
        ObjectNode out = MAPPER.createObjectNode();
        copy(node, out, "type");
        JsonNode properties = node.get("properties");
        if (properties != null && properties.isObject() && !properties.isEmpty()) {
            ObjectNode sketch = out.putObject("properties");
            properties
                    .fields()
                    .forEachRemaining(
                            entry -> {
                                JsonNode value = compact(entry.getValue(), from, depth + 1);
                                if (value != null) {
                                    sketch.set(entry.getKey(), value);
                                }
                            });
        }
        copy(node, out, "required");
        JsonNode items = compact(node.get("items"), from, depth + 1);
        if (items != null) {
            out.set("items", items);
        }
        copy(node, out, "enum");
        copy(node, out, "const");
        return out.isEmpty() ? null : out;
    }

    private static void copy(JsonNode from, ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null) {
            to.set(field, value);
        }
    }

    private static String clip(String value, int limit) {
        String flat = value.replace('\n', ' ').strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
