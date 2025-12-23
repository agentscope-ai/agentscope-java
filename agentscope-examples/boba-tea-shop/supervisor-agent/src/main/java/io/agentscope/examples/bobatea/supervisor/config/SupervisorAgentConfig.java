package io.agentscope.examples.bobatea.supervisor.config;

import io.agentscope.core.model.Model;
import io.agentscope.examples.bobatea.supervisor.agent.SupervisorAgent;
import io.agentscope.examples.bobatea.supervisor.tools.A2aAgentTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SupervisorAgentConfig {

    private static final Logger logger = LoggerFactory.getLogger(SupervisorAgentConfig.class);

    @Autowired private SupervisorAgentPromptConfig promptConfig;

    @Bean
    public SupervisorAgent supervisorAgent(Model model, A2aAgentTools tools) {
        logger.info("SupervisorAgent initialized - creates new agent for each request");
        return new SupervisorAgent(model, tools, promptConfig.getSupervisorAgentInstruction());
    }
}
