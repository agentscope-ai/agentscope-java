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
package io.agentscope.extensions.aistio.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamConflictException;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamInfo;
import io.agentscope.harness.agent.team.TeamMemberInfo;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamMessage;
import io.agentscope.harness.agent.team.TeamTask;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Control-plane backed {@link TeamClient} over {@code /api/v1/teams/*}. */
public final class ControlPlaneTeamClient implements TeamClient {

    private static final ObjectMapper MAPPER = ControlPlaneHttpClient.mapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ControlPlaneHttpClient http;

    public ControlPlaneTeamClient(ControlPlaneHttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public Mono<List<TeamTask>> listTasks(String namespace, String teamName) {
        return Mono.fromCallable(
                        () -> {
                            String path =
                                    "/api/v1/teams/"
                                            + enc(teamName)
                                            + "/tasks?namespace="
                                            + enc(namespace);
                            ControlPlaneHttpClient.Response resp = http.send("GET", path, null);
                            requireOk(resp, "listTasks");
                            JsonNode root = MAPPER.readTree(resp.body());
                            List<TeamTask> out = new ArrayList<>();
                            for (JsonNode n : root.path("tasks")) {
                                out.add(taskFromJson(n));
                            }
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamTask> createTask(
            String namespace,
            String teamName,
            String subject,
            String description,
            List<String> blockedBy,
            String owner) {
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("subject", subject);
                            body.put("description", description);
                            body.put("blockedBy", blockedBy == null ? List.of() : blockedBy);
                            if (owner != null && !owner.isBlank()) {
                                body.put("owner", owner);
                            }
                            ControlPlaneHttpClient.Response resp =
                                    http.send(
                                            "POST",
                                            "/api/v1/teams/"
                                                    + enc(teamName)
                                                    + "/tasks?namespace="
                                                    + enc(namespace),
                                            body);
                            requireOk(resp, "createTask");
                            return taskFromJson(MAPPER.readTree(resp.body()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamTask> assignTask(
            String namespace, String teamName, String taskId, String owner, long expectedVersion) {
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("owner", owner);
                            body.put("resourceVersion", String.valueOf(expectedVersion));
                            ControlPlaneHttpClient.Response resp =
                                    http.send(
                                            "POST",
                                            "/api/v1/teams/"
                                                    + enc(teamName)
                                                    + "/tasks/"
                                                    + enc(taskId)
                                                    + "/assign?namespace="
                                                    + enc(namespace),
                                            body);
                            if (resp.status() == 409) {
                                throw new TeamConflictException(resp.body());
                            }
                            requireOk(resp, "assignTask");
                            return taskFromJson(MAPPER.readTree(resp.body()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamTask> claimTask(
            String namespace,
            String teamName,
            String taskId,
            String claimedBy,
            long expectedVersion) {
        return Mono.fromCallable(
                        () -> {
                            long version = expectedVersion;
                            if (version <= 0) {
                                TeamTask cur = getTask(namespace, teamName, taskId);
                                if (cur != null
                                        && TeamTask.IN_PROGRESS.equals(cur.state())
                                        && claimedBy != null
                                        && claimedBy.equals(cur.owner())) {
                                    return cur;
                                }
                                if (cur == null) {
                                    throw new IllegalStateException("task not found: " + taskId);
                                }
                                version = cur.version();
                            }
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("claimedBy", claimedBy);
                            body.put("resourceVersion", String.valueOf(version));
                            ControlPlaneHttpClient.Response resp =
                                    http.send(
                                            "POST",
                                            "/api/v1/teams/"
                                                    + enc(teamName)
                                                    + "/tasks/"
                                                    + enc(taskId)
                                                    + "/claim?namespace="
                                                    + enc(namespace),
                                            body);
                            if (resp.status() == 409) {
                                // Idempotent retry: already in progress by this member.
                                TeamTask again = getTask(namespace, teamName, taskId);
                                if (again != null
                                        && TeamTask.IN_PROGRESS.equals(again.state())
                                        && claimedBy != null
                                        && claimedBy.equals(again.owner())) {
                                    return again;
                                }
                                throw new TeamConflictException(resp.body());
                            }
                            requireOk(resp, "claimTask");
                            return taskFromJson(MAPPER.readTree(resp.body()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamTask> failTask(
            String namespace, String teamName, String taskId, String reason) {
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> body =
                                    Map.of("reason", reason == null ? "" : reason);
                            ControlPlaneHttpClient.Response resp =
                                    http.send(
                                            "POST",
                                            "/api/v1/teams/"
                                                    + enc(teamName)
                                                    + "/tasks/"
                                                    + enc(taskId)
                                                    + "/fail?namespace="
                                                    + enc(namespace),
                                            body);
                            if (resp.status() == 409) {
                                throw new TeamConflictException(resp.body());
                            }
                            requireOk(resp, "failTask");
                            return taskFromJson(MAPPER.readTree(resp.body()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamTask> completeTask(
            String namespace, String teamName, String taskId, String result) {
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> body =
                                    Map.of("result", result == null ? "" : result);
                            ControlPlaneHttpClient.Response resp =
                                    http.send(
                                            "POST",
                                            "/api/v1/teams/"
                                                    + enc(teamName)
                                                    + "/tasks/"
                                                    + enc(taskId)
                                                    + "/complete?namespace="
                                                    + enc(namespace),
                                            body);
                            requireOk(resp, "completeTask");
                            return taskFromJson(MAPPER.readTree(resp.body()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamTask> unclaimTask(
            String namespace, String teamName, String taskId, long expectedVersion) {
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("resourceVersion", String.valueOf(expectedVersion));
                            ControlPlaneHttpClient.Response resp =
                                    http.send(
                                            "POST",
                                            "/api/v1/teams/"
                                                    + enc(teamName)
                                                    + "/tasks/"
                                                    + enc(taskId)
                                                    + "/unclaim?namespace="
                                                    + enc(namespace),
                                            body);
                            if (resp.status() == 409) {
                                throw new TeamConflictException(resp.body());
                            }
                            requireOk(resp, "unclaimTask");
                            return taskFromJson(MAPPER.readTree(resp.body()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<TeamTask>> listClaimableTasks(
            String namespace, String teamName, String forMember) {
        return listTasks(namespace, teamName).map(tasks -> TeamTask.claimableOf(tasks, forMember));
    }

    @Override
    public Mono<TeamMessage> sendMessage(
            String namespace, String teamName, String from, String to, String content) {
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("from", from);
                            body.put("to", to);
                            body.put("content", content);
                            ControlPlaneHttpClient.Response resp =
                                    http.send(
                                            "POST",
                                            "/api/v1/teams/"
                                                    + enc(teamName)
                                                    + "/messages?namespace="
                                                    + enc(namespace),
                                            body);
                            requireOk(resp, "sendMessage");
                            JsonNode n = MAPPER.readTree(resp.body());
                            return new TeamMessage(
                                    n.path("fromMember").asText(from),
                                    n.path("toMember").asText(to),
                                    n.path("content").asText(content),
                                    n.path("id").asLong(0L));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<TeamMessage>> broadcastMessage(
            String namespace, String teamName, String from, String content) {
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("from", from);
                            body.put("to", "");
                            body.put("content", content);
                            ControlPlaneHttpClient.Response resp =
                                    http.send(
                                            "POST",
                                            "/api/v1/teams/"
                                                    + enc(teamName)
                                                    + "/messages?namespace="
                                                    + enc(namespace),
                                            body);
                            requireOk(resp, "broadcastMessage");
                            JsonNode root = MAPPER.readTree(resp.body());
                            List<TeamMessage> out = new ArrayList<>();
                            if (root.has("messages") && root.path("messages").isArray()) {
                                for (JsonNode n : root.path("messages")) {
                                    out.add(
                                            new TeamMessage(
                                                    n.path("fromMember").asText(from),
                                                    n.path("toMember").asText(""),
                                                    n.path("content").asText(content),
                                                    n.path("id").asLong(0L)));
                                }
                            } else {
                                out.add(
                                        new TeamMessage(
                                                root.path("fromMember").asText(from),
                                                root.path("toMember").asText(""),
                                                root.path("content").asText(content),
                                                root.path("id").asLong(0L)));
                            }
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> spawnMember(
            String namespace, String teamName, String name, String agentRef, String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("agentRef", agentRef);
        body.put("prompt", prompt == null ? "" : prompt);
        return sendVoid(
                "POST",
                "/api/v1/teams/" + enc(teamName) + "/members?namespace=" + enc(namespace),
                body,
                "spawnMember");
    }

    @Override
    public Mono<Void> shutdownMember(String namespace, String teamName, String memberName) {
        return sendVoid(
                "DELETE",
                "/api/v1/teams/"
                        + enc(teamName)
                        + "/members/"
                        + enc(memberName)
                        + "?namespace="
                        + enc(namespace),
                null,
                "shutdownMember");
    }

    @Override
    public Mono<Void> submitPlan(
            String namespace, String teamName, String memberName, String planText) {
        return sendVoid(
                "POST",
                "/api/v1/teams/"
                        + enc(teamName)
                        + "/members/"
                        + enc(memberName)
                        + "/plan?namespace="
                        + enc(namespace),
                Map.of("planText", planText == null ? "" : planText),
                "submitPlan");
    }

    @Override
    public Mono<Void> approvePlan(String namespace, String teamName, String memberName) {
        return planDecision(namespace, teamName, memberName, "approve");
    }

    @Override
    public Mono<Void> rejectPlan(String namespace, String teamName, String memberName) {
        return planDecision(namespace, teamName, memberName, "reject");
    }

    private Mono<Void> planDecision(
            String namespace, String teamName, String memberName, String decision) {
        return sendVoid(
                "POST",
                "/api/v1/teams/"
                        + enc(teamName)
                        + "/members/"
                        + enc(memberName)
                        + "/plan/"
                        + decision
                        + "?namespace="
                        + enc(namespace),
                Map.of(),
                decision + "Plan");
    }

    /** Issues a control-plane call whose response body carries no useful payload. */
    private Mono<Void> sendVoid(String method, String path, Object body, String op) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            try {
                                ControlPlaneHttpClient.Response resp =
                                        http.send(method, path, body);
                                if (resp.status() == 409) {
                                    throw new TeamConflictException(resp.body());
                                }
                                requireOk(resp, op);
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new IllegalStateException(op + " failed", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<TeamMessage>> listMessages(String namespace, String teamName, int limit) {
        return Mono.fromCallable(
                        () -> {
                            String path =
                                    "/api/v1/teams/"
                                            + enc(teamName)
                                            + "/messages?namespace="
                                            + enc(namespace)
                                            + "&limit="
                                            + limit;
                            ControlPlaneHttpClient.Response resp = http.send("GET", path, null);
                            requireOk(resp, "listMessages");
                            JsonNode root = MAPPER.readTree(resp.body());
                            List<TeamMessage> out = new ArrayList<>();
                            for (JsonNode n : root.path("messages")) {
                                out.add(
                                        new TeamMessage(
                                                n.path("fromMember").asText(),
                                                n.path("toMember").asText(),
                                                n.path("content").asText(),
                                                n.path("id").asLong()));
                            }
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<TeamMemberInfo>> listMembers(String namespace, String teamName) {
        return Mono.fromCallable(
                        () -> {
                            String path =
                                    "/api/v1/teams/"
                                            + enc(teamName)
                                            + "/members?namespace="
                                            + enc(namespace);
                            ControlPlaneHttpClient.Response resp = http.send("GET", path, null);
                            requireOk(resp, "listMembers");
                            JsonNode root = MAPPER.readTree(resp.body());
                            List<TeamMemberInfo> out = new ArrayList<>();
                            JsonNode lead = root.path("lead");
                            if (!lead.isMissingNode() && !lead.isNull()) {
                                out.add(memberFromJson(lead, true));
                            }
                            for (JsonNode n : root.path("members")) {
                                out.add(memberFromJson(n, false));
                            }
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamInfo> createTeam(TeamCreateSpec spec) {
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("name", spec.name());
                            body.put("namespace", spec.namespace());
                            body.put("objective", spec.objective());
                            body.put(
                                    "lead",
                                    Map.of(
                                            "agentRef",
                                            spec.leadAgentRef(),
                                            "prompt",
                                            spec.leadPrompt() == null ? "" : spec.leadPrompt()));
                            List<Map<String, Object>> members = new ArrayList<>();
                            if (spec.members() != null) {
                                for (TeamMemberSpec m : spec.members()) {
                                    Map<String, Object> mm = new LinkedHashMap<>();
                                    mm.put("name", m.name());
                                    mm.put("agentRef", m.agentRef());
                                    mm.put("prompt", m.prompt() == null ? "" : m.prompt());
                                    if (m.deployMode() != null) {
                                        mm.put("deployMode", m.deployMode());
                                    }
                                    members.add(mm);
                                }
                            }
                            body.put("members", members);
                            ControlPlaneHttpClient.Response resp =
                                    http.send("POST", "/api/v1/teams", body);
                            if (resp.status() == 409) {
                                throw new TeamConflictException(resp.body());
                            }
                            requireOk(resp, "createTeam");
                            JsonNode root = MAPPER.readTree(resp.body());
                            JsonNode team = root.path("team");
                            if (team.isMissingNode()) {
                                team = root;
                            }
                            return new TeamInfo(
                                    team.path("name").asText(spec.name()),
                                    team.path("namespace").asText(spec.namespace()),
                                    team.path("objective").asText(spec.objective()),
                                    team.path("phase").asText("Running"),
                                    team.path("leadRef").asText(spec.leadAgentRef()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> completeTeam(String namespace, String teamName) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            try {
                                ControlPlaneHttpClient.Response resp =
                                        http.send(
                                                "POST",
                                                "/api/v1/teams/"
                                                        + enc(teamName)
                                                        + "/complete?namespace="
                                                        + enc(namespace),
                                                Map.of());
                                if (resp.status() == 404) {
                                    // Older control planes: DELETE forces complete+cleanup.
                                    resp =
                                            http.send(
                                                    "DELETE",
                                                    "/api/v1/teams/"
                                                            + enc(teamName)
                                                            + "?namespace="
                                                            + enc(namespace),
                                                    null);
                                }
                                requireOk(resp, "completeTeam");
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static TeamTask taskFromJson(JsonNode n) {
        List<String> blocked = new ArrayList<>();
        if (n.path("blockedBy").isArray()) {
            for (JsonNode b : n.path("blockedBy")) {
                blocked.add(b.asText());
            }
        } else if (n.path("blockedBy").isTextual()) {
            // postgres may return raw json string occasionally — ignore
        }
        return new TeamTask(
                n.path("taskId").asText(),
                n.path("teamName").asText(),
                n.path("namespace").asText(),
                n.path("subject").asText(),
                n.path("description").asText(""),
                n.path("state").asText(),
                n.path("owner").asText(""),
                blocked,
                n.path("result").asText(""),
                n.path("version").asLong());
    }

    private TeamTask getTask(String namespace, String teamName, String taskId) throws Exception {
        String path = "/api/v1/teams/" + enc(teamName) + "/tasks?namespace=" + enc(namespace);
        ControlPlaneHttpClient.Response resp = http.send("GET", path, null);
        requireOk(resp, "getTask");
        JsonNode root = MAPPER.readTree(resp.body());
        for (JsonNode n : root.path("tasks")) {
            if (taskId.equals(n.path("taskId").asText())) {
                return taskFromJson(n);
            }
        }
        return null;
    }

    private static TeamMemberInfo memberFromJson(JsonNode n, boolean leadHint) {
        String name = n.path("memberName").asText(n.path("name").asText());
        return new TeamMemberInfo(
                name,
                n.path("agentRef").asText(),
                n.path("phase").asText(),
                n.path("sessionId").asText(""),
                n.path("deployMode").asText(""),
                leadHint || "lead".equals(name));
    }

    private static void requireOk(ControlPlaneHttpClient.Response resp, String op) {
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException(
                    op + " failed: HTTP " + resp.status() + " " + resp.body());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
