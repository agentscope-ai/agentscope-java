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
package io.agentscope.harness.claw.app.admin.console;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Admin read-only view of per-channel routing bindings, sourced from the shared
 * {@code .agentscope/agentscope.json} workspace file.
 *
 * <ul>
 *   <li>{@code GET /api/admin/channels/{channelId}/bindings} — list bindings for a channel
 * </ul>
 *
 * <p>Mutations are not supported in the admin console; edit {@code agentscope.json} directly and
 * restart the application.
 *
 * <p>Requires {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/admin/channels/{channelId}/bindings")
public class AdminBindingController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path configFile;

    public AdminBindingController(@Value("${claw.workspace:}") String workspaceDir) {
        Path cwd =
                workspaceDir != null && !workspaceDir.isBlank()
                        ? Path.of(workspaceDir)
                        : Path.of(System.getProperty("user.dir"));
        this.configFile = cwd.resolve(".agentscope").resolve("agentscope.json").normalize();
    }

    /** Returns the routing bindings declared in {@code agentscope.json} for a single channel. */
    @GetMapping
    public Mono<List<BindingView>> list(@PathVariable String channelId) {
        return Mono.fromCallable(
                () -> {
                    if (!Files.exists(configFile)) return List.of();
                    JsonNode root = MAPPER.readTree(configFile.toFile());
                    JsonNode channels = root.path("channels");
                    if (channels.isMissingNode() || channels.isNull()) return List.of();
                    JsonNode ch = channels.path(channelId);
                    if (ch.isMissingNode() || ch.isNull()) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Channel not found: " + channelId);
                    }
                    JsonNode bindings = ch.path("bindings");
                    if (!bindings.isArray()) return List.of();
                    List<BindingView> result = new ArrayList<>();
                    for (JsonNode b : bindings) {
                        result.add(
                                new BindingView(
                                        textOrNull(b, "agentId"),
                                        textOrNull(b, "peer"),
                                        textOrNull(b, "guild"),
                                        textOrNull(b, "channel"),
                                        textOrNull(b, "sessionScope")));
                    }
                    return result;
                });
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode f = node.path(field);
        return f.isTextual() ? f.asText() : null;
    }

    /** Single channel-binding row for the admin console. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BindingView(
            String agentId, String peer, String guild, String channel, String sessionScope) {}
}
