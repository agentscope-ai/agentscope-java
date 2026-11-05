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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Component catalog loaded from a classpath JSON resource, an {@code http(s)} URL or a local file
 * (format: {@code a2ui/basic-catalog.json} in the A2UI spec §6.4). A remote/file source that cannot
 * be reached falls back to the built-in catalog; a missing classpath resource fails fast. Doubles as
 * the validation whitelist and the guide text the render sub-agent is prompted with ({@link
 * #guideText()}, composed into its SYSTEM prompt unless {@code catalogInPrompt} is off, in which
 * case the {@code a2ui_catalog} tool serves it on demand).
 */
public final class A2uiCatalog {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(A2uiCatalog.class);

    /** Fallback used when a remote/file catalog source cannot be reached. */
    private static final String BUILT_IN_RESOURCE = "a2ui/basic-catalog.json";

    private static final java.time.Duration REMOTE_TIMEOUT = java.time.Duration.ofSeconds(5);

    /** One catalogued component: description, JSON-Schema-ish prop map, required prop names. */
    public record ComponentDef(
            String name, String description, Map<String, Object> props, List<String> required) {}

    private final String catalogId;
    private final String version;
    private final Map<String, ComponentDef> components;

    private A2uiCatalog(String catalogId, String version, Map<String, ComponentDef> components) {
        this.catalogId = catalogId;
        this.version = version;
        this.components = Collections.unmodifiableMap(components);
    }

    /**
     * Loads the catalog from a classpath resource. Throws {@link IllegalStateException} when the
     * resource is missing or unparseable — misconfiguration should fail fast at agent build time.
     */
    @SuppressWarnings("unchecked")
    public static A2uiCatalog load(String resourcePath, String configuredCatalogId) {
        String spec = resourcePath == null ? "" : resourcePath.trim();
        if (isRemote(spec)) {
            try {
                return parse(readRemote(spec), configuredCatalogId);
            } catch (Exception e) {
                log.warn(
                        "A2UI catalog source {} is unreachable ({}); falling back to the built-in"
                                + " basic catalog",
                        spec,
                        e.getMessage());
                return parse(readClasspath(BUILT_IN_RESOURCE), configuredCatalogId);
            }
        }
        if (isFile(spec)) {
            try {
                return parse(readFile(spec), configuredCatalogId);
            } catch (Exception e) {
                log.warn(
                        "A2UI catalog file {} could not be read ({}); falling back to the built-in"
                                + " basic catalog",
                        spec,
                        e.getMessage());
                return parse(readClasspath(BUILT_IN_RESOURCE), configuredCatalogId);
            }
        }
        return parse(readClasspath(stripClasspathPrefix(spec)), configuredCatalogId);
    }

    private static boolean isRemote(String spec) {
        String lower = spec.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isFile(String spec) {
        return spec.startsWith("file:") || spec.startsWith("/") || spec.matches("^[A-Za-z]:.*");
    }

    private static String stripClasspathPrefix(String spec) {
        return spec.startsWith("classpath:") ? spec.substring("classpath:".length()) : spec;
    }

    private static byte[] readClasspath(String resourcePath) {
        try (InputStream in =
                A2uiCatalog.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException(
                        "A2UI catalog resource not found on classpath: " + resourcePath);
            }
            return in.readAllBytes();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load A2UI catalog from classpath:" + resourcePath, e);
        }
    }

    private static byte[] readFile(String spec) {
        try {
            java.nio.file.Path path =
                    spec.startsWith("file:")
                            ? java.nio.file.Paths.get(java.net.URI.create(spec))
                            : java.nio.file.Paths.get(spec);
            return java.nio.file.Files.readAllBytes(path);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read A2UI catalog file " + spec, e);
        }
    }

    private static byte[] readRemote(String spec) {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URI(spec).toURL().openConnection();
            conn.setConnectTimeout(
                    REMOTE_TIMEOUT.toMillis() < Integer.MAX_VALUE
                            ? (int) REMOTE_TIMEOUT.toMillis()
                            : Integer.MAX_VALUE);
            conn.setReadTimeout(conn.getConnectTimeout());
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) {
                throw new IllegalStateException("HTTP " + conn.getResponseCode());
            }
            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch A2UI catalog " + spec, e);
        }
    }

    private static A2uiCatalog parse(byte[] json, String configuredCatalogId) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> root =
                    mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            String catalogId =
                    configuredCatalogId != null && !configuredCatalogId.isBlank()
                            ? configuredCatalogId
                            : (String) root.get("catalogId");
            String version = (String) root.get("version");
            Map<String, ComponentDef> defs = new LinkedHashMap<>();
            Map<String, Object> raw =
                    (Map<String, Object>) root.getOrDefault("components", Collections.emptyMap());
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                Map<String, Object> def = (Map<String, Object>) entry.getValue();
                defs.put(
                        entry.getKey(),
                        new ComponentDef(
                                entry.getKey(),
                                (String) def.get("description"),
                                (Map<String, Object>) def.getOrDefault("props", Map.of()),
                                (List<String>) def.getOrDefault("required", List.of())));
            }
            return new A2uiCatalog(catalogId, version, defs);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse A2UI catalog JSON", e);
        }
    }

    public String catalogId() {
        return catalogId;
    }

    public String version() {
        return version;
    }

    public Set<String> componentNames() {
        return components.keySet();
    }

    public ComponentDef defOf(String name) {
        return components.get(name);
    }

    /**
     * Markdown rendering of the catalog. Channel-agnostic: composed into the render sub-agent's
     * SYSTEM prompt by default and returned verbatim by {@code a2ui_catalog} when {@code
     * catalogInPrompt} is off, so it states the submission contract without naming a tool.
     */
    public String guideText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# A2UI Component Catalog (")
                .append(catalogId)
                .append(", version ")
                .append(version)
                .append(")\n\n");
        sb.append("The UI is a flat `components` array of `{id, component, props}` objects.\n");
        sb.append("`id` must be unique per submission, `props` must follow the entry below, and\n");
        sb.append("unknown components are rejected.\n\n");
        for (ComponentDef def : components.values()) {
            sb.append("### ").append(def.name()).append('\n');
            if (def.description() != null && !def.description().isBlank()) {
                sb.append(def.description()).append('\n');
            }
            if (def.props().isEmpty()) {
                sb.append("- (no props)\n");
            }
            for (Map.Entry<String, Object> prop : def.props().entrySet()) {
                Map<String, Object> schema = asMap(prop.getValue());
                sb.append("- `")
                        .append(prop.getKey())
                        .append("` (")
                        .append(schema.getOrDefault("type", "any"))
                        .append(def.required().contains(prop.getKey()) ? ", required" : "")
                        .append(")");
                String description = (String) schema.get("description");
                if (description != null && !description.isBlank()) {
                    sb.append(" — ").append(description);
                }
                Object enumValues = schema.get("enum");
                if (enumValues instanceof List<?> values) {
                    sb.append(" [").append(joinValues(values)).append("]");
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String joinValues(List<?> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
