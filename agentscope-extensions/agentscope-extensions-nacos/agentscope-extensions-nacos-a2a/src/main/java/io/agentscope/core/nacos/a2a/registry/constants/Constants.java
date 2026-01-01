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

package io.agentscope.core.nacos.a2a.registry.constants;

/**
 * Constants for Nacos A2A registry.
 */
public class Constants {

    /**
     * HTTP protocol constant string.
     */
    public static final String PROTOCOL_TYPE_HTTP = "http";

    /**
     * HTTPS protocol constant string.
     */
    public static final String PROTOCOL_TYPE_HTTPS = "https";

    /**
     * Prefix of environment variables for transport properties.
     */
    public static final String PROPERTIES_ENV_PREFIX = "NACOS_A2A_AGENT_";

    /**
     * The enums for Environment Transport attributes.
     *
     * <p>The environment key format is
     * {@link #PROPERTIES_ENV_PREFIX}{@code {TRANSPORT}}_{@link TransportPropertiesAttribute#envKey}. For example:
     * {@code NACOS_A2A_AGENT_JSONRPC_HOST=} means transport attribute {@link #HOST} for transport {@code JSONRPC}.
     */
    public enum TransportPropertiesAttribute {
        HOST("HOST", "host"),
        PORT("PORT", "port"),
        PATH("PATH", "path"),
        PROTOCOL("PROTOCOL", "protocol"),
        QUERY("QUERY", "query"),
        SUPPORT_TLS("SUPPORTTLS", "supportTls");

        private final String envKey;

        private final String propertyKey;

        TransportPropertiesAttribute(String envKey, String propertyKey) {
            this.envKey = envKey;
            this.propertyKey = propertyKey;
        }

        /**
         * Gets the property key associated with this transport attribute.
         *
         * @return the property key
         */
        public String getPropertyKey() {
            return propertyKey;
        }

        /**
         * Get {@link TransportPropertiesAttribute} instance by environment key.
         *
         * @param envKey environment key
         * @return {@link TransportPropertiesAttribute} instance if found, null if not found
         */
        public static TransportPropertiesAttribute getByEnvKey(String envKey) {
            for (TransportPropertiesAttribute each : TransportPropertiesAttribute.values()) {
                if (each.envKey.equals(envKey)) {
                    return each;
                }
            }
            return null;
        }
    }
}
