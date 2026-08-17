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
package io.agentscope.spring.boot.admin.controller;

import io.agentscope.spring.boot.admin.properties.AdminProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pre-flight gate for write operations.
 *
 * <p>Throws {@link ResponseStatusException} when the request must be rejected. Designed to be
 * called as the first statement in every write controller method — keeps the failure surface in
 * one place.
 */
final class WriteGuard {

    private final AdminProperties properties;

    WriteGuard(AdminProperties properties) {
        this.properties = properties;
    }

    /**
     * @param suppliedToken value of the {@code X-Agentscope-Admin-Token} header (may be null)
     */
    void check(String suppliedToken) {
        if (!properties.isWriteEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "agentscope.admin.write-enabled is false; admin write operations are disabled");
        }
        String expected = properties.getWriteToken();
        if (!expected.isEmpty() && !expected.equals(suppliedToken)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "missing or invalid X-Agentscope-Admin-Token header");
        }
    }
}
