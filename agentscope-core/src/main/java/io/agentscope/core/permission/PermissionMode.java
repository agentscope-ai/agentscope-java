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
package io.agentscope.core.permission;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Permission mode controlling how the engine evaluates tool execution requests.
 *
 * <ul>
 *   <li>{@link #DEFAULT}: all operations require explicit allow rules.
 *   <li>{@link #ACCEPT_EDITS}: file edits inside working directories are auto-allowed.
 *   <li>{@link #EXPLORE}: read-only mode; modification tools are denied.
 *   <li>{@link #BYPASS}: all operations allowed without rule evaluation.
 *   <li>{@link #DONT_ASK}: ASK decisions are demoted to DENY (for unattended runs).
 * </ul>
 */
public enum PermissionMode {
    DEFAULT("default"),
    ACCEPT_EDITS("accept_edits"),
    EXPLORE("explore"),
    BYPASS("bypass"),
    DONT_ASK("dont_ask");

    private final String value;

    PermissionMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Resolves a string (case-insensitive) to a {@code PermissionMode}.
     *
     * @param value JSON / config string representation
     * @return matching mode
     * @throws IllegalArgumentException if no enum value matches
     */
    @JsonCreator
    public static PermissionMode fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("PermissionMode value must not be null");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (PermissionMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown PermissionMode: " + value);
    }
}
