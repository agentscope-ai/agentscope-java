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

import java.util.Map;

/** Immutable description of a tool call waiting for user or external-system input. */
public record A2aPendingTool(
        String toolCallId, String toolName, Map<String, Object> originalInput, String prompt) {

    public A2aPendingTool {
        requireText(toolCallId, "toolCallId");
        requireText(toolName, "toolName");
        originalInput = HitlValueCopies.immutableJsonMap(originalInput);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
