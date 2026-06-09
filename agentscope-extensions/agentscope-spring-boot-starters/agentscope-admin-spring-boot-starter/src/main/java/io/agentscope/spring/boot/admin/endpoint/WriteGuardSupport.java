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
package io.agentscope.spring.boot.admin.endpoint;

import io.agentscope.spring.boot.admin.properties.AdminProperties;

/**
 * Helper that mirrors {@code controller.WriteGuard} for Actuator write endpoints.
 *
 * <p>Actuator's {@code @WriteOperation} signature does not expose raw HTTP headers, so the
 * "shared secret" is passed as a body parameter named {@code token}, and a positive confirmation
 * is required as the {@code confirm} parameter (must equal {@code yes}). In production it is still
 * strongly recommended to put Spring Security in front of {@code /actuator/**} on the management
 * port.
 */
final class WriteGuardSupport {

    private WriteGuardSupport() {}

    static void check(AdminProperties properties, String token, String confirm) {
        if (!properties.isWriteEnabled()) {
            throw new IllegalStateException(
                    "agentscope.admin.write-enabled is false; admin write operations are disabled");
        }
        if (confirm == null || !"yes".equalsIgnoreCase(confirm.trim())) {
            throw new IllegalArgumentException(
                    "missing or invalid 'confirm' parameter; send confirm=yes to proceed");
        }
        String expected = properties.getWriteToken();
        if (!expected.isEmpty() && !expected.equals(token)) {
            throw new SecurityException("missing or invalid 'token' parameter");
        }
    }
}
