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
package io.agentscope.examples.copilotkit.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.AguiUtil;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.examples.copilotkit.model.CopilotKitModels.ThreadInfo;
import io.agentscope.examples.copilotkit.model.CopilotKitModels.ThreadMutationRequest;
import io.agentscope.examples.copilotkit.service.CopilotKitRuntimeService;
import io.agentscope.examples.copilotkit.service.DemoThreadStore;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import io.agentscope.spring.boot.agui.webflux.AguiWebFluxHandler;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * Functional routes for CopilotKit Runtime info, threads, connect, and multi-route agent run.
 *
 * <p>HTTP wiring lives here; business logic stays in dedicated services.
 *
 *
 */
@Configuration
public class CopilotKitRouteConfiguration {

    private static final String BASE = "/agui/run";

    @Bean
    public RouterFunction<ServerResponse> copilotKitRoutes(
            CopilotKitRuntimeService runtime,
            DemoThreadStore threads,
            ThreadSessionManager sessionManager,
            AguiWebFluxHandler aguiHandler) {
        return RouterFunctions.route()
                .GET(BASE + "/info", request -> ServerResponse.ok().bodyValue(runtime.info()))
                .POST(BASE + "/annotate", request -> ServerResponse.ok().bodyValue(Map.of()))
                .GET(BASE + "/threads", request -> listThreads(request, threads))
                .POST(BASE + "/threads", request -> mutateThread(request, threads::create))
                .PATCH(
                        BASE + "/threads/{threadId}",
                        request ->
                                mutateThread(
                                        request,
                                        body -> threads.update(pathVar(request, "threadId"), body)))
                .POST(
                        BASE + "/threads/{threadId}/archive",
                        request ->
                                mutateThread(
                                        request,
                                        body ->
                                                threads.archive(
                                                        pathVar(request, "threadId"), body)))
                .DELETE(
                        BASE + "/threads/{threadId}",
                        request ->
                                ServerResponse.ok()
                                        .bodyValue(threads.delete(pathVar(request, "threadId"))))
                .GET(
                        BASE + "/threads/{threadId}/events",
                        request ->
                                ServerResponse.ok()
                                        .bodyValue(threads.events(pathVar(request, "threadId"))))
                .GET(
                        BASE + "/threads/{threadId}/messages",
                        request ->
                                ServerResponse.ok()
                                        .bodyValue(threads.messages(pathVar(request, "threadId"))))
                .GET(
                        BASE + "/threads/{threadId}/state",
                        request ->
                                ServerResponse.ok()
                                        .bodyValue(threads.state(pathVar(request, "threadId"))))
                .POST(
                        BASE + "/agent/{agentId}/connect",
                        request ->
                                request.bodyToMono(RunAgentInput.class)
                                        .flatMap(
                                                input ->
                                                        ServerResponse.ok()
                                                                .contentType(
                                                                        MediaType.TEXT_EVENT_STREAM)
                                                                .body(
                                                                        runtime.connect(input),
                                                                        ServerSentEvent.class)))
                .POST(BASE + "/agent/{agentId}/run", aguiHandler::handleWithAgentId)
                .POST(
                        BASE + "/agent/{agentId}/stop/{threadId}",
                        request -> stopThread(request, sessionManager))
                .build();
    }

    @SuppressWarnings("resource") // borrowed session agent; session owns lifecycle
    private Mono<ServerResponse> stopThread(
            ServerRequest request, ThreadSessionManager threadSessionManager) {
        String threadId = request.pathVariable("threadId");
        String userId = resolveDemoUserId(request);
        threadSessionManager
                .getSession(userId, threadId)
                .ifPresent(
                        threadSession -> {
                            ReActAgent actAgent = AguiUtil.asReActAgent(threadSession.getAgent());
                            if (actAgent != null) {
                                actAgent.interrupt(userId, threadId);
                            } else {
                                threadSession.getAgent().interrupt();
                            }
                        });
        return Mono.empty();
    }

    private static String resolveDemoUserId(ServerRequest request) {
        String token = request.headers().firstHeader("X-Token");
        return token == null || token.isBlank() ? DemoThreadStore.DEMO_USER_ID : token;
    }

    private static Mono<ServerResponse> listThreads(
            ServerRequest request, DemoThreadStore threads) {
        String agentId = request.queryParam("agentId").orElse(DemoThreadStore.DEFAULT_AGENT_ID);
        boolean includeArchived =
                request.queryParam("includeArchived").map(Boolean::parseBoolean).orElse(false);
        Integer limit = request.queryParam("limit").map(Integer::valueOf).orElse(null);
        String cursor = request.queryParam("cursor").orElse(null);
        return ServerResponse.ok().bodyValue(threads.list(agentId, includeArchived, limit, cursor));
    }

    @FunctionalInterface
    private interface ThreadMutator {
        ThreadInfo apply(ThreadMutationRequest body);
    }

    private static Mono<ServerResponse> mutateThread(ServerRequest request, ThreadMutator mutator) {
        return optionalBody(request)
                .map(mutator::apply)
                .flatMap(thread -> ServerResponse.ok().bodyValue(thread))
                .onErrorResume(CopilotKitRouteConfiguration::handleThreadMutationError);
    }

    private static Mono<ThreadMutationRequest> optionalBody(ServerRequest request) {
        return request.bodyToMono(ThreadMutationRequest.class)
                .defaultIfEmpty(new ThreadMutationRequest(null, null, null));
    }

    private static Mono<ServerResponse> handleThreadMutationError(Throwable error) {
        if (error instanceof DecodingException || error instanceof ServerWebInputException) {
            return ServerResponse.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", "Invalid thread mutation request body"));
        }
        return Mono.error(error);
    }

    private static String pathVar(ServerRequest request, String name) {
        return request.pathVariable(name);
    }
}
