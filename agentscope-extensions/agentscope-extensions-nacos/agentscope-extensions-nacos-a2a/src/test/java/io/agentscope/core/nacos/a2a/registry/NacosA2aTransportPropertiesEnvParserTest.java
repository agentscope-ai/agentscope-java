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

package io.agentscope.core.nacos.a2a.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import io.agentscope.core.nacos.a2a.registry.constants.Constants;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NacosA2aTransportPropertiesEnvParser}.
 */
@ExtendWith(MockitoExtension.class)
class NacosA2aTransportPropertiesEnvParserTest {

    private static Method doGetTransportPropertiesMethod;

    @BeforeAll
    static void setUp() throws NoSuchMethodException {
        doGetTransportPropertiesMethod =
                NacosA2aTransportPropertiesEnvParser.class.getDeclaredMethod(
                        "doGetTransportProperties", Map.class);
        doGetTransportPropertiesMethod.setAccessible(true);
    }

    @Test
    @DisplayName("Should return empty map when no environment variables match prefix")
    void testGetTransportPropertiesWithNoMatchingEnvVars() throws Exception {
        NacosA2aTransportPropertiesEnvParser parser = new NacosA2aTransportPropertiesEnvParser();
        Map<String, String> environment =
                Map.of(
                        "OTHER_VAR", "value",
                        "ANOTHER_VAR", "value");

        @SuppressWarnings("unchecked")
        Map<String, NacosA2aRegistryTransportProperties> result =
                (Map<String, NacosA2aRegistryTransportProperties>)
                        doGetTransportPropertiesMethod.invoke(parser, environment);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should parse single transport property correctly")
    void testParseSingleTransportProperty() throws Exception {
        NacosA2aTransportPropertiesEnvParser parser = new NacosA2aTransportPropertiesEnvParser();
        Map<String, String> environment =
                Map.of(Constants.PROPERTIES_ENV_PREFIX + "HTTP_HOST", "localhost");

        @SuppressWarnings("unchecked")
        Map<String, NacosA2aRegistryTransportProperties> result =
                (Map<String, NacosA2aRegistryTransportProperties>)
                        doGetTransportPropertiesMethod.invoke(parser, environment);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("HTTP"));

        NacosA2aRegistryTransportProperties properties = result.get("HTTP");
        assertEquals("HTTP", properties.transport());
        assertEquals("localhost", properties.host());
    }

    @Test
    @DisplayName("Should parse multiple transport properties correctly")
    void testParseMultipleTransportProperties() throws Exception {
        NacosA2aTransportPropertiesEnvParser parser = new NacosA2aTransportPropertiesEnvParser();
        Map<String, String> environment =
                Map.of(
                        Constants.PROPERTIES_ENV_PREFIX + "HTTP_HOST", "localhost",
                        Constants.PROPERTIES_ENV_PREFIX + "HTTP_PORT", "8080",
                        Constants.PROPERTIES_ENV_PREFIX + "WEBSOCKET_HOST", "127.0.0.1",
                        Constants.PROPERTIES_ENV_PREFIX + "WEBSOCKET_PORT", "9090");

        @SuppressWarnings("unchecked")
        Map<String, NacosA2aRegistryTransportProperties> result =
                (Map<String, NacosA2aRegistryTransportProperties>)
                        doGetTransportPropertiesMethod.invoke(parser, environment);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.containsKey("HTTP"));
        assertTrue(result.containsKey("WEBSOCKET"));

        NacosA2aRegistryTransportProperties httpProps = result.get("HTTP");
        assertEquals("HTTP", httpProps.transport());
        assertEquals("localhost", httpProps.host());
        assertEquals(8080, httpProps.port());

        NacosA2aRegistryTransportProperties wsProps = result.get("WEBSOCKET");
        assertEquals("WEBSOCKET", wsProps.transport());
        assertEquals("127.0.0.1", wsProps.host());
        assertEquals(9090, wsProps.port());
    }

    @Test
    @DisplayName("Should parse all transport attributes correctly")
    void testParseAllTransportAttributes() throws Exception {
        NacosA2aTransportPropertiesEnvParser parser = new NacosA2aTransportPropertiesEnvParser();
        Map<String, String> environment = new HashMap<>();
        environment.put(Constants.PROPERTIES_ENV_PREFIX + "HTTP_HOST", "localhost");
        environment.put(Constants.PROPERTIES_ENV_PREFIX + "HTTP_PORT", "8080");
        environment.put(Constants.PROPERTIES_ENV_PREFIX + "HTTP_PATH", "/api");
        environment.put(Constants.PROPERTIES_ENV_PREFIX + "HTTP_PROTOCOL", "https");
        environment.put(Constants.PROPERTIES_ENV_PREFIX + "HTTP_QUERY", "param=value");
        environment.put(Constants.PROPERTIES_ENV_PREFIX + "HTTP_SUPPORTTLS", "true");

        @SuppressWarnings("unchecked")
        Map<String, NacosA2aRegistryTransportProperties> result =
                (Map<String, NacosA2aRegistryTransportProperties>)
                        doGetTransportPropertiesMethod.invoke(parser, environment);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("HTTP"));

        NacosA2aRegistryTransportProperties properties = result.get("HTTP");
        assertEquals("HTTP", properties.transport());
        assertEquals("localhost", properties.host());
        assertEquals(8080, properties.port());
        assertEquals("/api", properties.path());
        assertEquals("https", properties.protocol());
        assertEquals("param=value", properties.query());
        assertTrue(properties.supportTls());
    }

    @Test
    @DisplayName("Should ignore invalid attribute names")
    void testIgnoreInvalidAttributeNames() throws Exception {
        NacosA2aTransportPropertiesEnvParser parser = new NacosA2aTransportPropertiesEnvParser();
        Map<String, String> environment =
                Map.of(
                        Constants.PROPERTIES_ENV_PREFIX + "HTTP_HOST", "localhost",
                        Constants.PROPERTIES_ENV_PREFIX + "HTTP_INVALID_ATTR",
                                "invalid_value" // Invalid attribute
                        );

        @SuppressWarnings("unchecked")
        Map<String, NacosA2aRegistryTransportProperties> result =
                (Map<String, NacosA2aRegistryTransportProperties>)
                        doGetTransportPropertiesMethod.invoke(parser, environment);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("HTTP"));

        NacosA2aRegistryTransportProperties properties = result.get("HTTP");
        assertEquals("HTTP", properties.transport());
        assertEquals("localhost", properties.host());
        // Invalid attribute should be ignored, so other values remain default
    }

    @Test
    @DisplayName("Should ignore environment variables with incorrect format")
    void testIgnoreIncorrectFormat() throws Exception {
        NacosA2aTransportPropertiesEnvParser parser = new NacosA2aTransportPropertiesEnvParser();
        Map<String, String> environment =
                Map.of(
                        Constants.PROPERTIES_ENV_PREFIX + "HTTP_HOST",
                                "localhost", // Correct format
                        Constants.PROPERTIES_ENV_PREFIX + "HTTP",
                                "invalid_format", // Incorrect format - no attribute
                        Constants.PROPERTIES_ENV_PREFIX + "SIMPLE_OTHER",
                                "another_invalid" // Incorrect format - no defined attribute
                        );

        @SuppressWarnings("unchecked")
        Map<String, NacosA2aRegistryTransportProperties> result =
                (Map<String, NacosA2aRegistryTransportProperties>)
                        doGetTransportPropertiesMethod.invoke(parser, environment);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("HTTP"));

        NacosA2aRegistryTransportProperties properties = result.get("HTTP");
        assertEquals("HTTP", properties.transport());
        assertEquals("localhost", properties.host());
    }

    @Test
    @DisplayName("Should handle empty environment map")
    void testHandleEmptyEnvironmentMap() throws Exception {
        NacosA2aTransportPropertiesEnvParser parser = new NacosA2aTransportPropertiesEnvParser();
        Map<String, String> environment = new HashMap<>();

        @SuppressWarnings("unchecked")
        Map<String, NacosA2aRegistryTransportProperties> result =
                (Map<String, NacosA2aRegistryTransportProperties>)
                        doGetTransportPropertiesMethod.invoke(parser, environment);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should handle transport property with all defaults when missing attributes")
    void testHandleMissingAttributes() throws Exception {
        NacosA2aTransportPropertiesEnvParser parser = new NacosA2aTransportPropertiesEnvParser();
        Map<String, String> environment =
                Map.of(
                        Constants.PROPERTIES_ENV_PREFIX + "HTTP_HOST", "localhost"
                        // Only host is provided, other attributes are missing
                        );

        @SuppressWarnings("unchecked")
        Map<String, NacosA2aRegistryTransportProperties> result =
                (Map<String, NacosA2aRegistryTransportProperties>)
                        doGetTransportPropertiesMethod.invoke(parser, environment);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("HTTP"));

        NacosA2aRegistryTransportProperties properties = result.get("HTTP");
        assertEquals("HTTP", properties.transport());
        assertEquals("localhost", properties.host());
        assertEquals(0, properties.port()); // Default int value
        assertNull(properties.path()); // Default null value
        assertNull(properties.protocol()); // Default null value
        assertNull(properties.query()); // Default null value
        assertFalse(properties.supportTls()); // Default boolean value
    }

    @Test
    @DisplayName("Should parse boolean values correctly for supportTls")
    void testParseBooleanSupportTlsValues() throws Exception {
        NacosA2aTransportPropertiesEnvParser parser = new NacosA2aTransportPropertiesEnvParser();
        Map<String, String> environment =
                Map.of(
                        Constants.PROPERTIES_ENV_PREFIX + "HTTP_HOST", "localhost",
                        Constants.PROPERTIES_ENV_PREFIX + "HTTP_SUPPORTTLS",
                                "false" // Explicitly false
                        );

        @SuppressWarnings("unchecked")
        Map<String, NacosA2aRegistryTransportProperties> result =
                (Map<String, NacosA2aRegistryTransportProperties>)
                        doGetTransportPropertiesMethod.invoke(parser, environment);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("HTTP"));

        NacosA2aRegistryTransportProperties properties = result.get("HTTP");
        assertEquals("HTTP", properties.transport());
        assertEquals("localhost", properties.host());
        assertFalse(properties.supportTls());
    }

    @Test
    @DisplayName("Should parse getTransportProperties method which calls doGetTransportProperties")
    void testGetTransportPropertiesMethod() {
        NacosA2aTransportPropertiesEnvParser parser = new NacosA2aTransportPropertiesEnvParser();

        // When - This will call System.getenv() internally, so we're testing the public API
        // We can't easily test this method without mocking System.getenv(), so we focus on
        // testing the internal method via reflection instead
        Map<String, NacosA2aRegistryTransportProperties> result = parser.getTransportProperties();

        assertNotNull(result);
    }

    @Test
    @DisplayName("Should handle invalid transport port")
    void testHandleInvalidPort() {
        NacosA2aTransportPropertiesEnvParser parser = new NacosA2aTransportPropertiesEnvParser();
        Map<String, String> environment =
                Map.of(Constants.PROPERTIES_ENV_PREFIX + "JSONRPC_PORT", "no_number");
        InvocationTargetException exception =
                assertThrows(
                        InvocationTargetException.class,
                        () -> doGetTransportPropertiesMethod.invoke(parser, environment));
        assertInstanceOf(NacosRuntimeException.class, exception.getCause());
        NacosRuntimeException cause = (NacosRuntimeException) exception.getCause();
        assertEquals(
                "errCode: 400, errMsg: Invalid `port` value for transport `JSONRPC`: no_number ",
                cause.getMessage());
    }
}
