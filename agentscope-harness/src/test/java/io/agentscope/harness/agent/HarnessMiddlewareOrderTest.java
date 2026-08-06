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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.middleware.InboxMiddleware;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessMiddlewareOrderTest {

    @TempDir Path workspace;

    @Test
    void lowerPriorityCustomMiddlewareRunsAfterDefaultOrderHarnessMiddlewares() throws Exception {
        Files.createDirectories(workspace);
        MiddlewareBase lowerPriority =
                new MiddlewareBase() {
                    @Override
                    public int order() {
                        return 0;
                    }
                };

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("ordered")
                        .model(new TestModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .middleware(lowerPriority)
                        .build();

        List<MiddlewareBase> middlewares = agent.getDelegate().getMiddlewares();
        assertTrue(
                indexOfType(middlewares, InboxMiddleware.class)
                        < middlewares.indexOf(lowerPriority));
        assertSame(lowerPriority, middlewares.get(middlewares.size() - 1));
    }

    private static int indexOfType(List<MiddlewareBase> middlewares, Class<?> type) {
        for (int i = 0; i < middlewares.size(); i++) {
            if (type.isInstance(middlewares.get(i))) {
                return i;
            }
        }
        throw new AssertionError("Middleware not found: " + type.getSimpleName());
    }

    private static final class TestModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "test-model";
        }
    }
}
