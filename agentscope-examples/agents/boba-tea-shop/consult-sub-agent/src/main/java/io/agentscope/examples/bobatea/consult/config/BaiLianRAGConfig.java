/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
