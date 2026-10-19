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

package io.agentscope.core.nacos.a2a.utils;

import com.alibaba.nacos.api.ai.model.a2a.SecurityScheme;
import java.util.LinkedHashMap;
import java.util.Map;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.AuthorizationCodeOAuthFlow;
import org.a2aproject.sdk.spec.ClientCredentialsOAuthFlow;
import org.a2aproject.sdk.spec.DeviceCodeOAuthFlow;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.MutualTLSSecurityScheme;
import org.a2aproject.sdk.spec.OAuth2SecurityScheme;
import org.a2aproject.sdk.spec.OAuthFlows;
import org.a2aproject.sdk.spec.OpenIdConnectSecurityScheme;

/**
 * Security scheme converter between Nacos and the A2A specification.
 *
 * <p>Nacos stores A2A security schemes as maps in the A2A 0.3 format, with the scheme kind in
 * {@code type}. A scheme Nacos does not describe this way is left out when reading.
 */
final class SecuritySchemeConverterUtil {

    private static final String TYPE = "type";

    private static final String DESCRIPTION = "description";

    private SecuritySchemeConverterUtil() {}

    static Map<String, org.a2aproject.sdk.spec.SecurityScheme> convertToA2aSecuritySchemes(
            Map<String, SecurityScheme> securitySchemes) {
        if (null == securitySchemes) {
            return null;
        }
        Map<String, org.a2aproject.sdk.spec.SecurityScheme> result = new LinkedHashMap<>();
        securitySchemes.forEach(
                (name, scheme) -> {
                    org.a2aproject.sdk.spec.SecurityScheme converted =
                            convertToA2aSecurityScheme(scheme);
                    if (null != converted) {
                        result.put(name, converted);
                    }
                });
        return result;
    }

    private static org.a2aproject.sdk.spec.SecurityScheme convertToA2aSecurityScheme(
            Map<String, Object> scheme) {
        if (null == scheme) {
            return null;
        }
        String description = string(scheme, DESCRIPTION);
        return switch (String.valueOf(scheme.get(TYPE))) {
            case "apiKey" -> {
                APIKeySecurityScheme.Location location = apiKeyLocation(string(scheme, "in"));
                yield null == location
                        ? null
                        : new APIKeySecurityScheme(location, string(scheme, "name"), description);
            }
            case "http" ->
                    new HTTPAuthSecurityScheme(
                            string(scheme, "bearerFormat"), string(scheme, "scheme"), description);
            case "oauth2" ->
                    new OAuth2SecurityScheme(
                            convertToA2aOAuthFlows(map(scheme, "flows")),
                            description,
                            string(scheme, "oauth2MetadataUrl"));
            case "openIdConnect" ->
                    new OpenIdConnectSecurityScheme(
                            string(scheme, "openIdConnectUrl"), description);
            case "mutualTLS" -> new MutualTLSSecurityScheme(description);
            default -> null;
        };
    }

    private static APIKeySecurityScheme.Location apiKeyLocation(String in) {
        for (APIKeySecurityScheme.Location location : APIKeySecurityScheme.Location.values()) {
            if (location.asString().equals(in)) {
                return location;
            }
        }
        return null;
    }

    private static OAuthFlows convertToA2aOAuthFlows(Map<String, Object> flows) {
        Map<String, Object> code = map(flows, "authorizationCode");
        Map<String, Object> client = map(flows, "clientCredentials");
        Map<String, Object> device = map(flows, "deviceCode");
        return new OAuthFlows(
                null == code
                        ? null
                        : new AuthorizationCodeOAuthFlow(
                                string(code, "authorizationUrl"),
                                string(code, "refreshUrl"),
                                scopes(code),
                                string(code, "tokenUrl"),
                                Boolean.TRUE.equals(code.get("pkceRequired"))),
                null == client
                        ? null
                        : new ClientCredentialsOAuthFlow(
                                string(client, "refreshUrl"),
                                scopes(client),
                                string(client, "tokenUrl")),
                null == device
                        ? null
                        : new DeviceCodeOAuthFlow(
                                string(device, "deviceAuthorizationUrl"),
                                string(device, "tokenUrl"),
                                string(device, "refreshUrl"),
                                scopes(device)));
    }

    static Map<String, SecurityScheme> convertToNacosSecuritySchemes(
            Map<String, org.a2aproject.sdk.spec.SecurityScheme> securitySchemes) {
        if (securitySchemes == null) {
            return null;
        }
        Map<String, SecurityScheme> result = new LinkedHashMap<>();
        securitySchemes.forEach(
                (name, scheme) -> result.put(name, convertToNacosSecurityScheme(scheme)));
        return result;
    }

    private static SecurityScheme convertToNacosSecurityScheme(
            org.a2aproject.sdk.spec.SecurityScheme scheme) {
        SecurityScheme result = new SecurityScheme();
        if (scheme instanceof APIKeySecurityScheme apiKey) {
            result.put(TYPE, "apiKey");
            putIfPresent(result, "name", apiKey.name());
            putIfPresent(result, "in", apiKey.location().asString());
            putIfPresent(result, DESCRIPTION, apiKey.description());
        } else if (scheme instanceof HTTPAuthSecurityScheme http) {
            result.put(TYPE, "http");
            putIfPresent(result, "scheme", http.scheme());
            putIfPresent(result, "bearerFormat", http.bearerFormat());
            putIfPresent(result, DESCRIPTION, http.description());
        } else if (scheme instanceof OAuth2SecurityScheme oauth2) {
            result.put(TYPE, "oauth2");
            result.put("flows", convertToNacosOAuthFlows(oauth2.flows()));
            putIfPresent(result, "oauth2MetadataUrl", oauth2.oauth2MetadataUrl());
            putIfPresent(result, DESCRIPTION, oauth2.description());
        } else if (scheme instanceof OpenIdConnectSecurityScheme openIdConnect) {
            result.put(TYPE, "openIdConnect");
            putIfPresent(result, "openIdConnectUrl", openIdConnect.openIdConnectUrl());
            putIfPresent(result, DESCRIPTION, openIdConnect.description());
        } else if (scheme instanceof MutualTLSSecurityScheme mutualTls) {
            result.put(TYPE, "mutualTLS");
            putIfPresent(result, DESCRIPTION, mutualTls.description());
        }
        return result;
    }

    private static Map<String, Object> convertToNacosOAuthFlows(OAuthFlows flows) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (null == flows) {
            return result;
        }
        if (null != flows.authorizationCode()) {
            AuthorizationCodeOAuthFlow code = flows.authorizationCode();
            Map<String, Object> flow = new LinkedHashMap<>();
            putIfPresent(flow, "authorizationUrl", code.authorizationUrl());
            putIfPresent(flow, "tokenUrl", code.tokenUrl());
            putIfPresent(flow, "refreshUrl", code.refreshUrl());
            flow.put("scopes", code.scopes());
            flow.put("pkceRequired", code.pkceRequired());
            result.put("authorizationCode", flow);
        }
        if (null != flows.clientCredentials()) {
            ClientCredentialsOAuthFlow client = flows.clientCredentials();
            Map<String, Object> flow = new LinkedHashMap<>();
            putIfPresent(flow, "tokenUrl", client.tokenUrl());
            putIfPresent(flow, "refreshUrl", client.refreshUrl());
            flow.put("scopes", client.scopes());
            result.put("clientCredentials", flow);
        }
        if (null != flows.deviceCode()) {
            DeviceCodeOAuthFlow device = flows.deviceCode();
            Map<String, Object> flow = new LinkedHashMap<>();
            putIfPresent(flow, "deviceAuthorizationUrl", device.deviceAuthorizationUrl());
            putIfPresent(flow, "tokenUrl", device.tokenUrl());
            putIfPresent(flow, "refreshUrl", device.refreshUrl());
            flow.put("scopes", device.scopes());
            result.put("deviceCode", flow);
        }
        return result;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (null != value) {
            map.put(key, value);
        }
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = null == map ? null : map.get(key);
        return null == value ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> map, String key) {
        Object value = null == map ? null : map.get(key);
        return value instanceof Map<?, ?> nested ? (Map<String, Object>) nested : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> scopes(Map<String, Object> flow) {
        Object value = flow.get("scopes");
        return value instanceof Map<?, ?> scopes ? (Map<String, String>) scopes : Map.of();
    }
}
