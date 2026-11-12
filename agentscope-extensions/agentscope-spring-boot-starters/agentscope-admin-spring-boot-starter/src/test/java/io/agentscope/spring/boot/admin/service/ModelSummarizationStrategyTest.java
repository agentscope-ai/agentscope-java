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
package io.agentscope.spring.boot.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.FileIdSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ModelSummarizationStrategyTest {

    @Test
    void documentOnlyMessageReachesTheSummaryPrompt() {
        CapturingModel model = new CapturingModel();
        Msg documentOnly =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                DocumentBlock.builder()
                                        .name("report.pdf")
                                        .mimeType("application/pdf")
                                        .source(new FileIdSource("dashscope", "file-42"))
                                        .build())
                        .build();

        new ModelSummarizationStrategy().summarize(model, null, List.of(documentOnly)).block();

        assertThat(model.prompt.get())
                .contains(
                        "[document: report.pdf, application/pdf, provider file id: file-42"
                                + " (dashscope)]");
    }

    @Test
    void mixedMessageKeepsBodyAndDocumentReference() {
        CapturingModel model = new CapturingModel();
        Msg mixed =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder().text("Summarise this").build(),
                                DocumentBlock.builder()
                                        .name("report.pdf")
                                        .mimeType("application/pdf")
                                        .source(new FileIdSource("dashscope", "file-42"))
                                        .build())
                        .build();

        new ModelSummarizationStrategy().summarize(model, null, List.of(mixed)).block();

        assertThat(model.prompt.get())
                .contains("Summarise this")
                .contains("[document: report.pdf, application/pdf");
    }

    private static final class CapturingModel implements Model {

        private final AtomicReference<String> prompt = new AtomicReference<>("");

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            prompt.set(
                    messages.stream()
                            .map(Msg::getTextContent)
                            .reduce("", (left, right) -> left + "\n" + right));
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            TextBlock.builder().text("summary").build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "capturing-model";
        }
    }
}
