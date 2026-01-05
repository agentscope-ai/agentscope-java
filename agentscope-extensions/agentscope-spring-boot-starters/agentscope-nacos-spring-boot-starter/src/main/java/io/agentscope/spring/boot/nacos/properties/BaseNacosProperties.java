/*
 * Copyright 2024-2026 the original author or authors.
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

package io.agentscope.spring.boot.nacos.properties;

import com.alibaba.nacos.api.PropertyKeyConst;
import java.util.Properties;

/**
 * Properties for Nacos Client connecting to Nacos Server.
 *
 * <p>This class is used to configure the Nacos Server basic properties, like {@link #serverAddr}, {@link #namespace}, {@link #username}, {@link #password} and more.
 *
 * <p>The {@link #properties} is used to configure the Nacos Server advanced properties. Keys of properties are from {@link PropertyKeyConst}
 */
public class BaseNacosProperties {

    private static final String DEFAULT_ADDRESS = "127.0.0.1:8848";

    private static final String DEFAULT_NAMESPACE = "public";

    /**
     * The Server Address of Nacos Server.
     *
     * <p>Default is {@code 127.0.0.1:8848}.
     *
     * <p>The format is {@code [http://|https://]domain[:port]}. If schema is not specified, http:// will be used. And
     * if port is not specified, 8848 will be used.
     */
    String serverAddr;

    /**
     * The Namespace of Nacos Server.
     *
     * <p>Default is {@code public}.
     *
     * <p>This property should configure the namespaceId of Nacos Server, not name of namespace.
     */
    String namespace;

    /**
     * The Username of Nacos Server.
     *
     * <p>This property should configure the username of Nacos Server.
     *
     * <p>This property should be configured when Nacos Server enabled authentication. And it needs to be used in
     * conjunction with {@link #password}.
     */
    String username;

    /**
     * The Password of Nacos Server.
     *
     * <p>This property should configure the password of Nacos Server.
     *
     * <p>This property should be configured when Nacos Server enabled authentication. And it needs to be used in
     * conjunction with {@link #username}.
     */
    String password;

    /**
     * The AccessKey of Nacos Server.
     *
     * <p>This property should configure the accessKey of Nacos Server from cloud provider, such as aliyun.
     *
     * <p>This property should be configured when Nacos Server provided by cloud provider with enabled authentication.
     * And it needs to be used in conjunction with {@link #secretKey}.
     */
    String accessKey;

    /**
     * The SecretKey of Nacos Server.
     *
     * <p>This property should configure the secretKey of Nacos Server from cloud provider, such as aliyun.
     *
     * <p>This property should be configured when Nacos Server provided by cloud provider with enabled authentication.
     * And it needs to be used in conjunction with {@link #accessKey}.
     */
    String secretKey;

    /**
     * Some advanced properties of Nacos Server and Nacos Client.
     *
     * <p>Keys of properties are from {@link PropertyKeyConst}.
     *
     * <p>Some properties have been defined in this {@link BaseNacosProperties} which will overwrite value if
     * duplicate set in properties:
     * <ul>
     *     <li>{@link PropertyKeyConst#SERVER_ADDR} will be overwrite by {@link #serverAddr} if {@link #serverAddr} not null</li>
     *     <li>{@link PropertyKeyConst#NAMESPACE} will be overwrite by {@link #namespace} if {@link #namespace} not null</li>
     *     <li>{@link PropertyKeyConst#USERNAME} will be overwrite by {@link #username} if {@link #username} not null</li>
     *     <li>{@link PropertyKeyConst#PASSWORD} will be overwrite by {@link #password} if {@link #password} not null</li>
     *     <li>{@link PropertyKeyConst#ACCESS_KEY} will be overwrite by {@link #accessKey} if {@link #accessKey} not null</li>
     *     <li>{@link PropertyKeyConst#SECRET_KEY} will be overwrite by {@link #secretKey} if {@link #secretKey} not null</li>
     * </ul>
     */
    Properties properties;

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public Properties getProperties() {
        return properties;
    }

    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    /**
     * Build a {@link Properties} instance for configuring the Nacos client.
     *
     * <p>This method merges the advanced {@link #properties} with the basic fields such as
     * {@link #serverAddr}, {@link #namespace}, {@link #username}, {@link #password},
     * {@link #accessKey}, and {@link #secretKey}. If both the corresponding field and any
     * pre-configured property are {@code null}, the default values
     * {@link #DEFAULT_ADDRESS} and {@link #DEFAULT_NAMESPACE} are applied to the returned
     * {@link Properties} instance without modifying this object's state.
     *
     * @return a {@link Properties} instance containing all resolved Nacos configuration
     */
    public Properties getNacosProperties() {
        Properties result = new Properties();
        if (null != properties && !properties.isEmpty()) {
            result.putAll(properties);
        }
        // Resolve server address: prefer explicit field, otherwise use default if none present
        if (null != serverAddr) {
            result.put(PropertyKeyConst.SERVER_ADDR, serverAddr);
        } else {
            result.putIfAbsent(PropertyKeyConst.SERVER_ADDR, DEFAULT_ADDRESS);
        }
        // Resolve namespace: prefer explicit field, otherwise use default if none present
        if (null != namespace) {
            result.put(PropertyKeyConst.NAMESPACE, namespace);
        } else {
            result.putIfAbsent(PropertyKeyConst.NAMESPACE, DEFAULT_NAMESPACE);
        }
        if (null != username) {
            result.put(PropertyKeyConst.USERNAME, username);
        }
        if (null != password) {
            result.put(PropertyKeyConst.PASSWORD, password);
        }
        if (null != accessKey) {
            result.put(PropertyKeyConst.ACCESS_KEY, accessKey);
        }
        if (null != secretKey) {
            result.put(PropertyKeyConst.SECRET_KEY, secretKey);
        }
        return result;
    }
}
