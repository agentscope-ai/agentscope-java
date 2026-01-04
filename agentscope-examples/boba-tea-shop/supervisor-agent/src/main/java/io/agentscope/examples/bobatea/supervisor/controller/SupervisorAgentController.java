/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.examples.bobatea.supervisor.controller;

import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.examples.bobatea.supervisor.agent.SupervisorAgent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@RequestMapping("/api/assistant/")
@RestController
public class SupervisorAgentController {

    private static final Logger logger = LoggerFactory.getLogger(SupervisorAgentController.class);

    private final SupervisorAgent supervisorAgent;

    @Autowired private Environment environment;

    public SupervisorAgentController(SupervisorAgent supervisorAgent) {
        this.supervisorAgent = supervisorAgent;
    }

    @GetMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @RequestParam(name = "chat_id") String chatID,
            @RequestParam(name = "user_query") String userQuery,
            @RequestParam(name = "user_id") String userID)
            throws Exception {
        logger.info("Received user query: {}", userQuery);

        try {

            String userInput = userQuery + "<userId>" + userID + "</userId>";
            Sinks.Many<ServerSentEvent<String>> sink =
                    Sinks.many().unicast().onBackpressureBuffer();
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(userInput).build())
                            .build();

            processStream(supervisorAgent.stream(msg, chatID, userID), sink);

            return sink.asFlux()
                    .doOnCancel(
                            () -> {
                                logger.info("Client disconnected from stream");
                            })
                    .doOnError(
                            e -> {
                                logger.error("Error occurred during streaming", e);
                            });
        } catch (Exception e) {
            logger.error("Failed to process user query: {}", userQuery, e);
            return Flux.just(
                    ServerSentEvent.builder("System processing error, please try again later.")
                            .build());
        }
    }

    public void processStream(Flux<Event> generator, Sinks.Many<ServerSentEvent<String>> sink) {
        generator
                .doOnNext(output -> logger.info("output = {}", output))
                .filter(event -> !event.isLast())
                .map(
                        event -> {
                            Msg msg = event.getMessage();
                            return msg.getContent().stream()
                                    .filter(block -> block instanceof TextBlock)
                                    .map(block -> ((TextBlock) block).getText())
                                    .toList();
                        })
                .flatMap(Flux::fromIterable)
                .map(content -> ServerSentEvent.builder(content).build())
                .doOnNext(sink::tryEmitNext)
                .doOnError(
                        e -> {
                            logger.error(
                                    "Unexpected error in stream processing: {}", e.getMessage(), e);
                            sink.tryEmitNext(
                                    ServerSentEvent.builder(
                                                    "System processing error, please try again"
                                                            + " later.")
                                            .build());
                        })
                .doOnComplete(
                        () -> {
                            logger.info("Stream processing completed successfully");
                            sink.tryEmitComplete();
                        })
                .subscribe(
                        // onNext - already handled in doOnNext
                        null,
                        // onError
                        e -> {
                            logger.error("Stream processing failed: {}", e.getMessage(), e);
                            sink.tryEmitError(e);
                        });
    }

    /**
     * List all report files
     * @return List of report files
     */
    @GetMapping(path = "/reports", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listReports() {
        try {
            String userDir = System.getProperty("user.dir");
            Path reportsDir = Paths.get(userDir, "reports");

            if (!Files.exists(reportsDir)) {
                logger.warn("Reports directory does not exist: {}", reportsDir.toAbsolutePath());
                return ResponseEntity.ok(createSuccessResponse(Collections.emptyList()));
            }

            try (Stream<Path> paths = Files.list(reportsDir)) {
                List<Map<String, Object>> reports =
                        paths.filter(Files::isRegularFile)
                                .filter(path -> path.toString().endsWith(".md"))
                                .sorted(
                                        (p1, p2) -> {
                                            try {
                                                // Sort by modification time in descending order
                                                // (newest first)
                                                return Files.getLastModifiedTime(p2)
                                                        .compareTo(Files.getLastModifiedTime(p1));
                                            } catch (IOException e) {
                                                return 0;
                                            }
                                        })
                                .map(
                                        path -> {
                                            Map<String, Object> reportInfo = new HashMap<>();
                                            reportInfo.put(
                                                    "fileName", path.getFileName().toString());
                                            try {
                                                reportInfo.put("size", Files.size(path));
                                                reportInfo.put(
                                                        "lastModified",
                                                        Files.getLastModifiedTime(path).toString());
                                            } catch (IOException e) {
                                                logger.error(
                                                        "Failed to get file info: {}", path, e);
                                            }
                                            return reportInfo;
                                        })
                                .collect(Collectors.toList());

                logger.info("Successfully listed {} report files", reports.size());
                return ResponseEntity.ok(createSuccessResponse(reports));
            }
        } catch (IOException e) {
            logger.error("Failed to list report files", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to list report files: " + e.getMessage()));
        }
    }

    /**
     * Query report content by report name
     * @param fileName Report file name
     * @return Report content
     */
    @GetMapping(path = "/reports/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getReportContent(
            @RequestParam(name = "fileName") String fileName) {
        try {
            // Security check: prevent path traversal attack
            if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                logger.warn("Detected illegal file name: {}", fileName);
                return ResponseEntity.badRequest().body(createErrorResponse("Illegal file name"));
            }

            String userDir = System.getProperty("user.dir");
            Path reportPath = Paths.get(userDir, "reports", fileName);

            // Check if file exists
            if (!Files.exists(reportPath)) {
                logger.warn("Report file does not exist: {}", reportPath.toAbsolutePath());
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(createErrorResponse("Report file does not exist"));
            }

            // Check if it is a file
            if (!Files.isRegularFile(reportPath)) {
                logger.warn("Path is not a file: {}", reportPath.toAbsolutePath());
                return ResponseEntity.badRequest().body(createErrorResponse("Path is not a file"));
            }

            // Read file content
            String content = Files.readString(reportPath);

            Map<String, Object> result = new HashMap<>();
            result.put("fileName", fileName);
            result.put("content", content);
            result.put("size", Files.size(reportPath));
            result.put("lastModified", Files.getLastModifiedTime(reportPath).toString());

            logger.info("Successfully read report file: {}", fileName);
            return ResponseEntity.ok(createSuccessResponse(result));
        } catch (IOException e) {
            logger.error("Failed to read report file: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to read report file: " + e.getMessage()));
        }
    }

    /**
     * Create success response
     */
    private Map<String, Object> createSuccessResponse(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    /**
     * Create error response
     */
    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
}
