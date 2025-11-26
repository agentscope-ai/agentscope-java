/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.agentscope.core.tracing.model;

public enum Role {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
    DEVELOPER("developer");

    private final String value;

    public String getValue() {
        return value;
    }

    Role(String value) {
        this.value = value;
    }
}
