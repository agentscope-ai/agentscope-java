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
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.pipeline.SequentialPipeline;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.quickstart.util.MsgUtils;
import java.time.Duration;

/**
 * SequentialPipelineExample - Content processing workflow demonstration.
 */
public class SequentialPipelineExample {

    // Sample article for demonstration
    private static final String SAMPLE_ARTICLE =
            "Artificial Intelligence has revolutionized the technology industry in recent years."
                + " Machine learning algorithms now power everything from recommendation systems to"
                + " autonomous vehicles. While AI brings tremendous opportunities for innovation"
                + " and efficiency, it also raises important questions about ethics, privacy, and"
                + " job displacement. As we move forward, finding the right balance between"
                + " technological advancement and societal well-being will be crucial for"
                + " sustainable development.";

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Sequential Pipeline Example",
                "This example demonstrates a content processing workflow using"
                        + " SequentialPipeline.\n"
                        + "An English article will be processed through 3 sequential steps:\n"
                        + "  1. Translation (English → Chinese)\n"
                        + "  2. Summarization (Generate concise summary)\n"
                        + "  3. Sentiment Analysis (Analyze emotional tone)\n\n"
                        + "Watch how each agent's output becomes the next agent's input!");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        System.out.println("Setting up the pipeline...\n");

        // Create three specialized agents
        ReActAgent translator = createTranslator(apiKey);
        ReActAgent summarizer = createSummarizer(apiKey);
        ReActAgent sentimentAnalyzer = createSentimentAnalyzer(apiKey);

        // Build sequential pipeline using Builder pattern
        SequentialPipeline pipeline =
                SequentialPipeline.builder()
                        .addAgent(translator)
                        .addAgent(summarizer)
                        .addAgent(sentimentAnalyzer)
                        .build();

        System.out.println("Pipeline created with 3 agents:");
        System.out.println("  [1] Translator → [2] Summarizer → [3] Sentiment Analyzer\n");

        // Create input message with sample article
        Msg inputMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(SAMPLE_ARTICLE).build())
                        .build();

        // Display original article
        printSeparator();
        System.out.println("ORIGINAL ARTICLE (English):");
        printSeparator();
        System.out.println(SAMPLE_ARTICLE);
        System.out.println();

        // Execute pipeline and display progress
        System.out.println("Executing pipeline...\n");

        long startTime = System.currentTimeMillis();

        // Execute the pipeline with timeout
        Msg result = pipeline.execute(inputMsg).block(Duration.ofMinutes(3));

        long executionTime = System.currentTimeMillis() - startTime;

        // Display final result
        printSeparator();
        System.out.println("FINAL RESULT:");
        printSeparator();
        if (result != null) {
            String resultText = MsgUtils.getTextContent(result);
            System.out.println(resultText);
        } else {
            System.out.println("[No result returned]");
        }
        System.out.println();

        // Display execution summary
        printSeparator();
        System.out.println("EXECUTION SUMMARY:");
        printSeparator();
        System.out.println("Total execution time: " + executionTime + "ms");
        System.out.println("Pipeline stages: 3");
        System.out.println("  Step 1: Translation completed");
        System.out.println("  Step 2: Summarization completed");
        System.out.println("  Step 3: Sentiment Analysis completed");
        System.out.println();

        System.out.println(
                "This demonstrates how SequentialPipeline chains agents together,\n"
                        + "with each agent processing the previous agent's output.\n");
    }

    private static ReActAgent createTranslator(String apiKey) {
        return ReActAgent.builder()
                .name("Translator")
                .sysPrompt(
                        "You are a professional translator. "
                                + "Translate the given English text to Chinese accurately. "
                                + "Preserve the original meaning and tone. "
                                + "Only output the translated text, no explanations.")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .enableThinking(false)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .memory(new InMemoryMemory())
                .toolkit(new Toolkit())
                .build();
    }

    private static ReActAgent createSummarizer(String apiKey) {
        return ReActAgent.builder()
                .name("Summarizer")
                .sysPrompt(
                        "You are a professional content summarizer. "
                                + "Generate a concise summary of the given text in 2-3 sentences. "
                                + "Capture the main points and key message. "
                                + "Keep the summary in the same language as the input. "
                                + "Only output the summary, no additional comments.")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .enableThinking(false)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .memory(new InMemoryMemory())
                .toolkit(new Toolkit())
                .build();
    }

    private static ReActAgent createSentimentAnalyzer(String apiKey) {
        return ReActAgent.builder()
                .name("SentimentAnalyzer")
                .sysPrompt(
                        "You are a sentiment analysis expert. Analyze the emotional tone of the"
                            + " given text. Classify the sentiment as: Positive, Negative, Neutral,"
                            + " or Mixed. Explain the reasoning behind your classification in 1-2"
                            + " sentences. Format your response as:\n"
                            + "Sentiment: [classification]\n"
                            + "Reasoning: [explanation]\n"
                            + "Summary: [repeat the input text]")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .enableThinking(false)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .memory(new InMemoryMemory())
                .toolkit(new Toolkit())
                .build();
    }

    private static void printSeparator() {
        System.out.println("=".repeat(70));
    }
}
