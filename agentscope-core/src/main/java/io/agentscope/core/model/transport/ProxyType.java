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
package io.agentscope.core.model.transport;

/**
 * Enumeration of supported proxy types.
 *
 * <p>This enum defines the types of proxies that can be configured for HTTP and WebSocket
 * transport layers.
 */
public enum ProxyType {

    /**
     * HTTP/HTTPS proxy.
     *
     * <p>Supports authentication via username/password.
     */
    HTTP,

    /**
     * SOCKS version 4 proxy.
     *
     * <p>Does not support authentication.
     */
    SOCKS4,

    /**
     * SOCKS version 5 proxy.
     *
     * <p>Supports authentication via username/password, but only when using OkHttp-based
     * transport implementations. JDK HttpClient does not support SOCKS5 authentication.
     */
    SOCKS5
}
