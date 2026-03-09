package io.agentscope.extensions.rocketmq.util;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.agentscope.extensions.rocketmq.config.RocketMQProperties;
import org.apache.rocketmq.a2a.common.RocketMQResponse;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.SessionCredentialsProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.a2a.util.Utils.OBJECT_MAPPER;

public class RocketMQUtil {
    private static final Logger logger = LoggerFactory.getLogger(RocketMQUtil.class);

    private final RocketMQProperties rocketMQProperties;

    public RocketMQUtil(RocketMQProperties rocketMQProperties) {
        this.rocketMQProperties = rocketMQProperties;
        if (!this.checkRocketMQConfigParam()) {
            throw new IllegalArgumentException("RocketMQUtil param is invalid");
        }
    }

    public Producer buildProducer() throws ClientException {
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(rocketMQProperties.getAccessKey(), rocketMQProperties.getSecretKey());
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(rocketMQProperties.getEndpoint())
            .setNamespace(rocketMQProperties.getNamespace())
            .setCredentialProvider(sessionCredentialsProvider)
            .setRequestTimeout(Duration.ofSeconds(15))
            .build();
        final ProducerBuilder builder = provider.newProducerBuilder().setClientConfiguration(clientConfiguration);
        return builder.build();
    }

    public PushConsumer buildConsumer(MessageListener messageListener) throws ClientException {
        if (null == messageListener) {
            logger.error("buildConsumer error, messageListener is null");
            throw new RuntimeException("buildConsumer messageListener is null");
        }
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(rocketMQProperties.getAccessKey(), rocketMQProperties.getSecretKey());
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(rocketMQProperties.getEndpoint())
            .setNamespace(rocketMQProperties.getNamespace())
            .setCredentialProvider(sessionCredentialsProvider)
            .build();
        String tag = "*";
        FilterExpression filterExpression = new FilterExpression(tag, FilterExpressionType.TAG);
        return provider.newPushConsumerBuilder()
            .setClientConfiguration(clientConfiguration)
            .setConsumerGroup(rocketMQProperties.getBizConsumerGroup())
            .setSubscriptionExpressions(Collections.singletonMap(rocketMQProperties.getBizTopic(), filterExpression))
            .setMessageListener(messageListener).build();
    }

    public Message buildMessage(String topic, String liteTopic, RocketMQResponse response) {
        if (StringUtils.isEmpty(topic) || StringUtils.isEmpty(liteTopic) || null == response) {
            logger.error("buildMessage param error topic: {}, liteTopic: {}, response: {}", topic, liteTopic, JSON.toJSONString(response));
            return null;
        }
        String missionJsonStr = JSON.toJSONString(response);
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        final Message message = provider.newMessageBuilder()
            .setTopic(topic)
            .setBody(missionJsonStr.getBytes(StandardCharsets.UTF_8))
            .setLiteTopic(liteTopic)
            .build();
        return message;
    }

    public boolean checkRocketMQConfigParam() {
        if (null == rocketMQProperties) {
            logger.warn("rocketMQProperties is null");
            return false;
        }
        boolean result = true;
        if (StringUtils.isEmpty(rocketMQProperties.getEndpoint())) {
            logger.warn("rocketmq endpoint is empty, please config it");
            result = false;
        }
        if (StringUtils.isEmpty(rocketMQProperties.getBizTopic())) {
            logger.warn("rocketmq bizTopic is empty, please config it");
            result = false;
        }
        if (StringUtils.isEmpty(rocketMQProperties.getBizConsumerGroup())) {
            logger.warn("rocketmq bizConsumerGroup is empty, please config it");
            result = false;
        }
        return result;
    }

    public static String toJsonString(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException(ex);
        }
    }
}
