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
import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>Cache entries are scoped to the class they describe instead of living in a static map keyed
 * by {@code Class}, so an entry cannot outlive that class or pin the classloader that defined it.
 * That matters because the structured-output and tool-parameter classes reaching this utility are
 * not always compile-time-fixed: extensions can load skills and tools at runtime, and
 * multi-tenant deployments may load classes per tenant.</p>
 *
 * <p>A parameterized type is scoped to the deepest application class it mentions rather than to
 * its raw class: {@code List<TenantDto>} is held on {@code TenantDto} and dies with it. Scoping
 * such an entry to {@code java.util.List} instead would park it, and the {@code TenantDto} its key
 * references, on a class the JVM never unloads, which is the same classloader pinning in a
 * different place. See {@link #scopeClassOf} for how the class is picked.</p>
 *
 * <p>The number of entries a single class's cache can hold is bounded by the distinct generic
 * signatures the code produces against it: every {@link Type} reaching this utility originates in
 * a {@link TypeReference} literal or a reflective method signature, and two structurally equal
 * signatures share one entry. Loading a class at runtime therefore adds a class with its own
 * cache rather than another entry on an existing one. A caller that synthesizes {@code Type}
 * instances at runtime can add entries beyond that bound, but those entries are released together
 * with the class that owns the cache, which is the first application class the type mentions when
 * it mentions more than one.</p>
 *
 * <p>One case is deliberately outside that bound: a signature built only from JDK classes, such as
 * {@code List<String>}, has no application class to be scoped to, so it stays on its raw class
 * ({@code java.util.List}), which the JVM never unloads. Those entries live as long as the JVM and
 * cannot pin an application class, because no application class appears in them.</p>
 *
 * @hidden
 */
public class JsonSchemaUtils {

    private static final boolean PROPERTY_REQUIRED_BY_DEFAULT = false;

    private static final SchemaGenerator schemaGenerator;

    /**
     * Guards the shared victools {@link SchemaGenerator}, which is not thread-safe: its
     * JacksonModule keeps an unsynchronized introspection cache, so concurrent schema
     * generation must be serialized. Only cache misses in {@link #CLASS_SCHEMA_SLOT} and
     * {@link #TYPE_SCHEMA_SLOT} take this lock; cache hits never do.
     */
    private static final Object SCHEMA_LOCK = new Object();

    /**
     * Schema cache slot of each class. A schema is a deterministic function of the class and the
     * static, never-changing generator config, so entries never need invalidation. Cached nodes
     * are never mutated after being stored: every call still converts a fresh, independently
     * mutable {@link Map} from the cached node, so callers that mutate the returned map (e.g.
     * {@code ToolSchemaGenerator}) cannot corrupt the cache or interfere with one another.
     *
     * <p>Creating the slot through {@link ClassValue} holds it on the class it describes, rather
     * than in a static map that strongly references the class as a key, so a slot cannot keep that
     * class — or the classloader which defined it — reachable once the rest of the application has
     * let go of them.
     */
    private static final ClassValue<AtomicReference<JsonNode>> CLASS_SCHEMA_SLOT =
            new ClassValue<>() {
                @Override
                protected AtomicReference<JsonNode> computeValue(Class<?> clazz) {
                    return new AtomicReference<>();
                }
            };

    /**
     * Schema cache slot of each class, keyed by generic {@link Type} to support parameterized
     * structured-output and tool-parameter types. The variants of one class (the type variables it
     * declares, and every signature mentioning it) share the map held on that class, so these slots
     * are scoped to a classloader in the same way as {@link #CLASS_SCHEMA_SLOT}. {@link
     * #scopeClassOf} picks that class: the deepest application class the type mentions, which keeps
     * {@code List<TenantDto>} on {@code TenantDto} instead of on {@code java.util.List}.
     *
     * <p>The map grows with the distinct generic signatures the code writes against that class,
     * not with anything a caller supplies at runtime, so it carries the same bound the previous
     * static {@code Map<Type, JsonNode>} relied on. See the class javadoc for the full argument,
     * including the signatures that stay on a JDK class because they mention no other.
     *
     * <p>Because unrelated classes never share a map, a miss takes {@link #SCHEMA_LOCK} without
     * grouping unrelated types behind the same lock.
     */
    private static final ClassValue<Map<Type, JsonNode>> TYPE_SCHEMA_SLOT =
            new ClassValue<>() {
                @Override
                protected Map<Type, JsonNode> computeValue(Class<?> scopeClass) {
                    return new ConcurrentHashMap<>();
                }
            };

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
            JsonNode schemaNode = cachedSchemaNode(clazz);
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
     * <p>A bare {@link Class} is delegated to {@link #generateSchemaFromClass(Class)}, so a class
     * reached through either entry point is generated once and stored in one slot.
     *
     * @param type The type to generate schema for
     * @return JSON Schema as a Map
     * @throws NullPointerException if the type is null
     */
    public static Map<String, Object> generateSchemaFromType(Type type) {
        Objects.requireNonNull(type, "type");
        if (type instanceof Class<?> clazz) {
            return generateSchemaFromClass(clazz);
        }
        try {
            JsonNode schemaNode = cachedSchemaNode(type);
            return JsonUtils.getJsonCodec()
                    .convertValue(schemaNode, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to generate JSON schema for " + type.getTypeName(), e);
        }
    }

    /**
     * Returns the cached schema node for a class, generating it under {@link #SCHEMA_LOCK} on a
     * miss. The slot is re-read inside the lock so that a class several threads reach at once is
     * still generated exactly once.
     */
    private static JsonNode cachedSchemaNode(Class<?> clazz) {
        AtomicReference<JsonNode> slot = CLASS_SCHEMA_SLOT.get(clazz);
        JsonNode cached = slot.get();
        if (cached != null) {
            return cached;
        }

        synchronized (SCHEMA_LOCK) {
            cached = slot.get();
            if (cached == null) {
                cached = schemaGenerator.generateSchema(clazz);
                slot.set(cached);
            }
        }
        return cached;
    }

    /**
     * Returns the cached schema node for a type, generating it under {@link #SCHEMA_LOCK} on a
     * miss.
     *
     * <p>The miss path re-reads the slot inside the lock rather than calling {@code
     * ConcurrentHashMap#computeIfAbsent}, which runs its mapping function while holding the map's
     * bin lock: nesting the schema lock inside it would block unrelated types hashing to the same
     * bin for the whole generation, and would break the contract that a mapping function must not
     * modify its own map.
     */
    private static JsonNode cachedSchemaNode(Type type) {
        Class<?> scope = scopeClassOf(type);
        if (scope == null) {
            // No class to hang a slot on, so this type cannot be cached. Every type a method
            // signature can declare is attributed, so this path is only reachable for a type a
            // caller synthesizes: a wildcard is only ever a type argument, never a parameter or
            // return type.
            synchronized (SCHEMA_LOCK) {
                return schemaGenerator.generateSchema(type);
            }
        }

        Map<Type, JsonNode> slot = TYPE_SCHEMA_SLOT.get(scope);
        JsonNode cached = slot.get(type);
        if (cached != null) {
            return cached;
        }

        synchronized (SCHEMA_LOCK) {
            cached = slot.get(type);
            if (cached == null) {
                cached = schemaGenerator.generateSchema(type);
                slot.put(type, cached);
            }
        }
        return cached;
    }

    /**
     * Returns the class whose cache a type's schema belongs to, or {@code null} when the type
     * cannot be attributed to one.
     *
     * <p>A parameterized type belongs to the deepest application class it mentions, a type argument
     * in preference to the raw type, so {@code List<TenantDto>} is held on {@code TenantDto}. Its
     * raw class is the fallback for a signature built exclusively from JDK classes, such as {@code
     * List<String>}: that entry is permanent, but it cannot pin an application class because none
     * appears in it. See the class javadoc for the full argument.
     *
     * <p>The types with no raw class belong to the class that declares them: a type variable to its
     * declaring class or to the class declaring its constructor or method, and a generic array to
     * whichever class its component type resolves to. Caching those under a class rather than in a
     * static map keyed by the type itself keeps the no-classloader-pinning property of {@link
     * #CLASS_SCHEMA_SLOT}, since a type variable holds its declaration and would otherwise pin it.
     *
     * @param type the type to attribute; must not be {@code null}
     * @return the class to cache the schema under, or {@code null} if the type has none
     */
    private static Class<?> scopeClassOf(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Class<?> applicationClass = applicationClassIn(parameterizedType);
            if (applicationClass != null) {
                return applicationClass;
            }
            return parameterizedType.getRawType() instanceof Class<?> rawClass ? rawClass : null;
        }
        if (type instanceof GenericArrayType arrayType) {
            return scopeClassOf(arrayType.getGenericComponentType());
        }
        if (type instanceof TypeVariable<?> typeVariable) {
            return declaringClassOf(typeVariable);
        }
        return null;
    }

    /**
     * Returns the deepest class loaded by an application classloader that the given type mentions,
     * or {@code null} when every class it mentions comes from the bootstrap loader. Only a class
     * that can itself be unloaded may scope a cache entry: a slot on {@code java.util.List} is never
     * released, so an entry there would keep the application classes named by its key reachable for
     * the lifetime of the JVM.
     */
    private static Class<?> applicationClassIn(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz.getClassLoader() == null ? null : clazz;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                Class<?> argumentClass = applicationClassIn(argument);
                if (argumentClass != null) {
                    return argumentClass;
                }
            }
            return applicationClassIn(parameterizedType.getRawType());
        }
        if (type instanceof GenericArrayType arrayType) {
            return applicationClassIn(arrayType.getGenericComponentType());
        }
        if (type instanceof WildcardType wildcardType) {
            for (Type bound : wildcardType.getUpperBounds()) {
                Class<?> boundClass = applicationClassIn(bound);
                if (boundClass != null) {
                    return boundClass;
                }
            }
            for (Type bound : wildcardType.getLowerBounds()) {
                Class<?> boundClass = applicationClassIn(bound);
                if (boundClass != null) {
                    return boundClass;
                }
            }
            return null;
        }
        if (type instanceof TypeVariable<?> typeVariable) {
            Class<?> declaringClass = declaringClassOf(typeVariable);
            return declaringClass == null ? null : applicationClassIn(declaringClass);
        }
        return null;
    }

    /**
     * Returns the class that declares the given type variable, or {@code null} when the declaration
     * is neither a class, a method nor a constructor. A constructor declares type variables just as
     * a method does, and both are {@link Executable} rather than {@code Method}.
     */
    private static Class<?> declaringClassOf(TypeVariable<?> typeVariable) {
        GenericDeclaration declaration = typeVariable.getGenericDeclaration();
        if (declaration instanceof Class<?> declaringClass) {
            return declaringClass;
        }
        if (declaration instanceof Executable executable) {
            return executable.getDeclaringClass();
        }
        return null;
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
