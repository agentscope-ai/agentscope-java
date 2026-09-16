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
package io.agentscope.harness.agent.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Source material, prior to model rendering. Authority is determined by its provider, not text. */
public record ContextItem(
        String kind,
        String sourceId,
        String revision,
        Placement placement,
        boolean required,
        int priority,
        String content) {
    public enum Placement {
        SYSTEM,
        RUNTIME,
        REFERENCE
    }

    public ContextItem(String kind, String sourceId, String content) {
        this(
                kind,
                sourceId,
                versionOf(content),
                Placement.REFERENCE,
                "additional".equals(kind),
                0,
                content);
    }

    public static ContextItem instruction(String kind, String sourceId, String content) {
        return new ContextItem(
                kind, sourceId, versionOf(content), Placement.SYSTEM, true, 100, content);
    }

    private static String versionOf(String content) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            Objects.requireNonNullElse(content, "")
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public ContextItem {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(sourceId);
        Objects.requireNonNull(revision);
        Objects.requireNonNull(placement);
        if (!kind.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("Invalid context kind: " + kind);
        content = content == null ? "" : content;
    }
}
