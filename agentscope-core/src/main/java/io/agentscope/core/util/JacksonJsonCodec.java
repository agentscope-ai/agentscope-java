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

import java.lang.reflect.Type;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.cfg.MapperBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jackson-based implementation of {@link JsonCodec}.
 *
 * <p>This is the default implementation used by {@link JsonUtils}. It uses
 * Jackson's JsonMapper with the following configuration:
 * <ul>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES = false} - allows unknown fields in JSON</li>
 * </ul>
 *
 * <p>Users can access the underlying JsonMapper via {@link #getJsonMapper()}
 * for advanced operations not covered by the JsonCodec interface.
 *
 * @see JsonCodec
 * @see JsonUtils
 */
public class JacksonJsonCodec implements JsonCodec {

    private static final Logger log = LoggerFactory.getLogger(JacksonJsonCodec.class);

    private final JsonMapper jsonMapper;

    /**
     * Creates a new JacksonJsonCodec with default JsonMapper configuration.
     */
    public JacksonJsonCodec() {
        this.jsonMapper = createDefaultJsonMapper();
    }

    /**
     * Creates a new JacksonJsonCodec with a custom JsonMapper.
     *
     * @param jsonMapper the JsonMapper to use
     */
    public JacksonJsonCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * Creates the default JsonMapper with standard configuration.
     *
     * @return configured JsonMapper
     */
    private static JsonMapper createDefaultJsonMapper() {
        List<JacksonModule> modules = MapperBuilder.findModules();
        return JsonMapper.builder()
                .addModules(modules)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    /**
     * Get the underlying JsonMapper for advanced operations.
     *
     * @return the JsonMapper instance
     */
    public JsonMapper getJsonMapper() {
        return this.jsonMapper;
    }

    @Override
    public String toJson(Object obj) {
        try {
            return this.jsonMapper.writeValueAsString(obj);
        } catch (JacksonException e) {
            log.error("Failed to serialize object to JSON: {}", e.getMessage(), e);
            throw new JsonException("Failed to serialize object to JSON", e);
        }
    }

    @Override
    public String toPrettyJson(Object obj) {
        try {
            return this.jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JacksonException e) {
            log.error("Failed to serialize object to pretty JSON: {}", e.getMessage(), e);
            throw new JsonException("Failed to serialize object to pretty JSON", e);
        }
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return this.jsonMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new JsonException("Failed to deserialize JSON to " + type.getName(), e);
        }
    }

    @Override
    public <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return this.jsonMapper.readValue(json, typeRef);
        } catch (JacksonException e) {
            throw new JsonException("Failed to deserialize JSON", e);
        }
    }

    @Override
    public <T> T convertValue(Object from, Class<T> toType) {
        try {
            return this.jsonMapper.convertValue(from, toType);
        } catch (IllegalArgumentException | JacksonException e) {
            throw new JsonException("Failed to convert value to " + toType.getName(), e);
        }
    }

    @Override
    public <T> T convertValue(Object from, TypeReference<T> toTypeRef) {
        try {
            return this.jsonMapper.convertValue(from, toTypeRef);
        } catch (IllegalArgumentException | JacksonException e) {
            throw new JsonException("Failed to convert value", e);
        }
    }

    @Override
    public Object convertValue(Object from, Type toType) {
        try {
            JavaType javaType = this.jsonMapper.getTypeFactory().constructType(toType);
            return this.jsonMapper.convertValue(from, javaType);
        } catch (IllegalArgumentException | JacksonException e) {
            throw new JsonException("Failed to convert value to " + toType.getTypeName(), e);
        }
    }
}
