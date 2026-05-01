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
package io.agentscope.examples.advanced;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.user.UserAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.bailian.BailianLongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import java.util.Map;

/**
 * BailianMemoryExample - Demonstrates <a href="https://help.aliyun.com/zh/model-studio/memory-library">Bailian Memory Library</a>
 * long-term memory using Bailian backend.
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>Alibaba Cloud DashScope API key</li>
 *   <li>Bailian Long Term Memory service access</li>
 * </ul>
 *
 * <p><b>Environment Configuration:</b>
 * <pre>
 * export DASHSCOPE_API_KEY=sk-xxxx
 * export BAILIAN_USER_ID=your_user_id
 * </pre>
 *
 * <p><b>Optional Configuration:</b>
 * <pre>
 * export BAILIAN_MEMORY_LIBRARY_ID=your_library_id
 * export BAILIAN_PROJECT_ID=your_project_id
 * export BAILIAN_PROFILE_SCHEMA=your_profile_schema
 * </pre>
 *
 * <p>This example demonstrates:
 * <ul>
 *   <li>Creating an agent with Bailian long-term memory</li>
 *   <li>Using STATIC_CONTROL mode for memory operations</li>
 *   <li>Interactive chat with persistent memory</li>
 * </ul>
 */
public class BailianMemoryExample {

    public static void main(String[] args) throws Exception {
        String description =
                """
                This example demonstrates using Bailian long-term memory:
                - Creating an agent with Bailian long-term memory
                - Using STATIC_CONTROL mode for memory operations
                - Interactive chat with persistent memory

                Bailian Memory Prompt Example:
                [User (USER)]: I am Mike, and I drink water on time at 9 a.m. every morning.
                [Assistant (ASSISTANT)]: Hi Mike! That’s a great habit—staying hydrated first thing in the morning helps kickstart your metabolism, supports cognitive function, and sets a positive tone for the day.

                [User (USER)]: Who am I and what I need to do at 9 a.m. every morning?
                [Assistant (ASSISTANT)]: You are **Mike**, and you need to **drink water** at 9 a.m. every morning.
                """;
        ExampleUtils.printWelcome("Bailian Memory Example", description);

        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Set user id
        String userId = System.getenv("BAILIAN_USER_ID");
        if (userId == null || userId.isBlank()) {
            userId = "user-001";
        }

        BailianLongTermMemory.Builder memoryBuilder =
                BailianLongTermMemory.builder()
                        .apiKey(apiKey)
                        .userId(userId)
                        .metadata(Map.of("location_name", "Beijing"));

        // Set memory library id
        String memoryLibraryId = System.getenv("BAILIAN_MEMORY_LIBRARY_ID");
        if (memoryLibraryId != null && !memoryLibraryId.isBlank()) {
            memoryBuilder.memoryLibraryId(memoryLibraryId);
        }

        // Set project id
        String projectId = System.getenv("BAILIAN_PROJECT_ID");
        if (projectId != null && !projectId.isBlank()) {
            memoryBuilder.projectId(projectId);
        }

        // Set profile schema
        String profileSchema = System.getenv("BAILIAN_PROFILE_SCHEMA");
        if (profileSchema != null && !profileSchema.isBlank()) {
            memoryBuilder.profileSchema(profileSchema);
        }

        BailianLongTermMemory longTermMemory = memoryBuilder.build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .build())
                        .longTermMemory(longTermMemory)
                        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                        .build();

        UserAgent userAgent = UserAgent.builder().name("User").build();

        Msg msg = null;
        while (true) {
            msg = userAgent.call(msg).block();
            if (msg.getTextContent().equals("exit")) {
                break;
            }
            msg = agent.call(msg).block();
        }
    }
}
