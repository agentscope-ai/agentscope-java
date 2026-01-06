/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter.ollama;

import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges multi-agent conversation messages for Ollama API.
 *
 */
public class OllamaConversationMerger {
    private static final Logger log = LoggerFactory.getLogger(OllamaConversationMerger.class);
    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";

    private final String conversationHistoryPrompt;

    public OllamaConversationMerger(String conversationHistoryPrompt) {
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    public OllamaMessage mergeToMessage(
            List<Msg> msgs,
            Function<Msg, String> nameExtractor,
            Function<List<ContentBlock>, String> toolResultConverter,
            String historyPrompt) {

        StringBuilder textAccumulator = new StringBuilder();
        if (historyPrompt != null && !historyPrompt.isEmpty()) {
            textAccumulator.append(historyPrompt);
        }
        textAccumulator.append(HISTORY_START_TAG).append("\n");

        List<String> images = new ArrayList<>();

        for (Msg msg : msgs) {
            String name = nameExtractor.apply(msg);

            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock) {
                    textAccumulator
                            .append(name)
                            .append(": ")
                            .append(((TextBlock) block).getText())
                            .append("\n");
                } else if (block instanceof ImageBlock) {
                    ImageBlock imageBlock = (ImageBlock) block;
                    if (imageBlock.getSource() instanceof Base64Source) {
                        Base64Source source = (Base64Source) imageBlock.getSource();
                        images.add(source.getData());
                        textAccumulator.append(name).append(": [Image]\n");
                    } else {
                        log.warn(
                                "URL image source not yet supported for Ollama, skipping image"
                                        + " block in merger");
                        textAccumulator.append(name).append(": [Image - processing failed]\n");
                    }
                } else if (block instanceof ToolResultBlock) {
                    // Tool results in history are usually just appended as text
                    ToolResultBlock toolResult = (ToolResultBlock) block;

                    // Simplify: Just append tool result string.
                    Object output = toolResult.getOutput();
                    String resultText =
                            output instanceof String ? (String) output : String.valueOf(output);

                    textAccumulator
                            .append(name)
                            .append(" (")
                            .append(toolResult.getName())
                            .append("): ")
                            .append(resultText)
                            .append("\n");
                }
            }
        }

        textAccumulator.append(HISTORY_END_TAG);

        OllamaMessage message = new OllamaMessage();
        message.setRole("user"); // Merged history is typically treated as user input context
        message.setContent(textAccumulator.toString());
        if (!images.isEmpty()) {
            message.setImages(images);
        }

        return message;
    }
}
