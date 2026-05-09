/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.examples.multiagents.workflow.ragagent;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent;
import com.alibaba.cloud.ai.examples.multiagents.workflow.ragagent.node.PrepareAgentNode;
import com.alibaba.cloud.ai.examples.multiagents.workflow.ragagent.node.RetrieveNode;
import com.alibaba.cloud.ai.examples.multiagents.workflow.ragagent.node.RewriteNode;
import com.alibaba.cloud.ai.examples.multiagents.workflow.ragagent.tools.RagAgentTools;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.tool.Toolkit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * RAG agent workflow using AgentScope: rewrite → retrieve → prepare_agent → agent.
 * Uses DashScopeChatModel, AgentScope Knowledge (DashScopeTextEmbedding + InMemoryStore + SimpleKnowledge),
 * AgentScope @Tool in RagAgentTools, and AgentScopeAgent for the agent node.
 */
@Configuration
@ConditionalOnProperty(name = "workflow.rag.enabled", havingValue = "true")
public class RagAgentConfig {

    private static final int EMBEDDING_DIMENSIONS = 1024;

    private static final String REWRITE_PROMPT =
            """
            Rewrite this query to retrieve relevant WNBA information.
            The knowledge base contains: team rosters, game results with scores, and player statistics (PPG, RPG, APG).
            Focus on specific player names, team names, or stat categories mentioned.
            Original query: %s
            Respond with only the rewritten query, nothing else.
            """;

    private static final String AGENT_PROMPT =
            """
            You are a WNBA stats assistant. Use the provided context to answer questions.
            Context:
            %s

            Question: %s

            Respond concisely. If the context doesn't contain the answer, say so.
            """;

    @Bean
    public Model ragDashScopeChatModel(@Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        String key = StringUtils.hasText(apiKey) ? apiKey : System.getenv("AI_DASHSCOPE_API_KEY");
        return DashScopeChatModel.builder().apiKey(key).modelName("qwen-plus").build();
    }

    @Bean
    public EmbeddingModel ragEmbeddingModel(
            @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        String key = StringUtils.hasText(apiKey) ? apiKey : System.getenv("AI_DASHSCOPE_API_KEY");
        return DashScopeTextEmbedding.builder()
                .apiKey(key)
                .modelName("text-embedding-v3")
                .dimensions(EMBEDDING_DIMENSIONS)
                .build();
    }

    @Bean
    public Knowledge ragKnowledge(EmbeddingModel ragEmbeddingModel) throws Exception {
        VDBStoreBase vectorStore = InMemoryStore.builder().dimensions(EMBEDDING_DIMENSIONS).build();
        Knowledge knowledge =
                SimpleKnowledge.builder()
                        .embeddingModel(ragEmbeddingModel)
                        .embeddingStore(vectorStore)
                        .build();
        List<Document> docs = buildSampleDocuments();
        knowledge.addDocuments(docs).block();
        return knowledge;
    }

    private static List<Document> buildSampleDocuments() {
        return List.of(
                doc(
                        "New York Liberty 2024 roster: Breanna Stewart, Sabrina Ionescu, Jonquel"
                                + " Jones, Courtney Vandersloot.",
                        "rosters",
                        0),
                doc(
                        "Las Vegas Aces 2024 roster: A'ja Wilson, Kelsey Plum, Jackie Young,"
                                + " Chelsea Gray.",
                        "rosters",
                        1),
                doc(
                        "Indiana Fever 2024 roster: Caitlin Clark, Aliyah Boston, Kelsey Mitchell,"
                                + " NaLyssa Smith.",
                        "rosters",
                        2),
                doc(
                        "2024 WNBA Finals: New York Liberty defeated Minnesota Lynx 3-2 to win the"
                                + " championship.",
                        "games",
                        3),
                doc(
                        "June 15, 2024: Indiana Fever 85, Chicago Sky 79. Caitlin Clark had 23"
                                + " points and 8 assists.",
                        "games",
                        4),
                doc(
                        "August 20, 2024: Las Vegas Aces 92, Phoenix Mercury 84. A'ja Wilson scored"
                                + " 35 points.",
                        "games",
                        5),
                doc(
                        "A'ja Wilson 2024 season stats: 26.9 PPG, 11.9 RPG, 2.6 BPG. Won MVP"
                                + " award.",
                        "stats",
                        6),
                doc(
                        "Caitlin Clark 2024 rookie stats: 19.2 PPG, 8.4 APG, 5.7 RPG. Won Rookie of"
                                + " the Year.",
                        "stats",
                        7),
                doc("Breanna Stewart 2024 stats: 20.4 PPG, 8.5 RPG, 3.5 APG.", "stats", 8));
    }

    private static Document doc(String text, String source, int index) {
        DocumentMetadata metadata =
                new DocumentMetadata(
                        TextBlock.builder().text(text).build(),
                        "wnba-" + index,
                        "0",
                        Map.of("source", source));
        return new Document(metadata);
    }

    @Bean
    public CompiledGraph ragGraph(Model ragDashScopeChatModel, Knowledge ragKnowledge)
            throws GraphStateException {

        StateGraph graph =
                new StateGraph(
                        "rag_workflow",
                        () -> {
                            Map<String, KeyStrategy> strategies = new HashMap<>();
                            strategies.put("question", new ReplaceStrategy());
                            strategies.put("rewritten_query", new ReplaceStrategy());
                            strategies.put("documents", new ReplaceStrategy());
                            strategies.put("messages", new AppendStrategy(false));
                            return strategies;
                        });

        RewriteNode rewriteNode = new RewriteNode(ragDashScopeChatModel, REWRITE_PROMPT);
        RetrieveNode retrieveNode = new RetrieveNode(ragKnowledge);
        PrepareAgentNode prepareAgentNode = new PrepareAgentNode();

        RagAgentTools ragAgentTools = new RagAgentTools();
        Toolkit agentToolkit = new Toolkit();
        agentToolkit.registerTool(ragAgentTools);
        AgentScopeAgent ragAgent =
                AgentScopeAgent.fromBuilder(
                                ReActAgent.builder()
                                        .name("rag_agent")
                                        .sysPrompt(
                                                "You are a WNBA stats assistant. Answer questions"
                                                        + " using the context provided.")
                                        .model(ragDashScopeChatModel)
                                        .toolkit(agentToolkit)
                                        .memory(new InMemoryMemory()))
                        .name("rag_agent")
                        .description("WNBA stats assistant with context")
                        .instruction("Answer based on the context and question below.\n\n{input}")
                        .includeContents(false)
                        .returnReasoningContents(false)
                        .build();

        graph.addNode("rewrite", node_async(rewriteNode))
                .addNode("retrieve", node_async(retrieveNode))
                .addNode("prepare_agent", node_async(prepareAgentNode))
                .addNode("rag_agent", ragAgent.asNode())
                .addEdge(START, "rewrite")
                .addEdge("rewrite", "retrieve")
                .addEdge("retrieve", "prepare_agent")
                .addEdge("prepare_agent", "rag_agent")
                .addEdge("rag_agent", END);

        return graph.compile();
    }

    @Bean
    public RagAgentService ragAgentService(CompiledGraph ragGraph) {
        return new RagAgentService(ragGraph);
    }

    /**
     * Builds the agent prompt with context and question for PrepareAgentNode.
     */
    public static String buildAgentPrompt(String context, String question) {
        return AGENT_PROMPT.formatted(context, question);
    }
}
