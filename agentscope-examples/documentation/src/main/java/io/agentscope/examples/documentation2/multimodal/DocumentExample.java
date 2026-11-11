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
package io.agentscope.examples.documentation2.multimodal;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.FileIdSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.dto.DashScopeSkill;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.List;
import java.util.Map;

/**
 * DocumentExample - Demonstrates document input with DashScope document-capable models.
 *
 * <p>Three input channels are shown, matched by the source of the {@link DocumentBlock}:
 *
 * <ul>
 *   <li><b>Public URL</b> ({@code qwen-doc-turbo}): a {@link URLSource} pointing at a publicly
 *       reachable file. Up to 10 URLs per request; they travel as {@code doc_url} content parts
 *       inside the user turn itself. The parsing strategy is configured through the block
 *       metadata.
 *   <li><b>File id</b> ({@code qwen-long}, {@code qwen-doc-turbo}): upload the file through the
 *       OpenAI-compatible {@code files} endpoint first, then pass a {@link FileIdSource} with
 *       provider {@code "dashscope"}.
 *   <li><b>PPT generation</b> ({@code qwen-doc-turbo}): the {@code skill} parameter turns the
 *       document in the conversation into a PowerPoint file. Requires streaming and is passed as
 *       an additional body param, which the DashScope client merges into the request
 *       {@code parameters}. The outline and pages stream back through thinking blocks; the final
 *       download URL arrives as the text content of the last response.
 * </ul>
 */
public class DocumentExample {

    private static final String SAMPLE_DOC_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251107/"
                    + "jockge/%E7%A4%BA%E4%BE%8B%E4%BA%A7%E5%93%81%E6%89%8B%E5%86%8CA.docx";

    /**
     * Runs the document example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs
     */
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Document Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "This example demonstrates the three DashScope document channels:\n"
                        + "  1. doc_url (public URL, qwen-doc-turbo)\n"
                        + "  2. file id (upload first, qwen-long / qwen-doc-turbo)\n"
                        + "  3. PPT generation via the skill parameter (streaming only)");
        System.out.println("=".repeat(60) + "\n");

        documentUrlChannel(apiKey);
        fileIdChannel(apiKey);
        pptGenerationChannel(apiKey);
    }

    /** Channel 1: a public URL, parsed with an explicit strategy. */
    private static void documentUrlChannel(String apiKey) {
        System.out.println("\n" + "-".repeat(60));
        System.out.println("1) doc_url channel (qwen-doc-turbo, public URL)");
        System.out.println("-".repeat(60) + "\n");

        ReActAgent agent = documentAgent(apiKey, "qwen-doc-turbo", null);

        UserMessage userMsg =
                new UserMessage(
                        TextBlock.builder()
                                .text("List every product in this brochure as a table.")
                                .build(),
                        DocumentBlock.builder()
                                .source(URLSource.builder().url(SAMPLE_DOC_URL).build())
                                .name("product-brochure.docx")
                                // Optional: auto | text_only | text_and_images
                                .metadata(Map.of("file_parsing_strategy", "text_only"))
                                .build());

        printReply(agent, userMsg);
    }

    /** Channel 2: a pre-uploaded DashScope file id. */
    private static void fileIdChannel(String apiKey) {
        System.out.println("\n" + "-".repeat(60));
        System.out.println("2) file id channel (qwen-long)");
        System.out.println("-".repeat(60) + "\n");
        System.out.println(
                "Upload first, e.g. with the OpenAI-compatible endpoint:\n"
                        + "  curl -F 'file=@report.pdf' -F 'purpose=file-extract' \\\n"
                        + "    https://dashscope.aliyuncs.com/compatible-mode/v1/files\n"
                        + "The response contains the file id used below.\n");

        ReActAgent agent = documentAgent(apiKey, "qwen-long", null);

        // Replace file-xxxxxxxx with a real id before running; otherwise DashScope rejects
        // the request.
        UserMessage userMsg =
                new UserMessage(
                        TextBlock.builder().text("Summarise this document.").build(),
                        DocumentBlock.builder()
                                .source(
                                        FileIdSource.builder()
                                                .provider("dashscope")
                                                .fileId("file-xxxxxxxx")
                                                .build())
                                .name("report.pdf")
                                .build());

        printReply(agent, userMsg);
    }

    /** Channel 3: PPT generation through the skill parameter. */
    private static void pptGenerationChannel(String apiKey) {
        System.out.println("\n" + "-".repeat(60));
        System.out.println("3) PPT generation (qwen-doc-turbo, skill parameter, streaming)");
        System.out.println("-".repeat(60) + "\n");

        GenerateOptions skillOptions =
                GenerateOptions.builder()
                        // Template mode reads plain text from the system message body; PPT
                        // generation from a file id or a doc_url document requires creative
                        // mode, which is what a doc_url turn needs.
                        .additionalBodyParam("skill", List.of(DashScopeSkill.pptCreative()))
                        .build();
        ReActAgent agent = documentAgent(apiKey, "qwen-doc-turbo", skillOptions);

        System.out.println("Outline and pages stream through thinking blocks;");
        System.out.println("the final PPT download URL arrives as the text content.\n");

        UserMessage userMsg =
                new UserMessage(
                        TextBlock.builder().text("Generate a PPT from this brochure.").build(),
                        DocumentBlock.builder()
                                .source(URLSource.builder().url(SAMPLE_DOC_URL).build())
                                .name("product-brochure.docx")
                                .build());

        agent.streamEvents(userMsg)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e && e.getDelta() != null) {
                                System.out.print(e.getDelta());
                            }
                        })
                .blockLast();
        System.out.println("\n");
    }

    private static ReActAgent documentAgent(
            String apiKey, String modelName, GenerateOptions defaultOptions) {
        DashScopeChatModel.Builder modelBuilder =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(modelName).stream(true)
                        .formatter(new DashScopeChatFormatter());
        if (defaultOptions != null) {
            modelBuilder.defaultOptions(defaultOptions);
        }
        return ReActAgent.builder()
                .name("DocumentAssistant")
                .sysPrompt("You are a helpful assistant working with documents.")
                .model(modelBuilder.build())
                .build();
    }

    private static void printReply(ReActAgent agent, UserMessage userMsg) {
        System.out.println("Sending request...");
        Msg response = agent.call(userMsg).block();
        System.out.println("\nAgent Response:");
        System.out.println("-".repeat(60));
        System.out.println(response != null ? response.getTextContent() : "<no response>");
        System.out.println("-".repeat(60) + "\n");
    }
}
