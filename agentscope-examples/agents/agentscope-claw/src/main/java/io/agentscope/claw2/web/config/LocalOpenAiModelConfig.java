
package io.agentscope.claw2.web.config;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalOpenAiModelConfig.LocalOpenAiProperties.class)
public class LocalOpenAiModelConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalOpenAiModelConfig.class);

    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnProperty(prefix = "claw.local-openai", name = "enabled", havingValue = "true")
    public Model localOpenAiModel(LocalOpenAiProperties properties) {
        requireText(properties.apiKey(), "claw.local-openai.api-key");
        requireText(properties.baseUrl(), "claw.local-openai.base-url");
        requireText(properties.endpointPath(), "claw.local-openai.endpoint-path");
        requireText(properties.modelName(), "claw.local-openai.model-name");

        Duration connectTimeout =
                properties.connectTimeout() != null
                        ? properties.connectTimeout()
                        : Duration.ofSeconds(10);
        Duration readTimeout =
                properties.readTimeout() != null ? properties.readTimeout() : Duration.ofSeconds(120);

        log.info(
                "Building local OpenAI-compatible model: baseUrl={}, endpointPath={}, model={}, stream={}",
                properties.baseUrl(),
                properties.endpointPath(),
                properties.modelName(),
                properties.stream());

        GenerateOptions.Builder options =
                GenerateOptions.builder()
                        .stream(properties.stream())
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(readTimeout)
                                        .maxAttempts(1)
                                        .build());

        if (properties.maxTokens() != null && properties.maxTokens() > 0) {
            options.maxTokens(properties.maxTokens());
        }
        if (properties.temperature() != null) {
            options.temperature(properties.temperature());
        }

        HttpTransportConfig transportConfig =
                HttpTransportConfig.builder()
                        .connectTimeout(connectTimeout)
                        .readTimeout(readTimeout)
                        .build();

        HttpClient httpClient =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(connectTimeout)
                        .build();

        HttpTransport transport =
                JdkHttpTransport.builder().client(httpClient).config(transportConfig).build();

        return OpenAIChatModel.builder()
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .endpointPath(properties.endpointPath())
                .modelName(properties.modelName())
                .stream(properties.stream())
                .generateOptions(options.build())
                .httpTransport(transport)
                .build();
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
    }

    @ConfigurationProperties(prefix = "claw.local-openai")
    public record LocalOpenAiProperties(
            boolean enabled,
            String apiKey,
            String baseUrl,
            String endpointPath,
            String modelName,
            boolean stream,
            Integer maxTokens,
            Double temperature,
            Duration connectTimeout,
            Duration readTimeout) {}
}