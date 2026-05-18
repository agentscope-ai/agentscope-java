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
package io.agentscope.spring.boot.properties;

import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.spring.boot.transport.TransportProviderType;
import java.time.Duration;

/**
 * Properties for HTTP transport configuration that supports Spring Boot property binding.
 * This class acts as a bridge between Spring Boot's configuration properties and
 * the immutable HttpTransportConfig.
 */
public class HttpTransportProperties {

    private boolean enabled = true;

    private TransportProviderType.HttpType type = TransportProviderType.HttpType.JDK;

    private Duration connectTimeout = HttpTransportConfig.DEFAULT_CONNECT_TIMEOUT;

    private Duration readTimeout = HttpTransportConfig.DEFAULT_READ_TIMEOUT;

    private Duration writeTimeout = HttpTransportConfig.DEFAULT_WRITE_TIMEOUT;

    private int maxIdleConnections = 5;

    private Duration keepAliveDuration = Duration.ofMinutes(5);

    private int maxConnections = 500;

    private Duration maxIdleTime = Duration.ofSeconds(45);

    private Duration evictInBackground = Duration.ofSeconds(30);

    private boolean ignoreSsl = false;

    private ProxyProperties proxy;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public TransportProviderType.HttpType getType() {
        return type;
    }

    public void setType(TransportProviderType.HttpType type) {
        this.type = type;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getWriteTimeout() {
        return writeTimeout;
    }

    public void setWriteTimeout(Duration writeTimeout) {
        this.writeTimeout = writeTimeout;
    }

    public int getMaxIdleConnections() {
        return maxIdleConnections;
    }

    public void setMaxIdleConnections(int maxIdleConnections) {
        this.maxIdleConnections = maxIdleConnections;
    }

    public Duration getKeepAliveDuration() {
        return keepAliveDuration;
    }

    public void setKeepAliveDuration(Duration keepAliveDuration) {
        this.keepAliveDuration = keepAliveDuration;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public Duration getMaxIdleTime() {
        return maxIdleTime;
    }

    public void setMaxIdleTime(Duration maxIdleTime) {
        this.maxIdleTime = maxIdleTime;
    }

    public Duration getEvictInBackground() {
        return evictInBackground;
    }

    public void setEvictInBackground(Duration evictInBackground) {
        this.evictInBackground = evictInBackground;
    }

    public boolean isIgnoreSsl() {
        return ignoreSsl;
    }

    public void setIgnoreSsl(boolean ignoreSsl) {
        this.ignoreSsl = ignoreSsl;
    }

    public ProxyProperties getProxy() {
        return proxy;
    }

    public void setProxy(ProxyProperties proxy) {
        this.proxy = proxy;
    }

    /**
     * Convert this properties object to HttpTransportConfig.
     *
     * @return a new HttpTransportConfig instance based on these properties
     */
    public HttpTransportConfig toTransportConfig() {
        return HttpTransportConfig.builder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .writeTimeout(writeTimeout)
                .maxIdleConnections(maxIdleConnections)
                .keepAliveDuration(keepAliveDuration)
                .maxConnections(maxConnections)
                .maxIdleTime(maxIdleTime)
                .evictInBackground(evictInBackground)
                .ignoreSsl(ignoreSsl)
                .proxy(proxy != null ? proxy.toProxyConfig() : null)
                .build();
    }
}
