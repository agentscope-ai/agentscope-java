package io.agentscope.examples.bobatea.supervisor.config;

import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.extensions.nacos.a2a.discovery.NacosAgentCardResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author xiweng.yy
 */
@Configuration
public class A2aAgentConfiguration {

    @Bean
    public A2aAgent consultAgent(AiService a2aService) {
        return A2aAgent.builder()
                .name("consult_agent")
                .agentCardResolver(new NacosAgentCardResolver(a2aService))
                .build();
    }

    @Bean
    public A2aAgent businessAgent(AiService a2aService) {
        return A2aAgent.builder()
                .name("business_agent")
                .agentCardResolver(new NacosAgentCardResolver(a2aService))
                .build();
    }
}
