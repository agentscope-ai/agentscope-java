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
package io.agentscope.harness.coding;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.coding.control.RunDispatcher;
import io.agentscope.harness.coding.observability.CodingAgentMetrics;
import io.agentscope.harness.coding.reviewer.GitHubReviewPublisher;
import io.agentscope.harness.coding.reviewer.ReviewerFindingsService;
import io.agentscope.harness.coding.store.SqliteBaseStore;
import io.agentscope.harness.coding.tools.FetchUrlTool;
import io.agentscope.harness.coding.tools.GitHubApiTool;
import io.agentscope.harness.coding.tools.HttpRequestTool;
import io.agentscope.harness.coding.tools.RequestPrReviewTool;
import io.agentscope.harness.coding.tools.WebSearchTool;
import io.agentscope.harness.coding.tools.finding.AddFindingTool;
import io.agentscope.harness.coding.tools.finding.ListFindingsTool;
import io.agentscope.harness.coding.tools.finding.PublishReviewTool;
import io.agentscope.harness.coding.tools.finding.UpdateFindingTool;
import io.agentscope.harness.coding.webhook.github.GitHubWebhookHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot application entry point for the coding agent webhook service.
 *
 * <p>Assembles all components:
 *
 * <ul>
 *   <li>{@link CodingBootstrap} with dual agents (coding + reviewer)
 *   <li>{@link SqliteBaseStore} for thread metadata, queue, deliveries, findings
 *   <li>{@link RunDispatcher} for webhook → agent dispatch
 *   <li>{@link GitHubWebhookHandler} for incoming GitHub events
 * </ul>
 *
 * <h2>Run</h2>
 *
 * <pre>
 * mvn -pl agentscope-examples/agents/agentscope-codingagent -am spring-boot:run
 * </pre>
 */
@SpringBootApplication
public class CodingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodingAgentApplication.class, args);
    }

    @Bean
    public SqliteBaseStore sqliteBaseStore() throws SQLException {
        String dbPath =
                System.getProperty("agentscope.codingagent.db", ".agentscope/codingagent.db");
        return new SqliteBaseStore(dbPath);
    }

    @Bean
    public CodingAgentMetrics codingAgentMetrics(MeterRegistry registry) {
        return new CodingAgentMetrics(registry);
    }

    @Bean
    public ReviewerFindingsService reviewerFindingsService(
            SqliteBaseStore store, CodingAgentMetrics metrics) {
        return new ReviewerFindingsService(store, metrics);
    }

    @Bean
    public GitHubReviewPublisher gitHubReviewPublisher(CodingAgentMetrics metrics) {
        return new GitHubReviewPublisher(metrics);
    }

    @Bean
    public Toolkit codingToolkit(ObjectProvider<RunDispatcher> dispatcherProvider) {
        // RunDispatcher depends on CodingBootstrap, which depends on this toolkit — resolve the
        // dispatcher lazily at tool-invocation time to break the bean-construction cycle.
        Toolkit tk = new Toolkit();
        tk.registerTool(new HttpRequestTool());
        tk.registerTool(new FetchUrlTool());
        tk.registerTool(new WebSearchTool());
        tk.registerTool(new GitHubApiTool());
        tk.registerTool(
                new RequestPrReviewTool(
                        prUrl ->
                                dispatcherProvider
                                        .getObject()
                                        .dispatchReviewer(prUrl)
                                        .subscribe()));
        return tk;
    }

    @Bean
    public Toolkit reviewerToolkit(
            ReviewerFindingsService findingsService, GitHubReviewPublisher publisher) {
        Toolkit tk = new Toolkit();
        tk.registerTool(new GitHubApiTool());
        tk.registerTool(new FetchUrlTool());
        tk.registerTool(new AddFindingTool(findingsService));
        tk.registerTool(new UpdateFindingTool(findingsService));
        tk.registerTool(new ListFindingsTool(findingsService));
        tk.registerTool(new PublishReviewTool(findingsService, publisher));
        return tk;
    }

    @Bean
    public CodingBootstrap codingBootstrap(
            Toolkit codingToolkit, Toolkit reviewerToolkit, SqliteBaseStore store)
            throws IOException {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        return CodingBootstrap.builder()
                .cwd(cwd)
                .withDualCodingAgents(codingToolkit, reviewerToolkit, store)
                .build();
    }

    @Bean
    public RunDispatcher runDispatcher(
            CodingBootstrap bootstrap, SqliteBaseStore store, CodingAgentMetrics metrics) {
        return new RunDispatcher(bootstrap.gateway(), store, metrics);
    }

    @Bean
    public GitHubWebhookHandler gitHubWebhookHandler(
            RunDispatcher dispatcher, SqliteBaseStore store, CodingAgentMetrics metrics) {
        return new GitHubWebhookHandler(dispatcher, store, metrics);
    }
}
