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
package com.alibaba.cloud.ai.examples.multiagents.pipeline;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LoopAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import java.util.Optional;
import org.springframework.ai.chat.messages.Message;

/**
 * Service that invokes pipeline agents (Sequential, Parallel, Loop) for testing.
 * Extracts result text from each agent's output state.
 */
public class PipelineService {

    private static final String INPUT_KEY = "input";
    private static final String SQL_KEY = "sql";
    private static final String SCORE_KEY = "score";
    private static final String RESEARCH_REPORT_KEY = "research_report";

    private final SequentialAgent sequentialSqlAgent;
    private final ParallelAgent parallelResearchAgent;
    private final LoopAgent loopSqlRefinementAgent;

    public PipelineService(
            SequentialAgent sequentialSqlAgent,
            ParallelAgent parallelResearchAgent,
            LoopAgent loopSqlRefinementAgent) {
        this.sequentialSqlAgent = sequentialSqlAgent;
        this.parallelResearchAgent = parallelResearchAgent;
        this.loopSqlRefinementAgent = loopSqlRefinementAgent;
    }

    /**
     * Run the sequential SQL pipeline: natural language → SQL → score.
     */
    public SequentialResult runSequential(String userInput) throws GraphRunnerException {
        Optional<OverAllState> resultOpt = sequentialSqlAgent.invoke(userInput);
        if (resultOpt.isEmpty()) {
            return new SequentialResult(userInput, null, null);
        }
        OverAllState state = resultOpt.get();
        String sql = extractText(state.value(SQL_KEY));
        String score = extractText(state.value(SCORE_KEY));
        return new SequentialResult(userInput, sql, score);
    }

    /**
     * Run the parallel research pipeline: one topic → tech + finance + market analyses merged.
     */
    public ParallelResult runParallel(String userInput) throws GraphRunnerException {
        Optional<OverAllState> resultOpt = parallelResearchAgent.invoke(userInput);
        if (resultOpt.isEmpty()) {
            return new ParallelResult(userInput, null);
        }
        OverAllState state = resultOpt.get();
        String report = extractText(state.value(RESEARCH_REPORT_KEY));
        return new ParallelResult(userInput, report);
    }

    /**
     * Run the loop SQL refinement pipeline: generate SQL and score until score &gt; 0.5.
     */
    public LoopResult runLoop(String userInput) throws GraphRunnerException {
        Optional<OverAllState> resultOpt = loopSqlRefinementAgent.invoke(userInput);
        if (resultOpt.isEmpty()) {
            return new LoopResult(userInput, null, null);
        }
        OverAllState state = resultOpt.get();
        String sql = extractText(state.value(SQL_KEY));
        String score = extractText(state.value(SCORE_KEY));
        return new LoopResult(userInput, sql, score);
    }

    private static String extractText(Optional<Object> valueOpt) {
        if (valueOpt.isEmpty()) {
            return null;
        }
        Object v = valueOpt.get();
        if (v instanceof Message message) {
            return message.getText();
        }
        return v != null ? v.toString() : null;
    }

    public record SequentialResult(String input, String sql, String score) {}

    public record ParallelResult(String input, String researchReport) {}

    public record LoopResult(String input, String sql, String score) {}
}
