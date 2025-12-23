/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.extensions.aigateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MCPAuthConfigTest {

    @Test
    void testBearerAuth() {
        MCPAuthConfig config = MCPAuthConfig.bearer("my-token-12345");

        assertEquals(MCPAuthConfig.AuthType.BEARER, config.getType());
        assertEquals("my-token-12345", config.getToken());
        assertNull(config.getHeaderName());
        assertNull(config.getHeaderValue());
        assertNull(config.getQueryName());
        assertNull(config.getQueryValue());
    }

    @Test
    void testHeaderAuth() {
        MCPAuthConfig config = MCPAuthConfig.header("X-API-Key", "api-key-value");

        assertEquals(MCPAuthConfig.AuthType.HEADER, config.getType());
        assertNull(config.getToken());
        assertEquals("X-API-Key", config.getHeaderName());
        assertEquals("api-key-value", config.getHeaderValue());
        assertNull(config.getQueryName());
        assertNull(config.getQueryValue());
    }

    @Test
    void testQueryAuth() {
        MCPAuthConfig config = MCPAuthConfig.query("apiKey", "query-api-key");

        assertEquals(MCPAuthConfig.AuthType.QUERY, config.getType());
        assertNull(config.getToken());
        assertNull(config.getHeaderName());
        assertNull(config.getHeaderValue());
        assertEquals("apiKey", config.getQueryName());
        assertEquals("query-api-key", config.getQueryValue());
    }

    @Test
    void testIsValidBearerWithValidToken() {
        MCPAuthConfig config = MCPAuthConfig.bearer("valid-token");
        assertTrue(config.isValid());
    }

    @Test
    void testIsValidBearerWithNullToken() {
        MCPAuthConfig config = MCPAuthConfig.bearer(null);
        assertFalse(config.isValid());
    }

    @Test
    void testIsValidBearerWithEmptyToken() {
        MCPAuthConfig config = MCPAuthConfig.bearer("");
        assertFalse(config.isValid());
    }

    @Test
    void testIsValidHeaderWithValidValues() {
        MCPAuthConfig config = MCPAuthConfig.header("X-Key", "value");
        assertTrue(config.isValid());
    }

    @Test
    void testIsValidHeaderWithNullName() {
        MCPAuthConfig config = MCPAuthConfig.header(null, "value");
        assertFalse(config.isValid());
    }

    @Test
    void testIsValidHeaderWithEmptyName() {
        MCPAuthConfig config = MCPAuthConfig.header("", "value");
        assertFalse(config.isValid());
    }

    @Test
    void testIsValidHeaderWithNullValue() {
        MCPAuthConfig config = MCPAuthConfig.header("X-Key", null);
        assertFalse(config.isValid());
    }

    @Test
    void testIsValidHeaderWithEmptyValue() {
        MCPAuthConfig config = MCPAuthConfig.header("X-Key", "");
        assertFalse(config.isValid());
    }

    @Test
    void testIsValidQueryWithValidValues() {
        MCPAuthConfig config = MCPAuthConfig.query("key", "value");
        assertTrue(config.isValid());
    }

    @Test
    void testIsValidQueryWithNullName() {
        MCPAuthConfig config = MCPAuthConfig.query(null, "value");
        assertFalse(config.isValid());
    }

    @Test
    void testIsValidQueryWithEmptyName() {
        MCPAuthConfig config = MCPAuthConfig.query("", "value");
        assertFalse(config.isValid());
    }

    @Test
    void testIsValidQueryWithNullValue() {
        MCPAuthConfig config = MCPAuthConfig.query("key", null);
        assertFalse(config.isValid());
    }

    @Test
    void testIsValidQueryWithEmptyValue() {
        MCPAuthConfig config = MCPAuthConfig.query("key", "");
        assertFalse(config.isValid());
    }

    @Test
    void testToStringBearer() {
        MCPAuthConfig config = MCPAuthConfig.bearer("secret-token");
        String str = config.toString();

        assertNotNull(str);
        assertTrue(str.contains("BEARER"));
        assertTrue(str.contains("***"));
        assertFalse(str.contains("secret-token"));
    }

    @Test
    void testToStringHeader() {
        MCPAuthConfig config = MCPAuthConfig.header("X-Client-ID", "secret-client");
        String str = config.toString();

        assertNotNull(str);
        assertTrue(str.contains("HEADER"));
        assertTrue(str.contains("X-Client-ID"));
        assertTrue(str.contains("***"));
        assertFalse(str.contains("secret-client"));
    }

    @Test
    void testToStringQuery() {
        MCPAuthConfig config = MCPAuthConfig.query("apiKey", "secret-key");
        String str = config.toString();

        assertNotNull(str);
        assertTrue(str.contains("QUERY"));
        assertTrue(str.contains("apiKey"));
        assertTrue(str.contains("***"));
        assertFalse(str.contains("secret-key"));
    }

    @Test
    void testAuthTypeEnumValues() {
        MCPAuthConfig.AuthType[] types = MCPAuthConfig.AuthType.values();

        assertEquals(3, types.length);
        assertEquals(MCPAuthConfig.AuthType.BEARER, MCPAuthConfig.AuthType.valueOf("BEARER"));
        assertEquals(MCPAuthConfig.AuthType.HEADER, MCPAuthConfig.AuthType.valueOf("HEADER"));
        assertEquals(MCPAuthConfig.AuthType.QUERY, MCPAuthConfig.AuthType.valueOf("QUERY"));
    }

    @Test
    void testDifferentHeaderNames() {
        String[] headerNames = {"X-API-Key", "X-Client-ID", "Authorization", "X-Custom-Header"};

        for (String headerName : headerNames) {
            MCPAuthConfig config = MCPAuthConfig.header(headerName, "test-value");
            assertEquals(headerName, config.getHeaderName());
            assertTrue(config.isValid());
        }
    }

    @Test
    void testDifferentQueryNames() {
        String[] queryNames = {"apiKey", "token", "access_token", "key"};

        for (String queryName : queryNames) {
            MCPAuthConfig config = MCPAuthConfig.query(queryName, "test-value");
            assertEquals(queryName, config.getQueryName());
            assertTrue(config.isValid());
        }
    }
}
