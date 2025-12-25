package io.agentscope.examples.bobatea.consult.config;

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BaiLianRAGConfig {

    @Value("${agentscope.dashscope.access-key-id}")
    private String accessKeyId;

    @Value("${agentscope.dashscope.access-key-secret}")
    private String accessKeySecret;

    @Value("${agentscope.dashscope.workspace-id}")
    private String workspaceId;

    @Value("${agentscope.dashscope.index-id}")
    private String indexId;

    @Bean
    public Knowledge bailianRAGKnowledge() {
        return BailianKnowledge.builder()
                .config(
                        BailianConfig.builder()
                                .accessKeyId(accessKeyId)
                                .accessKeySecret(accessKeySecret)
                                .workspaceId(workspaceId)
                                .indexId(indexId)
                                .build())
                .build();
    }
}
