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
package io.agentscope.examples.chat;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    ChatSessions sessions(
            @Value("${chat.workspace}") String workspace,
            @Value("${chat.model}") String modelName,
            @Value("${chat.api-key}") String key)
            throws IOException {
        Model model;
        if ("demo".equals(modelName)) model = new DemoChatModel();
        else {
            if (key.isBlank())
                throw new IllegalArgumentException(
                        "DASHSCOPE_API_KEY is required for a real model");
            model =
                    DashScopeChatModel.builder().apiKey(key).modelName(modelName).stream(true)
                            .build();
        }
        return new ChatSessions(Files.createDirectories(Path.of(workspace)), model);
    }
}
