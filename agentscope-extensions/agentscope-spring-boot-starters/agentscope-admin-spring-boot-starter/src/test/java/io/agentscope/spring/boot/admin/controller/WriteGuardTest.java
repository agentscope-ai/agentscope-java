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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.spring.boot.admin.properties.AdminProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Visibility of {@link WriteGuard} is package-private, so this test lives in the same package.
 */
class WriteGuardTest {

    @Test
    void rejectsWhenWritesDisabled() {
        WriteGuard guard = new WriteGuard(new AdminProperties()); // writeEnabled defaults to false
        assertThatThrownBy(() -> guard.check("anything"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> {
                            assert e.getStatusCode().value() == HttpStatus.FORBIDDEN.value();
                        });
    }

    @Test
    void acceptsWhenWritesEnabledAndNoTokenConfigured() {
        AdminProperties p = new AdminProperties();
        p.setWriteEnabled(true);
        WriteGuard guard = new WriteGuard(p);
        assertThatCode(() -> guard.check(null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBadTokenWhenConfigured() {
        AdminProperties p = new AdminProperties();
        p.setWriteEnabled(true);
        p.setWriteToken("s3cret");
        WriteGuard guard = new WriteGuard(p);
        assertThatThrownBy(() -> guard.check("wrong"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> {
                            assert e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value();
                        });
    }

    @Test
    void acceptsMatchingToken() {
        AdminProperties p = new AdminProperties();
        p.setWriteEnabled(true);
        p.setWriteToken("s3cret");
        WriteGuard guard = new WriteGuard(p);
        assertThatCode(() -> guard.check("s3cret")).doesNotThrowAnyException();
    }
}
