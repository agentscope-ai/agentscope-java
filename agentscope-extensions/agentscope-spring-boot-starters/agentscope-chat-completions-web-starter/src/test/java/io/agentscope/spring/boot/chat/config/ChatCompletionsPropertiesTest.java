/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatCompletionsProperties}.
 *
 * <p>These tests verify that configuration properties are correctly bound and have appropriate
 * defaults.
 */
@DisplayName("ChatCompletionsProperties Tests")
class ChatCompletionsPropertiesTest {

    private ChatCompletionsProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ChatCompletionsProperties();
    }

    @Nested
    @DisplayName("Default Values Tests")
    class DefaultValuesTests {

        @Test
        @DisplayName("Should have enabled default to true")
        void shouldHaveEnabledDefaultToTrue() {
            assertThat(properties.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have basePath default to /v1/chat/completions")
        void shouldHaveBasePathDefaultToV1ChatCompletions() {
            assertThat(properties.getBasePath()).isEqualTo("/v1/chat/completions");
        }

        @Test
        @DisplayName("Should have sessionManager default instance")
        void shouldHaveSessionManagerDefaultInstance() {
            assertThat(properties.getSessionManager()).isNotNull();
        }

        @Test
        @DisplayName("Should have sessionManager.type default to in-memory")
        void shouldHaveSessionManagerTypeDefaultToInMemory() {
            assertThat(properties.getSessionManager().getType()).isEqualTo("in-memory");
        }
    }

    @Nested
    @DisplayName("Enabled Property Tests")
    class EnabledPropertyTests {

        @Test
        @DisplayName("Should set enabled to false")
        void shouldSetEnabledToFalse() {
            properties.setEnabled(false);

            assertThat(properties.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should set enabled to true")
        void shouldSetEnabledToTrue() {
            properties.setEnabled(true);

            assertThat(properties.isEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("BasePath Property Tests")
    class BasePathPropertyTests {

        @Test
        @DisplayName("Should set basePath to custom value")
        void shouldSetBasePathToCustomValue() {
            String customPath = "/api/custom/chat";
            properties.setBasePath(customPath);

            assertThat(properties.getBasePath()).isEqualTo(customPath);
        }

        @Test
        @DisplayName("Should set basePath to empty string")
        void shouldSetBasePathToEmptyString() {
            properties.setBasePath("");

            assertThat(properties.getBasePath()).isEmpty();
        }

        @Test
        @DisplayName("Should set basePath to null")
        void shouldSetBasePathToNull() {
            properties.setBasePath(null);

            assertThat(properties.getBasePath()).isNull();
        }
    }

    @Nested
    @DisplayName("SessionManager Property Tests")
    class SessionManagerPropertyTests {

        @Test
        @DisplayName("Should set sessionManager to custom instance")
        void shouldSetSessionManagerToCustomInstance() {
            ChatCompletionsProperties.SessionManager customManager =
                    new ChatCompletionsProperties.SessionManager();
            customManager.setType("redis");

            properties.setSessionManager(customManager);

            assertThat(properties.getSessionManager()).isSameAs(customManager);
            assertThat(properties.getSessionManager().getType()).isEqualTo("redis");
        }

        @Test
        @DisplayName("Should set sessionManager.type to redis")
        void shouldSetSessionManagerTypeToRedis() {
            properties.getSessionManager().setType("redis");

            assertThat(properties.getSessionManager().getType()).isEqualTo("redis");
        }

        @Test
        @DisplayName("Should set sessionManager.type to mysql")
        void shouldSetSessionManagerTypeToMysql() {
            properties.getSessionManager().setType("mysql");

            assertThat(properties.getSessionManager().getType()).isEqualTo("mysql");
        }

        @Test
        @DisplayName("Should set sessionManager.type to custom value")
        void shouldSetSessionManagerTypeToCustomValue() {
            String customType = "custom-session-manager";
            properties.getSessionManager().setType(customType);

            assertThat(properties.getSessionManager().getType()).isEqualTo(customType);
        }

        @Test
        @DisplayName("Should set sessionManager.type to null")
        void shouldSetSessionManagerTypeToNull() {
            properties.getSessionManager().setType(null);

            assertThat(properties.getSessionManager().getType()).isNull();
        }
    }

    @Nested
    @DisplayName("SessionManager Inner Class Tests")
    class SessionManagerInnerClassTests {

        @Test
        @DisplayName("Should create SessionManager instance")
        void shouldCreateSessionManagerInstance() {
            ChatCompletionsProperties.SessionManager manager =
                    new ChatCompletionsProperties.SessionManager();

            assertThat(manager).isNotNull();
            assertThat(manager.getType()).isEqualTo("in-memory");
        }

        @Test
        @DisplayName("Should get and set type")
        void shouldGetAndSetType() {
            ChatCompletionsProperties.SessionManager manager =
                    new ChatCompletionsProperties.SessionManager();

            manager.setType("test-type");
            assertThat(manager.getType()).isEqualTo("test-type");

            manager.setType("another-type");
            assertThat(manager.getType()).isEqualTo("another-type");
        }
    }
}
