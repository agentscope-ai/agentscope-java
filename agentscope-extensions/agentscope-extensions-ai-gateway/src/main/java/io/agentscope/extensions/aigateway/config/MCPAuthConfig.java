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

/**
 * Authentication configuration for MCP endpoints.
 *
 * <p>Supports three authentication modes:
 * <ul>
 *   <li>Bearer token: {@code Authorization: Bearer {token}}</li>
 *   <li>Custom header: {@code {headerName}: {headerValue}}</li>
 *   <li>Query parameter: {@code ?{queryName}={queryValue}}</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Bearer token authentication
 * MCPAuthConfig bearerAuth = MCPAuthConfig.bearer("my-token");
 *
 * // Custom header authentication
 * MCPAuthConfig headerAuth = MCPAuthConfig.header("X-API-Key", "my-api-key");
 *
 * // Query parameter authentication
 * MCPAuthConfig queryAuth = MCPAuthConfig.query("apiKey", "my-api-key");
 * }</pre>
 */
public class MCPAuthConfig {

    /** Authentication type. */
    public enum AuthType {
        /** Bearer token in Authorization header. */
        BEARER,
        /** Custom HTTP header Authorization. */
        HEADER,
        /** Custom query parameter Authorization. */
        QUERY
    }

    private final AuthType type;
    private final String token;
    private final String headerName;
    private final String headerValue;
    private final String queryName;
    private final String queryValue;

    private MCPAuthConfig(
            AuthType type,
            String token,
            String headerName,
            String headerValue,
            String queryName,
            String queryValue) {
        this.type = type;
        this.token = token;
        this.headerName = headerName;
        this.headerValue = headerValue;
        this.queryName = queryName;
        this.queryValue = queryValue;
    }

    /**
     * Creates a Bearer token authentication config.
     *
     * <p>The token will be sent as: {@code Authorization: Bearer {token}}
     *
     * @param token the bearer token
     * @return authentication config
     */
    public static MCPAuthConfig bearer(String token) {
        return new MCPAuthConfig(AuthType.BEARER, token, null, null, null, null);
    }

    /**
     * Creates a custom header authentication config.
     *
     * <p>The header will be sent as: {@code {headerName}: {headerValue}}
     *
     * @param headerName the header name (e.g., "X-Client-ID", "X-API-Key")
     * @param headerValue the header value
     * @return authentication config
     */
    public static MCPAuthConfig header(String headerName, String headerValue) {
        return new MCPAuthConfig(AuthType.HEADER, null, headerName, headerValue, null, null);
    }

    /**
     * Creates a custom query parameter authentication config.
     *
     * <p>The query parameter will be appended to the URL: {@code ?{queryName}={queryValue}}
     *
     * @param queryName the query parameter name (e.g., "apiKey", "token")
     * @param queryValue the query parameter value
     * @return authentication config
     */
    public static MCPAuthConfig query(String queryName, String queryValue) {
        return new MCPAuthConfig(AuthType.QUERY, null, null, null, queryName, queryValue);
    }

    public AuthType getType() {
        return type;
    }

    public String getToken() {
        return token;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getHeaderValue() {
        return headerValue;
    }

    public String getQueryName() {
        return queryName;
    }

    public String getQueryValue() {
        return queryValue;
    }

    /**
     * Checks if this authentication config is valid and has the required values set.
     *
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        if (type == null) {
            return false;
        }
        switch (type) {
            case BEARER:
                return token != null && !token.isEmpty();
            case HEADER:
                return headerName != null
                        && !headerName.isEmpty()
                        && headerValue != null
                        && !headerValue.isEmpty();
            case QUERY:
                return queryName != null
                        && !queryName.isEmpty()
                        && queryValue != null
                        && !queryValue.isEmpty();
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        switch (type) {
            case BEARER:
                return "MCPAuthConfig{type=BEARER, token=***}";
            case HEADER:
                return "MCPAuthConfig{type=HEADER, headerName='"
                        + headerName
                        + "', headerValue=***}";
            case QUERY:
                return "MCPAuthConfig{type=QUERY, queryName='" + queryName + "', queryValue=***}";
            default:
                return "MCPAuthConfig{type=" + type + "}";
        }
    }
}
