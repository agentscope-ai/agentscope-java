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
package io.agentcore.managed.harness.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.erdtman.jcs.JsonCanonicalizer;

/** RFC 8785 over the complete request; counters on the public protocol are decimal strings. */
public final class CanonicalJson {
    private CanonicalJson() {}

    public static String hash(JsonNode value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            new JsonCanonicalizer(value.toString())
                                                    .getEncodedUTF8()));
        } catch (IOException | NoSuchAlgorithmException invalid) {
            throw new IllegalArgumentException("Invalid canonical JSON", invalid);
        }
    }
}
