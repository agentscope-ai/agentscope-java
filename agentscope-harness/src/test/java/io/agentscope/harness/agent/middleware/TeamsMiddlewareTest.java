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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMessage;
import io.agentscope.harness.agent.team.TeamTask;
import io.agentscope.harness.agent.tool.TeamTool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class TeamsMiddlewareTest {

    @Test
    void renderIncludesLeadGuidance() {
        TeamContext ctx =
                new TeamContext(
                        "research",
                        "default",
                        "ship docs",
                        "lead",
                        true,
                        List.of(new TeamContext.MemberSnapshot("lead", "a", "working")),
                        List.of("createTask", "assignTask"));
        String section = TeamsMiddleware.renderTeamSection(ctx);
        assertTrue(section.contains("Agent Team"));
        assertTrue(section.contains("lead"));
        assertTrue(section.contains("ship docs"));
        assertTrue(section.contains("createTask"));
    }

    @Test
    void notifyWakeup_pushesInjectableHintWithEventBody() {
        TeamsMiddleware mw = middleware(leadContext());
        RecordingMessageBus bus = new RecordingMessageBus();
        mw.wireMessageBus(bus, "main");
        mw.bindSession("sess-1");

        mw.notifyWakeup("sess-1", "[team] Task task-1 (ship it) completed by w1.");

        Map<String, Object> pushed = bus.pushes.get("agentscope:inbox:sess-1");
        assertNotNull(pushed, "notice must land in the session inbox");
        // InboxMiddleware drops payloads without a `hint` field, so the woken turn
        // would otherwise start with no context at all.
        Object hint = pushed.get("hint");
        assertNotNull(hint, "payload must carry a hint InboxMiddleware can deserialize");
        assertNotNull(InboxMiddleware.deserializeHintBlock(pushed));
        assertTrue(hint.toString().contains("Task task-1 (ship it) completed by w1"));
        assertTrue(bus.pushes.containsKey("agentscope:wakeups"), "idle lead must be woken");
    }

    @Test
    void notifyWakeup_withoutBoundSession_bufferedForNextReasoning() {
        TeamsMiddleware mw = middleware(leadContext());

        mw.notifyWakeup(null, "[team] Task task-9 failed by w2.");

        String injected = mw.drainPendingNotices();
        assertTrue(injected.contains("Task task-9 failed by w2"));
        assertTrue(mw.drainPendingNotices().isEmpty(), "notices drain once");
    }

    @Test
    void teamToolRejectsDisallowedAction() {
        TeamContext ctx =
                new TeamContext(
                        "research",
                        "default",
                        "ship",
                        "worker",
                        false,
                        List.of(),
                        List.of("listTasks", "claimTask"));
        TeamClient client =
                new io.agentscope.harness.agent.team.LocalTeamClient(
                        new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore());
        TeamTool tool = new TeamTool(client, ctx);
        String out =
                tool.team(
                        "createTask",
                        null,
                        "x",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertTrue(out.contains("error"));
        assertFalse(out.contains("\"taskId\""));
    }

    @Test
    void boardDigest_showsEveryTaskWithItsOwnerStateAndResult() {
        String digest =
                TeamsMiddleware.renderBoardDigest(
                        List.of(
                                task(
                                        "task-1",
                                        "analyse e2b",
                                        TeamTask.COMPLETED,
                                        "worker1",
                                        "e2b" + " isolates with firecracker"),
                                task(
                                        "task-2",
                                        "analyse daytona",
                                        TeamTask.IN_PROGRESS,
                                        "lead",
                                        "")),
                        "lead");

        assertTrue(digest.contains("task-1"), digest);
        assertTrue(digest.contains("analyse e2b"), digest);
        assertTrue(digest.contains("worker1"), digest);
        assertTrue(
                digest.contains("e2b isolates with firecracker"),
                "the lead writes the final summary from these results: " + digest);
        assertTrue(digest.contains("task-2"), digest);
    }

    @Test
    void boardDigest_namesTheLeadsOwnUnsettledTasks() {
        String digest =
                TeamsMiddleware.renderBoardDigest(
                        List.of(
                                task(
                                        "task-1",
                                        "analyse e2b",
                                        TeamTask.COMPLETED,
                                        "worker1",
                                        "done"),
                                task("task-2", "analyse daytona", TeamTask.IN_PROGRESS, "lead", ""),
                                task("task-4", "compare both", TeamTask.IN_PROGRESS, "lead", "")),
                        "lead");

        assertTrue(digest.contains("You own 2 unsettled task(s): task-2, task-4."), digest);
        assertFalse(digest.contains("Every task is settled"), digest);
    }

    @Test
    void boardDigest_asksForTheFinalSummaryOnceEverythingIsSettled() {
        String digest =
                TeamsMiddleware.renderBoardDigest(
                        List.of(
                                task(
                                        "task-1",
                                        "analyse e2b",
                                        TeamTask.COMPLETED,
                                        "worker1",
                                        "done"),
                                task(
                                        "task-2",
                                        "analyse daytona",
                                        TeamTask.FAILED,
                                        "worker2",
                                        "no" + " api key")),
                        "lead");

        assertTrue(digest.contains("Every task is settled"), digest);
        assertTrue(digest.contains("completeTeam"), digest);
        assertFalse(digest.contains("You own"), digest);
    }

    @Test
    void boardDigest_ofAnEmptyBoardAddsNothing() {
        assertEquals("", TeamsMiddleware.renderBoardDigest(List.of(), "lead"));
        assertEquals("", TeamsMiddleware.renderBoardDigest(null, "lead"));
    }

    private static TeamTask task(
            String id, String subject, String state, String owner, String result) {
        return new TeamTask(id, "research", "ns", subject, "", state, owner, List.of(), result, 1);
    }

    @Test
    void turnEnd_failsTheTaskTheWorkerLeftOpenAndTellsTheLeadWhy() {
        LocalTeamClient client = teamWithClaimedTask("stall", "worker-1");
        TeamsMiddleware mw = new TeamsMiddleware(client, memberContext("stall", "worker-1", "ns"));

        mw.settleOwnedTasks("I cannot search without TAVILY_API_KEY. Please send me the docs.");

        TeamTask task = client.listTasks("ns", "stall").block().get(0);
        assertEquals(TeamTask.FAILED, task.state());
        List<TeamMessage> leadInbox = inboxOf(client, "stall", "lead");
        assertEquals(1, leadInbox.size());
        assertTrue(
                leadInbox.get(0).content().contains("TAVILY_API_KEY"), leadInbox.get(0).content());
    }

    @Test
    void turnEnd_leavesATaskTheWorkerAlreadySettledAlone() {
        LocalTeamClient client = teamWithClaimedTask("done", "worker-1");
        TeamsMiddleware mw = new TeamsMiddleware(client, memberContext("done", "worker-1", "ns"));
        TeamTool tool = (TeamTool) mw.getTools().get(0);

        tool.completeTask("task-1", "Analysed the sandbox, findings inline.");
        mw.settleOwnedTasks("Analysed the sandbox, findings inline.");

        TeamTask task = client.listTasks("ns", "done").block().get(0);
        assertEquals(TeamTask.COMPLETED, task.state());
        assertEquals(1, inboxOf(client, "done", "lead").size(), "only the completion notice");
    }

    @Test
    void turnEnd_withoutAClosingMessageStillSettlesTheTask() {
        LocalTeamClient client = teamWithClaimedTask("mute", "worker-1");
        TeamsMiddleware mw = new TeamsMiddleware(client, memberContext("mute", "worker-1", "ns"));

        mw.settleOwnedTasks("");

        TeamTask task = client.listTasks("ns", "mute").block().get(0);
        assertEquals(TeamTask.FAILED, task.state());
        assertTrue(
                inboxOf(client, "mute", "lead").get(0).content().contains("without reporting"),
                "the lead still learns the task came back empty");
    }

    @Test
    void turnEnd_leavesAnAssignedTaskTheWorkerHasNotStarted() {
        LocalTeamClient client = teamWithClaimedTask("queued", "worker-1");
        client.createTask("ns", "queued", "analyse daytona", "", List.of(), "worker-1").block();
        TeamsMiddleware mw = new TeamsMiddleware(client, memberContext("queued", "worker-1", "ns"));

        mw.settleOwnedTasks("Done with the first one.");

        List<TeamTask> tasks = client.listTasks("ns", "queued").block();
        assertEquals(TeamTask.FAILED, tasks.get(0).state(), "the claimed task is settled");
        assertEquals(
                TeamTask.PENDING,
                tasks.get(1).state(),
                "a task only assigned to it still awaits its own wakeup");
    }

    @Test
    void turnEnd_ofTheLeadSettlesNothing() {
        LocalTeamClient client = teamWithClaimedTask("chair", "lead");
        TeamsMiddleware mw = new TeamsMiddleware(client, memberContext("chair", "lead", "ns"));

        mw.settleOwnedTasks("I will look into it.");

        TeamTask task = client.listTasks("ns", "chair").block().get(0);
        assertEquals(TeamTask.IN_PROGRESS, task.state());
    }

    private static LocalTeamClient teamWithClaimedTask(String team, String member) {
        LocalTeamClient client = new LocalTeamClient(new InMemoryStore());
        client.createTeam(new TeamCreateSpec(team, "ns", "obj", "lead", "", List.of())).block();
        TeamTask task =
                client.createTask("ns", team, "analyse e2b", "read the docs", List.of(), member)
                        .block();
        client.claimTask("ns", team, task.taskId(), member, 0L).block();
        return client;
    }

    private static List<TeamMessage> inboxOf(LocalTeamClient client, String team, String member) {
        return client.listMessages("ns", team, 50).block().stream()
                .filter(m -> member.equals(m.to()))
                .toList();
    }

    private static TeamContext memberContext(String teamName, String memberName, String namespace) {
        return new TeamContext(
                teamName,
                namespace,
                "ship docs",
                memberName,
                false,
                List.of(new TeamContext.MemberSnapshot(memberName, "a", "working")),
                List.of("claimTask", "completeTask", "sendMessage", "listTasks"));
    }

    @Test
    void unregisterSession_dropsBothSessionAndMemberRouting() {
        TeamsMiddleware mw = middleware(memberContext("cleanup-a", "worker-1"));
        mw.bindSession("sess-cleanup-a");
        assertTrue(TeamsMiddleware.wakeupTeamMember("cleanup-a", "worker-1"));

        TeamsMiddleware.unregisterSession("sess-cleanup-a");

        assertFalse(TeamsMiddleware.wakeupSession("sess-cleanup-a"));
        assertFalse(TeamsMiddleware.wakeupTeamMember("cleanup-a", "worker-1"));
    }

    @Test
    void unregisterSession_keepsMemberRebuiltUnderANewSession() {
        TeamsMiddleware old = middleware(memberContext("cleanup-b", "worker-1"));
        old.bindSession("sess-old");
        TeamsMiddleware current = middleware(memberContext("cleanup-b", "worker-1"));
        current.bindSession("sess-new");

        TeamsMiddleware.unregisterSession("sess-old");

        assertTrue(TeamsMiddleware.wakeupTeamMember("cleanup-b", "worker-1"));
        assertTrue(TeamsMiddleware.wakeupSession("sess-new"));
    }

    private static TeamContext memberContext(String teamName, String memberName) {
        return new TeamContext(
                teamName,
                "default",
                "ship docs",
                memberName,
                false,
                List.of(new TeamContext.MemberSnapshot(memberName, "a", "working")),
                List.of("claimTask"));
    }

    private static TeamContext leadContext() {
        return new TeamContext(
                "research",
                "default",
                "ship docs",
                "lead",
                true,
                List.of(new TeamContext.MemberSnapshot("lead", "a", "working")),
                List.of("createTask"));
    }

    private static TeamsMiddleware middleware(TeamContext ctx) {
        return new TeamsMiddleware(
                new io.agentscope.harness.agent.team.LocalTeamClient(
                        new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore()),
                ctx);
    }

    /** Captures the last payload pushed per bus key. */
    private static class RecordingMessageBus implements MessageBus {

        final Map<String, Map<String, Object>> pushes = new ConcurrentHashMap<>();

        @Override
        public Mono<String> queuePush(String key, Map<String, Object> payload) {
            pushes.put(key, payload);
            return Mono.just("1");
        }

        @Override
        public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
            return Mono.just(List.of());
        }

        @Override
        public Mono<Void> queueDelete(String key) {
            return Mono.empty();
        }

        @Override
        public Mono<Boolean> queuePeek(String key) {
            return Mono.just(pushes.containsKey(key));
        }

        @Override
        public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
            return Mono.just("1");
        }

        @Override
        public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
            return Mono.just(List.of());
        }

        @Override
        public Mono<Void> logTrim(String key) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> publish(String key, Map<String, Object> payload) {
            return Mono.empty();
        }

        @Override
        public Flux<Map<String, Object>> subscribe(String key) {
            return Flux.empty();
        }
    }
}
