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

package io.agentscope.core.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import io.agentscope.core.tool.ToolSchemaModule;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for JSON Schema operations.
 *
 * <p>This class provides utility methods for:
 * <ul>
 *   <li>Generating JSON schemas from Java classes (for structured output)</li>
 *   <li>Converting between Maps and typed objects</li>
 *   <li>Mapping Java types to JSON Schema types</li>
 * </ul>
 *
 * <p>Supports AgentScope annotations:
 * <ul>
 *   <li>{@code @ToolParam(description = ...)} - add property description</li>
 *   <li>{@code @ToolParam(required = ...)} - mark property as required</li>
 * </ul>
 *
 * <p>Supports Jackson annotations:
 * <ul>
 *   <li>{@code @JsonProperty(required = ...)} - mark property as required</li>
 *   <li>{@code @JsonPropertyDescription(...)} - add property description</li>
 *   <li>{@code @JsonClassDescription(...)} - add class description</li>
 * </ul>
 *
 * <p>All public methods are thread-safe. Generated schemas are cached per {@link Class} /
 * {@link Type}; the shared victools {@code SchemaGenerator} is not designed for concurrent use,
 * so the internal lock is taken only on a cache miss (the first time a given class or type is
 * seen). A cache hit converts a fresh, independently mutable {@code Map} from the cached,
 * never-mutated {@link JsonNode}, so it needs no lock.</p>
 *
 * @hidden
 */
public class JsonSchemaUtils {

    private static final boolean PROPERTY_REQUIRED_BY_DEFAULT = false;

    private static final SchemaGenerator schemaGenerator;

    /**
     * Guards the shared victools {@link SchemaGenerator}, which is not thread-safe: its
     * JacksonModule keeps an unsynchronized introspection cache, so concurrent schema
     * generation must be serialized. Only cache misses in {@link #CLASS_SCHEMA_CACHE} and
     * {@link #TYPE_SCHEMA_CACHE} take this lock; cache hits never do.
     */
    private static final Object SCHEMA_LOCK = new Object();

    /**
     * Caches the schema {@link JsonNode} generated for each class. A schema is a deterministic
     * function of the class and the static, never-changing generator config, so entries never
     * need invalidation. Cached nodes are never mutated after being stored: every call still
     * converts a fresh, independently mutable {@link Map} from the cached node, so callers that
     * mutate the returned map (e.g. {@code ToolSchemaGenerator}) cannot corrupt the cache or
     * interfere with one another.
     *
     * <p>Unbounded by design: keys are the compile-time-fixed structured-output and
     * tool-parameter classes declared by application code, so the entry count is bounded by the
     * small, finite set of such classes the JVM loads for that purpose — not by request volume
     * or untrusted input.
     */
    private static final Map<Class<?>, JsonNode> CLASS_SCHEMA_CACHE = new ConcurrentHashMap<>();

    /**
     * Same caching strategy and bound rationale as {@link #CLASS_SCHEMA_CACHE}, keyed by generic
     * {@link Type} to support parameterized structured-output and tool-parameter types.
     */
    private static final Map<Type, JsonNode> TYPE_SCHEMA_CACHE = new ConcurrentHashMap<>();

    static {
        // JacksonModule to support @JsonProperty, @JsonPropertyDescription annotations
        JacksonModule jacksonModule =
                new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);

        ToolSchemaModule toolSchemaModule =
                PROPERTY_REQUIRED_BY_DEFAULT
                        ? new ToolSchemaModule()
                        : new ToolSchemaModule(
                                ToolSchemaModule.Option.PROPERTY_REQUIRED_FALSE_BY_DEFAULT);

        SchemaGeneratorConfigBuilder configBuilder =
                new SchemaGeneratorConfigBuilder(
                                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(jacksonModule)
                        .with(toolSchemaModule)
                        .with(Option.PLAIN_DEFINITION_KEYS)
                        .without(Option.SCHEMA_VERSION_INDICATOR);
        SchemaGeneratorConfig config = configBuilder.build();
        schemaGenerator = new SchemaGenerator(config);
    }

    /**
     * Generate JSON Schema from a Java class.
     * This method is suitable for structured output scenarios where complex nested
     * objects need to be converted to JSON Schema format.
     *
     * @param clazz The class to generate schema for
     * @return JSON Schema as a Map
     * @throws RuntimeException if schema generation fails due to reflection errors,
     *                          configuration issues, or other processing errors
     */
    public static Map<String, Object> generateSchemaFromClass(Class<?> clazz) {
        try {
            JsonNode schemaNode =
                    CLASS_SCHEMA_CACHE.computeIfAbsent(
                            clazz,
                            c -> {
                                synchronized (SCHEMA_LOCK) {
                                    return schemaGenerator.generateSchema(c);
                                }
                            });
            return JsonUtils.getJsonCodec()
                    .convertValue(schemaNode, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JSON schema for " + clazz.getName(), e);
        }
    }

    /**
     * Generate JSON Schema from a com.fasterxml.jackson.databind.JsonNode instance.
     * This method is suitable for structured output scenarios where complex nested
     * objects need to be converted to JSON Schema format.
     *
     * @param schema The com.fasterxml.jackson.databind.JsonNode instance to generate schema for
     * @return JSON Schema as a Map
     * @throws RuntimeException if schema generation fails due to reflection errors,
     *                          configuration issues, or other processing errors
     */
    public static Map<String, Object> generateSchemaFromJsonNode(JsonNode schema) {
        try {
            return JsonUtils.getJsonCodec()
                    .convertValue(schema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JSON schema for schema", e);
        }
    }

    /**
     * Generate JSON Schema from a Java Type (supports Generics).
     *
     * @param type The type to generate schema for
     * @return JSON Schema as a Map
     */
    public static Map<String, Object> generateSchemaFromType(Type type) {
        try {
            JsonNode schemaNode =
                    TYPE_SCHEMA_CACHE.computeIfAbsent(
                            type,
                            t -> {
                                synchronized (SCHEMA_LOCK) {
                                    return schemaGenerator.generateSchema(t);
                                }
                            });
            return JsonUtils.getJsonCodec()
                    .convertValue(schemaNode, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to generate JSON schema for " + type.getTypeName(), e);
        }
    }

    /**
     * Convert Map to typed object.
     *
     * @param data        The data map
     * @param targetClass The target class
     * @param <T>         The type
     * @return Converted object
     * @throws IllegalStateException if the input data is null
     * @throws RuntimeException      if the conversion fails due to type mismatch,
     *                               JSON parsing errors, or incompatible data
     *                               structure
     */
    public static <T> T convertToObject(Object data, Class<T> targetClass) {
        if (data == null) {
            throw new IllegalStateException("No structured data available in response");
        }

        try {
            return JsonUtils.getJsonCodec().convertValue(data, targetClass);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert metadata to " + targetClass.getName(), e);
        }
    }
}
