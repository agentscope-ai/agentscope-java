package io.agentscope.extensions.rocketmq.config;

public class RocketMQProperties {
    private String endpoint;
    private String namespace;
    private String bizTopic;
    private String bizConsumerGroup;
    private String accessKey;
    private String secretKey;

    public RocketMQProperties() {}

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getBizTopic() {
        return bizTopic;
    }

    public void setBizTopic(String bizTopic) {
        this.bizTopic = bizTopic;
    }

    public String getBizConsumerGroup() {
        return bizConsumerGroup;
    }

    public void setBizConsumerGroup(String bizConsumerGroup) {
        this.bizConsumerGroup = bizConsumerGroup;
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
}
