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
package io.agentscope.examples.documentation2.harness.teams;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamSessionMembership.MemberSession;
import io.agentscope.harness.agent.team.TeamSessionMembership.Role;
import io.agentscope.harness.agent.team.TeamSessionMembership.SessionKey;
import io.agentscope.harness.agent.team.TeamSessionMembership.TeamAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Flux;

/** Registers existing native Sessions through the programming API, without provider credentials. */
public final class NativeTeamMembershipExample {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String LOG_DOMAIN = "example-workspace-logs";
    private static final String RELATIONSHIP_DOMAIN = "example-relations";

    private NativeTeamMembershipExample() {}

    /** Runs with an optional workspace directory. Only the Session logs survive process exit. */
    public static void main(String[] args) throws Exception {
        Path workspace =
                args.length == 0
                        ? Files.createTempDirectory("native-team-membership-")
                        : Path.of(args[0]);
        var logs = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        var leaderContext = context();
        var workerContext = context();
        MemberSession leader;
        MemberSession worker;
        try (var leaderAgent = agent(workspace, logs, "coordinator-runtime");
                var workerAgent = agent(workspace, logs, "research-runtime")) {
            leaderAgent.call("Initialize the coordinator conversation.", leaderContext).block(WAIT);
            workerAgent.call("Initialize the researcher conversation.", workerContext).block(WAIT);
            leader =
                    new MemberSession(
                            "lead",
                            "demo",
                            "coordinator-definition",
                            Role.LEADER,
                            SessionKey.nativeSession(
                                    LOG_DOMAIN, leaderAgent.sessionKey(leaderContext)));
            worker =
                    new MemberSession(
                            "researcher",
                            "demo",
                            "research-definition",
                            Role.WORKER,
                            SessionKey.nativeSession(
                                    LOG_DOMAIN, workerAgent.sessionKey(workerContext)));
        }

        var teamStore = new InMemoryStore();
        var client = new LocalTeamClient(teamStore);
        var address = new TeamAddress("example-app", "research");
        client.createTeam(
                        new TeamCreateSpec(
                                address.teamName(),
                                address.namespace(),
                                "Compare evidence",
                                "coordinator-definition",
                                "",
                                List.of(
                                        new TeamMemberSpec(
                                                "researcher", "research-definition", "", "byo"))))
                .block(WAIT);
        var membership =
                client.sessionMembership(RELATIONSHIP_DOMAIN, Map.of(), Map.of(LOG_DOMAIN, logs));
        var team =
                Objects.requireNonNull(
                        membership
                                .adoptTeam("demo", address, "Research", List.of(leader))
                                .block(WAIT));
        var binding =
                Objects.requireNonNull(membership.bindMember("demo", address, worker).block(WAIT));

        var reopenedLogs = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        var reopened =
                new LocalTeamClient(teamStore)
                        .sessionMembership(
                                RELATIONSHIP_DOMAIN, Map.of(), Map.of(LOG_DOMAIN, reopenedLogs));
        var current = reopened.findMembership(worker.session()).block(WAIT);
        var reopenedTeam = Objects.requireNonNull(reopened.getTeam("demo", address).block(WAIT));
        var members = Objects.requireNonNull(reopened.listMemberships("demo", address).block(WAIT));
        if (!binding.equals(current)
                || !team.teamId().equals(reopenedTeam.teamId())
                || members.size() != 2) {
            throw new IllegalStateException("Reopening changed the Team associations");
        }

        boolean removed =
                Boolean.TRUE.equals(
                        reopened.unbindMember("demo", address, worker.session(), binding.token())
                                .block(WAIT));
        if (!removed || reopened.findMembership(worker.session()).block(WAIT) != null) {
            throw new IllegalStateException("The expected binding was not removed");
        }
        var nativeKey =
                new io.agentscope.core.session.SessionKey(
                        worker.session().owner(),
                        worker.session().agentId(),
                        worker.session().sessionId());
        if (SessionProjection.read(reopenedLogs.open(nativeKey, workerContext)).stateJson()
                == null) {
            throw new IllegalStateException("Removing membership deleted the native Session");
        }
        System.out.println("Team: " + team.teamId());
        System.out.println("Registered two existing Sessions and recovered both associations.");
        System.out.println(
                "Removed the researcher association; its committed Session log remains.");
        System.out.println("Native log workspace: " + workspace.toAbsolutePath());
        System.out.println(
                "Team records use InMemoryStore in this example. Use a durable JdbcStore to retain"
                        + " associations across process restarts.");
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder()
                .userId("demo")
                .sessionId(UUID.randomUUID().toString())
                .build();
    }

    private static HarnessAgent agent(Path workspace, SessionLogStore logs, String agentId) {
        return HarnessAgent.builder()
                .agentId(agentId)
                .workspace(workspace)
                .model(new InitializationModel())
                .sessionLogStore(logs)
                .disableShellTool()
                .disableFilesystemTools()
                .disableWebTools()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableToolsConfig()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .build();
    }

    private static final class InitializationModel implements Model {
        @Override
        public String getModelName() {
            return "scripted-session-initialization";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("Session ready.").build()))
                            .build());
        }
    }
}
