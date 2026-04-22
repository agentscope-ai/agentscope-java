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

package io.agentscope.examples.bobatea.supervisor.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.mysql.MysqlSession;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.bobatea.supervisor.tools.A2aAgentTools;
import io.agentscope.examples.bobatea.supervisor.utils.MonitoringHook;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

/**
 * SupervisorAgent wrapper that creates a new ReActAgent instance for each request.
 * This ensures complete isolation between requests without maintaining conversation context.
 */
public class SupervisorAgent {

    private static final Logger logger = LoggerFactory.getLogger(SupervisorAgent.class);

    private final Model model;

    private final A2aAgentTools tools;

    private final String sysPrompt;

    private final Path sessionPath;

    @Autowired private DataSource dataSource;

    public SupervisorAgent(Model model, A2aAgentTools tools, String sysPrompt) {
        this.model = model;
        this.tools = tools;
        this.sysPrompt = sysPrompt;
        this.sessionPath =
                Paths.get(
                        System.getProperty("java.io.tmpdir"),
                        ".agentscope",
                        "examples",
                        "sessions");
        logger.info("Session path: {}", sessionPath);
    }

    /**
     * Stream method that handles user messages by creating a new agent for each request.
     *
     * @param msg    the user message
     * @return Flux of Events from the agent
     */
    public Flux<Event> stream(Msg msg, String sessionId, String userId) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        AutoContextConfig autoContextConfig =
                AutoContextConfig.builder().tokenRatio(0.4).lastKeep(10).build();
        // Use AutoContextMemory, support context auto compression
        AutoContextMemory memory = new AutoContextMemory(autoContextConfig, model);
        MysqlSession mysqlSession =
                new MysqlSession(dataSource, System.getenv("DB_NAME"), null, true);
        ReActAgent agent = createAgent(toolkit, memory);
        agent.loadIfExists(mysqlSession, sessionId);
        return agent.stream(msg)
                .doFinally(
                        signalType -> {
                            logger.info(
                                    "Stream terminated with signal: {}, saving session: {}",
                                    signalType,
                                    sessionId);
                            agent.saveTo(mysqlSession, sessionId);
                        });
    }

    /**
     * Create a new ReActAgent instance for the given userId.
     *
     * @return newly created ReActAgent
     */
    private ReActAgent createAgent(Toolkit toolkit, Memory memory) {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("supervisor_agent")
                        .sysPrompt(sysPrompt)
                        .toolkit(toolkit)
                        .hook(new MonitoringHook())
                        .model(model)
                        .memory(memory)
                        .build();
        return agent;
    }
}
