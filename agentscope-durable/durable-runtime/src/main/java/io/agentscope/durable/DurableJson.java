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
package io.agentscope.durable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.agentscope.durable.store.ObjectStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.erdtman.jcs.JsonCanonicalizer;

/** JSON values are copied at durable boundaries; digests use RFC 8785. */
public final class DurableJson {
    private static final ObjectMapper MAPPER =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxStringLength(ObjectStore.MAX_BYTES)
                                                    .build())
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private DurableJson() {}

    public static JsonNode value(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static JsonNode copy(JsonNode value) {
        return value == null ? NullNode.instance : value.deepCopy();
    }

    public static JsonNode parse(String json) {
        try {
            JsonNode value = MAPPER.readTree(json);
            if (value == null || value.isMissingNode())
                throw new DurableException(DurableException.Code.INVALID, "Empty JSON");
            return value;
        } catch (IOException e) {
            throw new DurableException(DurableException.Code.INVALID, "Invalid JSON", e);
        }
    }

    public static <T> T convert(JsonNode value, Class<T> type) {
        try {
            return MAPPER.treeToValue(value, type);
        } catch (IOException e) {
            throw new DurableException(
                    DurableException.Code.INVALID, "Invalid " + type.getSimpleName(), e);
        }
    }

    public static String hash(Object value) {
        try {
            // The canonicalizer accepts containers; wrapping also supports scalar/null inputs.
            JsonNode envelope = MAPPER.createObjectNode().set("value", value(value));
            requirePortableNumbers(envelope);
            return digest(new JsonCanonicalizer(envelope.toString()).getEncodedUTF8());
        } catch (IOException e) {
            throw new DurableException(
                    DurableException.Code.INVALID, "Cannot canonicalize value", e);
        }
    }

    /** Prevent different exact values from collapsing into one IEEE-754 canonical digest. */
    public static void requirePortableNumbers(JsonNode value) {
        if (value.isNumber()) {
            double number = value.doubleValue();
            if (!Double.isFinite(number)
                    || BigDecimal.valueOf(number).compareTo(value.decimalValue()) != 0)
                throw new DurableException(
                        DurableException.Code.INVALID,
                        "JSON number loses precision in canonical form; encode exact values as"
                                + " strings");
        } else if (value.isContainerNode()) value.forEach(DurableJson::requirePortableNumbers);
    }

    public static String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String text(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256)
            throw new DurableException(
                    DurableException.Code.INVALID, name + " must contain 1..256 characters");
        return value;
    }
}
