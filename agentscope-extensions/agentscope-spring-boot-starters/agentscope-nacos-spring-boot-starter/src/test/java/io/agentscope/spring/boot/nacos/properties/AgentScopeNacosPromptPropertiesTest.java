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

package io.agentscope.spring.boot.nacos.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.PropertyKeyConst;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentScopeNacosPromptProperties}.
 */
class AgentScopeNacosPromptPropertiesTest {

    @Nested
    @DisplayName("Default values")
    class DefaultValueTests {

        @Test
        @DisplayName("should have enabled=true by default")
        void shouldBeEnabledByDefault() {
            AgentScopeNacosPromptProperties props = new AgentScopeNacosPromptProperties();
            assertTrue(props.isEnabled());
        }

        @Test
        @DisplayName("should have null sysPromptKey by default")
        void shouldHaveNullSysPromptKeyByDefault() {
            AgentScopeNacosPromptProperties props = new AgentScopeNacosPromptProperties();
            assertNull(props.getSysPromptKey());
        }

        @Test
        @DisplayName("should have empty variables map by default")
        void shouldHaveEmptyVariablesByDefault() {
            AgentScopeNacosPromptProperties props = new AgentScopeNacosPromptProperties();
            assertNotNull(props.getVariables());
            assertTrue(props.getVariables().isEmpty());
        }
    }

    @Nested
    @DisplayName("Setters and getters")
    class SetterGetterTests {

        @Test
        @DisplayName("should set and get sysPromptKey")
        void shouldSetAndGetSysPromptKey() {
            AgentScopeNacosPromptProperties props = new AgentScopeNacosPromptProperties();
            props.setSysPromptKey("my-agent");
            assertEquals("my-agent", props.getSysPromptKey());
        }

        @Test
        @DisplayName("should set and get variables")
        void shouldSetAndGetVariables() {
            AgentScopeNacosPromptProperties props = new AgentScopeNacosPromptProperties();
            Map<String, String> vars = new HashMap<>();
            vars.put("role", "Helper");
            vars.put("env", "prod");
            props.setVariables(vars);

            assertEquals(2, props.getVariables().size());
            assertEquals("Helper", props.getVariables().get("role"));
            assertEquals("prod", props.getVariables().get("env"));
        }

        @Test
        @DisplayName("should set and get enabled")
        void shouldSetAndGetEnabled() {
            AgentScopeNacosPromptProperties props = new AgentScopeNacosPromptProperties();
            props.setEnabled(false);
            assertEquals(false, props.isEnabled());
        }
    }

    @Nested
    @DisplayName("Inherited BaseNacosProperties - getNacosProperties()")
    class NacosPropertiesTests {

        @Test
        @DisplayName("should return default server-addr and namespace when not set")
        void shouldReturnDefaultsWhenNotSet() {
            AgentScopeNacosPromptProperties props = new AgentScopeNacosPromptProperties();
            Properties nacosProps = props.getNacosProperties();

            assertEquals("127.0.0.1:8848", nacosProps.get(PropertyKeyConst.SERVER_ADDR));
            assertEquals("public", nacosProps.get(PropertyKeyConst.NAMESPACE));
        }

        @Test
        @DisplayName("should override server-addr when set")
        void shouldOverrideServerAddr() {
            AgentScopeNacosPromptProperties props = new AgentScopeNacosPromptProperties();
            props.setServerAddr("prompt.example.com:8848");
            Properties nacosProps = props.getNacosProperties();

            assertEquals("prompt.example.com:8848", nacosProps.get(PropertyKeyConst.SERVER_ADDR));
        }

        @Test
        @DisplayName("should include username and password when set")
        void shouldIncludeCredentials() {
            AgentScopeNacosPromptProperties props = new AgentScopeNacosPromptProperties();
            props.setUsername("admin");
            props.setPassword("secret");
            Properties nacosProps = props.getNacosProperties();

            assertEquals("admin", nacosProps.get(PropertyKeyConst.USERNAME));
            assertEquals("secret", nacosProps.get(PropertyKeyConst.PASSWORD));
        }

        @Test
        @DisplayName("should include accessKey and secretKey when set")
        void shouldIncludeCloudCredentials() {
            AgentScopeNacosPromptProperties props = new AgentScopeNacosPromptProperties();
            props.setAccessKey("ak-123");
            props.setSecretKey("sk-456");
            Properties nacosProps = props.getNacosProperties();

            assertEquals("ak-123", nacosProps.get(PropertyKeyConst.ACCESS_KEY));
            assertEquals("sk-456", nacosProps.get(PropertyKeyConst.SECRET_KEY));
        }

        @Test
        @DisplayName("should not include username/password when not set")
        void shouldNotIncludeCredentialsWhenNotSet() {
            AgentScopeNacosPromptProperties props = new AgentScopeNacosPromptProperties();
            Properties nacosProps = props.getNacosProperties();

            assertNull(nacosProps.get(PropertyKeyConst.USERNAME));
            assertNull(nacosProps.get(PropertyKeyConst.PASSWORD));
        }
    }
}
