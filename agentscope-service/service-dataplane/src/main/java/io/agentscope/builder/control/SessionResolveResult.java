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
package io.agentscope.builder.control;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.agentscope.builder.web.managed.EnvironmentDto;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import java.util.List;
import java.util.Map;

/**
 * One-shot session materialization returned by {@code GET /api/internal/sessions/{id}/resolve} on
 * the control plane (aistiod).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionResolveResult(
        ManagedSessionDto session,
        Map<String, Object> agentSnapshot,
        String workspacePath,
        String workspaceId,
        Integer workspaceVersion,
        Map<String, String> definitionFiles,
        EnvironmentDto environment,
        List<Map<String, Object>> vaultCredentials,
        List<Map<String, Object>> memoryMounts,
        Map<String, Object> teamContext) {}
