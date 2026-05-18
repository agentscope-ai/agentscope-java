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
package io.agentscope.spring.boot.transport;

import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.core.model.transport.WebSocketTransport;
import io.agentscope.core.model.transport.websocket.JdkWebSocketTransport;
import io.agentscope.core.model.transport.websocket.OkHttpWebSocketTransport;
import io.agentscope.core.model.transport.websocket.WebSocketTransportConfig;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import io.agentscope.spring.boot.properties.HttpTransportProperties;
import io.agentscope.spring.boot.properties.WebSocketTransportProperties;

/**
 * Provides different implementations of HttpTransport and WebSocketTransport based on the type configuration provided.
 */
public class TransportProviderType {

    /**
     * Provides different implementations of HttpTransport based on the type configuration provided.
     */
    public enum HttpType {
        /**
         * Uses the JDK HttpClient to make HTTP requests.
         */
        JDK {
            @Override
            public HttpTransport createHttpTransport(HttpTransportConfig config) {
                return JdkHttpTransport.builder().config(config).build();
            }
        },
        /**
         * Uses the OkHttpClient to make HTTP requests.
         */
        OKHTTP {
            @Override
            public HttpTransport createHttpTransport(HttpTransportConfig config) {
                return OkHttpTransport.builder().config(config).build();
            }
        },
        /**
         * Uses the Spring WebClient to make HTTP requests.
         */
        WEBCLIENT {
            @Override
            public HttpTransport createHttpTransport(HttpTransportConfig config) {
                return WebClientTransport.builder().config(config).build();
            }
        };

        /**
         * Creates a HttpTransport based on the provided configuration.
         *
         * @param config the HttpTransportConfig
         * @return A new HttpTransport instance
         */
        public abstract HttpTransport createHttpTransport(HttpTransportConfig config);

        /**
         * Creates a HttpTransport from the provided properties.
         *
         * @param properties the AgentscopeProperties
         * @return A new HttpTransport instance
         */
        public static HttpTransport createTransportFromProperties(AgentscopeProperties properties) {
            HttpTransportProperties httpTransportProperties = properties.getTransport().getHttp();
            HttpType httpType = httpTransportProperties.getType();
            HttpTransportConfig httpTransportConfig = httpTransportProperties.toTransportConfig();
            return httpType.createHttpTransport(httpTransportConfig);
        }
    }

    /**
     * Provides different implementations of WebSocketTransport based on the type configuration provided.
     */
    public enum WebSocketType {
        /**
         * Uses the JDK WebSocket API to establish WebSocket connections.
         */
        JDK {
            @Override
            public WebSocketTransport createWebSocketTransport(WebSocketTransportConfig config) {
                return JdkWebSocketTransport.create(config);
            }
        },
        /**
         * Uses the OkHttpClient to establish WebSocket connections.
         */
        OKHTTP {
            @Override
            public WebSocketTransport createWebSocketTransport(WebSocketTransportConfig config) {
                return OkHttpWebSocketTransport.create(config);
            }
        };

        /**
         * Creates a WebSocketTransport based on the provided configuration.
         *
         * @param config the WebSocketTransportConfig
         * @return A new WebSocketTransport instance
         */
        public abstract WebSocketTransport createWebSocketTransport(
                WebSocketTransportConfig config);

        /**
         * Creates a WebSocketTransport from the provided properties.
         *
         * @param properties the AgentscopeProperties
         * @return A new WebSocketTransport instance
         */
        public static WebSocketTransport createTransportFromProperties(
                AgentscopeProperties properties) {
            WebSocketTransportProperties webSocketTransportProperties =
                    properties.getTransport().getWebsocket();
            WebSocketType webSocketType = webSocketTransportProperties.getType();
            WebSocketTransportConfig webSocketTransportConfig =
                    webSocketTransportProperties.toTransportConfig();
            return webSocketType.createWebSocketTransport(webSocketTransportConfig);
        }
    }
}
