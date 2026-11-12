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
package io.agentcore.managed.server.runtime.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical map ordering keeps command identity stable across HTTP retries and replicas. */
public final class RuntimePayloadDigest {
    private RuntimePayloadDigest() {}

    public static String of(ObjectMapper mapper, Object payload) {
        try {
            byte[] json =
                    mapper.copy()
                            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsBytes(payload);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot encode runtime command", error);
        }
    }
}
