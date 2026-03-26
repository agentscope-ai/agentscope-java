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
package io.agentscope.spring.boot.a2a.properties;

import io.agentscope.core.a2a.server.transport.CustomTransportProperties;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot configuration properties for A2A (Agent-to-Agent) RocketMQ integration.
 * Maps configuration from application.properties/yaml with prefix "agentscope.a2a.server.transports.rocketmq".
 */
@ConfigurationProperties(Constants.A2A_ROCKETMQ_SERVER_PREFIX)
public class A2aRocketMQProperties implements CustomTransportProperties {
    /**
     * Whether the RocketMQ server is enabled.
     */
    private boolean enabled = false;

    /**
     * The network endpoint of the RocketMQ service.
     */
    private String rocketMQEndpoint;

    /**
     * The namespace of the RocketMQ service.
     */
    private String rocketMQNamespace;

    /**
     * The standard RocketMQ business topic bound to the Agent, used for receiving task requests and other information.
     */
    private String bizTopic;

    /**
     * The CID used to subscribe to the standard business topic bound to the Agent.
     */
    private String bizConsumerGroup;

    /**
     * The access key for authenticating with the RocketMQ service.
     */
    private String accessKey;

    /**
     * The secret key for authenticating with the RocketMQ service.
     */
    private String secretKey;

    /**
     * The lightweight topic used to receive asynchronous replies.
     */
    private String workAgentResponseTopic;

    /**
     * The consumer group ID used when subscribing to the {@link #workAgentResponseTopic}.
     */
    private String workAgentResponseGroupId;

    /**
     * Default constructor.
     */
    public A2aRocketMQProperties() {}

    /**
     * Gets the RocketMQ endpoint.
     *
     * @return the endpoint URL.
     */
    public String getRocketMQEndpoint() {
        return rocketMQEndpoint;
    }

    /**
     * Sets the RocketMQ endpoint.
     *
     * @param rocketMQEndpoint the endpoint URL.
     */
    public void setRocketMQEndpoint(String rocketMQEndpoint) {
        this.rocketMQEndpoint = rocketMQEndpoint;
    }

    /**
     * Gets the RocketMQ namespace.
     *
     * @return the namespace.
     */
    public String getRocketMQNamespace() {
        return rocketMQNamespace;
    }

    /**
     * Sets the RocketMQ namespace.
     *
     * @param rocketMQNamespace the namespace.
     */
    public void setRocketMQNamespace(String rocketMQNamespace) {
        this.rocketMQNamespace = rocketMQNamespace;
    }

    /**
     * Gets the business topic.
     *
     * @return the topic name.
     */
    public String getBizTopic() {
        return bizTopic;
    }

    /**
     * Sets the business topic.
     *
     * @param bizTopic the topic name.
     */
    public void setBizTopic(String bizTopic) {
        this.bizTopic = bizTopic;
    }

    /**
     * Gets the business consumer group.
     *
     * @return the consumer group ID.
     */
    public String getBizConsumerGroup() {
        return bizConsumerGroup;
    }

    /**
     * Sets the business consumer group.
     *
     * @param bizConsumerGroup the consumer group ID.
     */
    public void setBizConsumerGroup(String bizConsumerGroup) {
        this.bizConsumerGroup = bizConsumerGroup;
    }

    /**
     * Gets the access key.
     *
     * @return the access key.
     */
    public String getAccessKey() {
        return accessKey;
    }

    /**
     * Sets the access key.
     *
     * @param accessKey the access key.
     */
    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    /**
     * Gets the secret key.
     *
     * @return the secret key
     */
    public String getSecretKey() {
        return secretKey;
    }

    /**
     * Sets the secret key.
     *
     * @param secretKey the secret key.
     */
    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    /**
     * Gets the response topic.
     *
     * @return the response topic name.
     */
    public String getWorkAgentResponseTopic() {
        return workAgentResponseTopic;
    }

    /**
     * Sets the response topic.
     *
     * @param workAgentResponseTopic the response topic name.
     */
    public void setWorkAgentResponseTopic(String workAgentResponseTopic) {
        this.workAgentResponseTopic = workAgentResponseTopic;
    }

    /**
     * Gets the response consumer group.
     *
     * @return the consumer group ID.
     */
    public String getWorkAgentResponseGroupId() {
        return workAgentResponseGroupId;
    }

    /**
     * Sets the response consumer group.
     *
     * @param workAgentResponseGroupId the consumer group ID.
     */
    public void setWorkAgentResponseGroupId(String workAgentResponseGroupId) {
        this.workAgentResponseGroupId = workAgentResponseGroupId;
    }

    /**
     * Sets whether the RocketMQ server is enabled.
     * @param enabled true if enabled, false otherwise.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Converts the RocketMQ properties to a transport properties object.
     * @return the transport properties object.
     */
    @Override
    public TransportProperties toTransportProperties() {
        if (StringUtils.isEmpty(rocketMQEndpoint)) {
            throw new IllegalArgumentException("rocketMQEndpoint cannot be empty");
        }
        String[] split = rocketMQEndpoint.split(":");
        if (split.length != 2) {
            throw new IllegalArgumentException("Invalid rocketMQEndpoint format");
        }
        String host = split[0].trim();
        int port = Integer.parseInt(split[1].trim());
        return TransportProperties.builder(RocketMQA2AConstant.ROCKETMQ_PROTOCOL)
                .host(host)
                .port(port)
                .path(rocketMQNamespace + RocketMQA2AConstant.RESOURCE_SPLIT + bizTopic)
                .build();
    }

    /**
     * Sets the deployment properties.
     * @param deploymentProperties the deployment properties.
     */
    @Override
    public void setDeploymentProperties(DeploymentProperties deploymentProperties) {}

    /**
     * Whether the RocketMQ server is enabled.
     * @return true if enabled, false otherwise.
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
