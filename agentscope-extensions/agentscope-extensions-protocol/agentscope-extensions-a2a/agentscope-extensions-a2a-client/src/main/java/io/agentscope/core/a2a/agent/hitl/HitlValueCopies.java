/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.agentscope.core.a2a.agent.hitl;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.util.JsonUtils;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Defensive-copy helpers for durable public HITL values. */
final class HitlValueCopies {

    private HitlValueCopies() {}

    static Map<String, Object> immutableJsonMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        value.forEach(
                (key, item) ->
                        copy.put(
                                Objects.requireNonNull(key, "JSON object key must not be null"),
                                immutableJsonValue(item)));
        return Collections.unmodifiableMap(copy);
    }

    static List<ContentBlock> copyContentBlocks(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<ContentBlock> copy = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            ContentBlock required =
                    Objects.requireNonNull(block, "outputBlocks must not contain null");
            copy.add(
                    JsonUtils.getJsonCodec()
                            .fromJson(
                                    JsonUtils.getJsonCodec().toJson(required), ContentBlock.class));
        }
        return List.copyOf(copy);
    }

    private static Object immutableJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return immutableJsonNumber(number);
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach(
                    (key, item) -> {
                        if (!(key instanceof String stringKey)) {
                            throw new IllegalArgumentException(
                                    "JSON object keys must be strings: " + key);
                        }
                        copy.put(stringKey, immutableJsonValue(item));
                    });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(item -> copy.add(immutableJsonValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) {
                copy.add(immutableJsonValue(Array.get(value, index)));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException(
                "HITL values must be JSON-shaped; unsupported type: " + value.getClass().getName());
    }

    private static Number immutableJsonNumber(Number number) {
        if (number instanceof Float value) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("HITL JSON numbers must be finite");
            }
            return value;
        }
        if (number instanceof Double value) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("HITL JSON numbers must be finite");
            }
            return value;
        }
        if (number instanceof Byte
                || number instanceof Short
                || number instanceof Integer
                || number instanceof Long
                || number instanceof BigInteger
                || number instanceof BigDecimal) {
            return number;
        }
        throw new IllegalArgumentException(
                "HITL JSON numbers must use an immutable supported type: "
                        + number.getClass().getName());
    }
}
