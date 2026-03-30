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

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Command runner for testing pipeline agents. When {@code pipeline.runner.enabled=true},
 * runs a demo for each pipeline (Sequential, Parallel, Loop) with sample inputs and logs the results.
 */
@Component
@ConditionalOnProperty(name = "pipeline.runner.enabled", havingValue = "true")
public class PipelineCommandRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineCommandRunner.class);

    private final PipelineService pipelineService;

    public PipelineCommandRunner(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("=== Pipeline Command Runner (test) ===");

        runSequentialDemo();
        runParallelDemo();
        runLoopDemo();

        log.info("=== Pipeline test run finished ===");
    }

    private void runSequentialDemo() {
        String input = "List all orders from the last 30 days with total amount greater than 500";
        log.info("--- SequentialAgent demo ---");
        log.info("Input: {}", input);
        try {
            PipelineService.SequentialResult result = pipelineService.runSequential(input);
            log.info("SQL: {}", result.sql());
            log.info("Score: {}", result.score());
        } catch (GraphRunnerException e) {
            log.error("Sequential pipeline failed", e);
        }
    }

    private void runParallelDemo() {
        String input = "AI agents in enterprise software";
        log.info("--- ParallelAgent demo ---");
        log.info("Input: {}", input);
        try {
            PipelineService.ParallelResult result = pipelineService.runParallel(input);
            log.info("Research report (excerpt): {}", truncate(result.researchReport(), 400));
        } catch (GraphRunnerException e) {
            log.error("Parallel pipeline failed", e);
        }
    }

    private void runLoopDemo() {
        String input = "Find customers who placed more than 3 orders in 2024";
        log.info("--- LoopAgent demo ---");
        log.info("Input: {}", input);
        try {
            PipelineService.LoopResult result = pipelineService.runLoop(input);
            log.info("SQL: {}", result.sql());
            log.info("Score: {}", result.score());
        } catch (GraphRunnerException e) {
            log.error("Loop pipeline failed", e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
