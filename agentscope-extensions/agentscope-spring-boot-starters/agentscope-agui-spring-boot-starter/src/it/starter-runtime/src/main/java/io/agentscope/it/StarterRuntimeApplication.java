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
package io.agentscope.it;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.util.Collections;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

/** Checks the consumer classpath under Spring Boot's nested-JAR class loader. */
@SpringBootApplication
public class StarterRuntimeApplication {

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        boolean aguiExpected = !"base".equals(scenario);
        assertResourceCount("io/agentscope/core/ReActAgent.class", 1);
        assertResourceCount(
                "io/agentscope/core/agui/adapter/AguiAgentAdapter.class", aguiExpected ? 1 : 0);
        assertResourceCount("META-INF/maven/io.agentscope/agentscope/pom.properties", 0);
        assertResourceCount("io/agentscope/extensions/model/dashscope/DashScopeChatModel.class", 0);

        try (var context =
                SpringApplication.run(
                        StarterRuntimeApplication.class,
                        "--spring.main.web-application-type=none",
                        "--agentscope.agent.enabled=true")) {
            context.getBean(Memory.class);
            context.getBean(Toolkit.class);
            context.getBean(ReActAgent.class);
            if ("agui-starter".equals(scenario)) {
                Class<?> registry =
                        Class.forName("io.agentscope.core.agui.registry.AguiAgentRegistry");
                context.getBean(registry);
            }
        }
        System.out.println("Starter runtime classpath passed: " + scenario);
    }

    private static void assertResourceCount(String resource, int expected) throws Exception {
        var resources =
                Collections.list(
                        Thread.currentThread().getContextClassLoader().getResources(resource));
        if (resources.size() != expected) {
            throw new IllegalStateException(
                    "Expected " + expected + " resources for " + resource + ", found " + resources);
        }
    }

    @Bean
    Model model() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return "offline-test-model";
            }
        };
    }
}
