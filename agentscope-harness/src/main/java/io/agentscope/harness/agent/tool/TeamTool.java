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
package io.agentscope.harness.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamConflictException;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamMemberInfo;
import io.agentscope.harness.agent.team.TeamMessage;
import io.agentscope.harness.agent.team.TeamTask;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Unified AgentTeams tool. Actions are gated by {@link TeamContext#availableActions()} (lead gets
 * create/assign/spawn/shutdown/approve/complete; workers get list/claim/message).
 */
public final class TeamTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TeamClient client;
    private final TeamContext context;

    public TeamTool(TeamClient client, TeamContext context) {
        this.client = Objects.requireNonNull(client, "client");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Tool(
            name = "team",
            description =
                    "AgentTeams coordination. Set action to one of: listTasks, createTask,"
                        + " assignTask, claimTask, unclaimTask, completeTask, listClaimableTasks,"
                        + " sendMessage, broadcastMessage, listMessages, listMembers, spawnMember,"
                        + " shutdownMember, submitPlan, approvePlan, rejectPlan, completeTeam."
                        + " Lead-only actions require isLead. Use expectedVersion from listTasks"
                        + " for assign/claim/unclaim.")
    public String team(
            @ToolParam(name = "action", description = "Team action name") String action,
            @ToolParam(name = "task_id", description = "Task id", required = false) String taskId,
            @ToolParam(name = "subject", description = "Task subject", required = false)
                    String subject,
            @ToolParam(name = "description", description = "Task description", required = false)
                    String description,
            @ToolParam(name = "owner", description = "Assignee / claimer", required = false)
                    String owner,
            @ToolParam(
                            name = "expected_version",
                            description = "Optimistic lock version",
                            required = false)
                    Long expectedVersion,
            @ToolParam(name = "result", description = "Completion result text", required = false)
                    String result,
            @ToolParam(name = "to_member", description = "Mailbox recipient", required = false)
                    String toMember,
            @ToolParam(name = "content", description = "Mailbox message body", required = false)
                    String content,
            @ToolParam(name = "limit", description = "List limit", required = false) Integer limit,
            @ToolParam(name = "member_name", description = "Teammate name", required = false)
                    String memberName,
            @ToolParam(name = "agent_ref", description = "Agent ref for spawn", required = false)
                    String agentRef,
            @ToolParam(name = "prompt", description = "Member prompt / plan text", required = false)
                    String prompt,
            @ToolParam(
                            name = "plan_text",
                            description = "Plan body for submitPlan",
                            required = false)
                    String planText) {
        String act = action == null ? "" : action.trim();
        if (act.isEmpty()) {
            return error("action is required");
        }
        if (!isAllowed(act)) {
            return error("action '" + act + "' is not allowed for role " + context.myRole());
        }
        String ns = context.resolvedNamespace();
        String team = context.teamName();
        String me = context.myRole() == null ? "" : context.myRole();
        try {
            return switch (normalize(act)) {
                case "listtasks" -> json(client.listTasks(ns, team).block());
                case "listclaimabletasks" -> json(client.listClaimableTasks(ns, team).block());
                case "createtask" ->
                        json(
                                client.createTask(
                                                ns,
                                                team,
                                                subject == null ? "" : subject,
                                                description == null ? "" : description,
                                                List.of(),
                                                owner == null ? "" : owner)
                                        .block());
                case "assigntask" ->
                        json(
                                client.assignTask(
                                                ns,
                                                team,
                                                require(taskId, "task_id"),
                                                require(owner, "owner"),
                                                expectedVersion == null ? 0L : expectedVersion)
                                        .block());
                case "claimtask" ->
                        json(
                                client.claimTask(
                                                ns,
                                                team,
                                                require(taskId, "task_id"),
                                                owner == null || owner.isBlank() ? me : owner,
                                                expectedVersion == null ? 0L : expectedVersion)
                                        .block());
                case "unclaimtask" ->
                        json(
                                client.unclaimTask(
                                                ns,
                                                team,
                                                require(taskId, "task_id"),
                                                expectedVersion == null ? 0L : expectedVersion)
                                        .block());
                case "completetask" ->
                        json(
                                client.completeTask(
                                                ns,
                                                team,
                                                require(taskId, "task_id"),
                                                result == null ? "" : result)
                                        .block());
                case "sendmessage" ->
                        json(
                                client.sendMessage(
                                                ns,
                                                team,
                                                me,
                                                require(toMember, "to_member"),
                                                content == null ? "" : content)
                                        .block());
                case "broadcastmessage" ->
                        json(
                                client.broadcastMessage(
                                                ns, team, me, content == null ? "" : content)
                                        .block());
                case "listmessages" ->
                        json(client.listMessages(ns, team, limit == null ? 50 : limit).block());
                case "listmembers" -> json(client.listMembers(ns, team).block());
                case "spawnmember" -> {
                    client.spawnMember(
                                    ns,
                                    team,
                                    require(memberName, "member_name"),
                                    require(agentRef, "agent_ref"),
                                    prompt == null ? "" : prompt)
                            .block();
                    yield "{\"ok\":true}";
                }
                case "shutdownmember" -> {
                    client.shutdownMember(ns, team, require(memberName, "member_name")).block();
                    yield "{\"ok\":true}";
                }
                case "submitplan" -> {
                    String text =
                            planText != null && !planText.isBlank()
                                    ? planText
                                    : (prompt == null ? "" : prompt);
                    client.submitPlan(
                                    ns,
                                    team,
                                    memberName == null || memberName.isBlank() ? me : memberName,
                                    text)
                            .block();
                    yield "{\"ok\":true}";
                }
                case "approveplan" -> {
                    client.approvePlan(ns, team, require(memberName, "member_name")).block();
                    yield "{\"ok\":true}";
                }
                case "rejectplan" -> {
                    client.rejectPlan(ns, team, require(memberName, "member_name")).block();
                    yield "{\"ok\":true}";
                }
                case "completeteam" -> {
                    client.completeTeam(ns, team).block();
                    yield "{\"ok\":true}";
                }
                default -> error("unknown action: " + act);
            };
        } catch (TeamConflictException e) {
            return error("conflict: " + e.getMessage());
        } catch (RuntimeException e) {
            return error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private boolean isAllowed(String action) {
        List<String> allowed = context.availableActions();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        Set<String> set = new HashSet<>();
        for (String a : allowed) {
            if (a != null) {
                set.add(normalize(a));
            }
        }
        String n = normalize(action);
        // Aliases keep sessions started before an action was named in TeamContext working.
        if (("listclaimabletasks".equals(n) || "unclaimtask".equals(n))
                && set.contains("claimtask")) {
            return true;
        }
        if ("listmessages".equals(n)
                && (set.contains("sendmessage") || set.contains("broadcastmessage"))) {
            return true;
        }
        return set.contains(n);
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static String require(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return v;
    }

    private static String json(Object value) {
        try {
            if (value == null) {
                return "null";
            }
            if (value instanceof List<?> list) {
                // Prefer compact task/member records
                return MAPPER.writeValueAsString(list);
            }
            if (value instanceof TeamTask
                    || value instanceof TeamMessage
                    || value instanceof TeamMemberInfo) {
                return MAPPER.writeValueAsString(value);
            }
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return error("json encode failed");
        }
    }

    private static String error(String msg) {
        return "{\"error\":" + quote(msg) + "}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
