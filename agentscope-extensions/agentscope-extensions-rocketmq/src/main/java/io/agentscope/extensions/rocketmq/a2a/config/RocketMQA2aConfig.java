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
package io.agentscope.extensions.rocketmq.a2a.config;

/**
 * Configuration class for RocketMQ A2A (Agent-to-Agent) communication.
 */
public class RocketMQA2aConfig {
    /**
     * The network endpoint of the RocketMQ service.
     */
    private String rocketMQEndpoint;

    /**
     * The namespace used for logical isolation of RocketMQ resources.
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
     * Constructs a RocketMQA2aConfig with all fields.
     *
     * @param rocketMQEndpoint the RocketMQ service endpoint.
     * @param rocketMQNamespace the namespace for resource isolation.
     * @param bizTopic the business topic for receiving requests.
     * @param bizConsumerGroup the consumer group for the business topic.
     * @param accessKey the authentication access key.
     * @param secretKey the authentication secret key.
     * @param workAgentResponseTopic the topic for async responses.
     * @param workAgentResponseGroupId the consumer group for response topic.
     */
    public RocketMQA2aConfig(
            String rocketMQEndpoint,
            String rocketMQNamespace,
            String bizTopic,
            String bizConsumerGroup,
            String accessKey,
            String secretKey,
            String workAgentResponseTopic,
            String workAgentResponseGroupId) {
        this.rocketMQEndpoint = rocketMQEndpoint;
        this.rocketMQNamespace = rocketMQNamespace;
        this.bizTopic = bizTopic;
        this.bizConsumerGroup = bizConsumerGroup;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.workAgentResponseTopic = workAgentResponseTopic;
        this.workAgentResponseGroupId = workAgentResponseGroupId;
    }

    /**
     * Default constructor.
     */
    public RocketMQA2aConfig() {}

    /**
     * Gets the RocketMQ service endpoint.
     *
     * @return the endpoint.
     */
    public String getRocketMQEndpoint() {
        return rocketMQEndpoint;
    }

    /**
     * Sets the RocketMQ service endpoint.
     *
     * @param rocketMQEndpoint the endpoint to set.
     */
    public void setRocketMQEndpoint(String rocketMQEndpoint) {
        this.rocketMQEndpoint = rocketMQEndpoint;
    }

    /**
     * Gets the namespace for resource isolation.
     *
     * @return the namespace.
     */
    public String getRocketMQNamespace() {
        return rocketMQNamespace;
    }

    /**
     * Sets the namespace for resource isolation.
     *
     * @param rocketMQNamespace the namespace to set.
     */
    public void setRocketMQNamespace(String rocketMQNamespace) {
        this.rocketMQNamespace = rocketMQNamespace;
    }

    /**
     * Gets the business topic for receiving requests.
     *
     * @return the business topic.
     */
    public String getBizTopic() {
        return bizTopic;
    }

    /**
     * Sets the business topic for receiving requests.
     *
     * @param bizTopic the topic to set.
     */
    public void setBizTopic(String bizTopic) {
        this.bizTopic = bizTopic;
    }

    /**
     * Gets the consumer group for the business topic.
     *
     * @return the consumer group.
     */
    public String getBizConsumerGroup() {
        return bizConsumerGroup;
    }

    /**
     * Sets the consumer group for the business topic.
     *
     * @param bizConsumerGroup the consumer group to set.
     */
    public void setBizConsumerGroup(String bizConsumerGroup) {
        this.bizConsumerGroup = bizConsumerGroup;
    }

    /**
     * Gets the authentication access key.
     *
     * @return the access key.
     */
    public String getAccessKey() {
        return accessKey;
    }

    /**
     * Sets the authentication access key.
     *
     * @param accessKey the access key to set.
     */
    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    /**
     * Gets the authentication secret key.
     *
     * @return the secret key.
     */
    public String getSecretKey() {
        return secretKey;
    }

    /**
     * Sets the authentication secret key.
     *
     * @param secretKey the secret key to set.
     */
    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    /**
     * Gets the topic for receiving async responses.
     *
     * @return the response topic.
     */
    public String getWorkAgentResponseTopic() {
        return workAgentResponseTopic;
    }

    /**
     * Sets the topic for receiving async responses.
     *
     * @param workAgentResponseTopic the topic to set.
     */
    public void setWorkAgentResponseTopic(String workAgentResponseTopic) {
        this.workAgentResponseTopic = workAgentResponseTopic;
    }

    /**
     * Gets the consumer group for the response topic.
     *
     * @return the response consumer group.
     */
    public String getWorkAgentResponseGroupId() {
        return workAgentResponseGroupId;
    }

    /**
     * Sets the consumer group for the response topic.
     *
     * @param workAgentResponseGroupId the consumer group to set.
     */
    public void setWorkAgentResponseGroupId(String workAgentResponseGroupId) {
        this.workAgentResponseGroupId = workAgentResponseGroupId;
    }

    /**
     * Returns a new Builder instance.
     *
     * @return the builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing RocketMQA2aConfig instances.
     */
    public static class Builder {
        private String rocketMQEndpoint;
        private String rocketMQNamespace;
        private String bizTopic;
        private String bizConsumerGroup;
        private String accessKey;
        private String secretKey;
        private String workAgentResponseTopic;
        private String workAgentResponseGroupId;

        /**
         * Sets the RocketMQ endpoint.
         *
         * @param rocketMQEndpoint the endpoint.
         * @return this builder.
         */
        public Builder rocketMQEndpoint(String rocketMQEndpoint) {
            this.rocketMQEndpoint = rocketMQEndpoint;
            return this;
        }

        /**
         * Sets the namespace.
         *
         * @param rocketMQNamespace the namespace.
         * @return this builder.
         */
        public Builder rocketMQNamespace(String rocketMQNamespace) {
            this.rocketMQNamespace = rocketMQNamespace;
            return this;
        }

        /**
         * Sets the business topic.
         *
         * @param bizTopic the topic.
         * @return this builder.
         */
        public Builder bizTopic(String bizTopic) {
            this.bizTopic = bizTopic;
            return this;
        }

        /**
         * Sets the business consumer group.
         *
         * @param bizConsumerGroup the consumer group.
         * @return this builder.
         */
        public Builder bizConsumerGroup(String bizConsumerGroup) {
            this.bizConsumerGroup = bizConsumerGroup;
            return this;
        }

        /**
         * Sets the access key.
         *
         * @param accessKey the access key.
         * @return this builder.
         */
        public Builder accessKey(String accessKey) {
            this.accessKey = accessKey;
            return this;
        }

        /**
         * Sets the secret key.
         *
         * @param secretKey the secret key.
         * @return this builder.
         */
        public Builder secretKey(String secretKey) {
            this.secretKey = secretKey;
            return this;
        }

        /**
         * Sets the response topic.
         *
         * @param workAgentResponseTopic the response topic.
         * @return this builder.
         */
        public Builder workAgentResponseTopic(String workAgentResponseTopic) {
            this.workAgentResponseTopic = workAgentResponseTopic;
            return this;
        }

        /**
         * Sets the response consumer group.
         *
         * @param workAgentResponseGroupId the consumer group.
         * @return this builder.
         */
        public Builder workAgentResponseGroupId(String workAgentResponseGroupId) {
            this.workAgentResponseGroupId = workAgentResponseGroupId;
            return this;
        }

        /**
         * Builds and returns the RocketMQA2aConfig instance.
         *
         * @return the constructed config.
         */
        public RocketMQA2aConfig build() {
            return new RocketMQA2aConfig(
                    rocketMQEndpoint,
                    rocketMQNamespace,
                    bizTopic,
                    bizConsumerGroup,
                    accessKey,
                    secretKey,
                    workAgentResponseTopic,
                    workAgentResponseGroupId);
        }
    }

    @Override
    public String toString() {
        return "RocketMQA2aConfig{"
                + "rocketMQEndpoint='"
                + rocketMQEndpoint
                + '\''
                + ", rocketMQNamespace='"
                + rocketMQNamespace
                + '\''
                + ", bizTopic='"
                + bizTopic
                + '\''
                + ", bizConsumerGroup='"
                + bizConsumerGroup
                + '\''
                + ", accessKey='"
                + accessKey
                + '\''
                + ", secretKey='"
                + secretKey
                + '\''
                + ", workAgentResponseTopic='"
                + workAgentResponseTopic
                + '\''
                + ", workAgentResponseGroupId='"
                + workAgentResponseGroupId
                + '\''
                + '}';
    }
}
