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

package io.agentscope.core.a2a.agent.hitl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.agentscope.core.permission.PermissionRule;
import java.util.List;
import java.util.Map;

/** Approval or denial for a pending tool, optionally replacing only its input. */
@JsonTypeName("user-confirmation")
public record A2aUserConfirmation(
        String toolCallId,
        boolean approved,
        Map<String, Object> modifiedInput,
        List<PermissionRule> permissionRules)
        implements A2aHitlResponse {

    public A2aUserConfirmation {
        requireText(toolCallId, "toolCallId");
        modifiedInput =
                modifiedInput == null ? null : HitlValueCopies.immutableJsonMap(modifiedInput);
        permissionRules = permissionRules == null ? List.of() : List.copyOf(permissionRules);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    @Override
    @JsonProperty("responseType")
    public String responseType() {
        return "user-confirmation";
    }
}
