package io.agentscope.examples.documentation2.quickstart;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UserIsolatedMultiTurnsExample {

    public static void main(String[] args) throws Exception {
        // 1. Prepare workspace: generate AGENTS.md on first run, reuse afterwards
        Path workspace = Paths.get(".agentscope/workspace");
        initWorkspaceIfAbsent(workspace);

        // 2. Build model
        Model model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-max")
                .stream(true)
                .build();

        // 3. Build HarnessAgent: workspace injection, session persistence, and trace logging
        //    are enabled by default; compaction is explicitly configured here
        HarnessAgent agent = HarnessAgent.builder()
                .name("quickstart-agent")
                .sysPrompt("You are a note-taking assistant.")
                .model(model)
                .workspace(workspace)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .flushBeforeCompact(true)   // extract facts to daily log before compacting
                        .build())
                .build();

        // 4. Two conversation turns with the same RuntimeContext
        //    Same sessionId → turn 2 auto-restores state from turn 1
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("demo-session")
                .userId("alice")
                .build();

        Msg turn1 = agent.call(
                Msg.builder().role(MsgRole.USER)
                        .textContent("My name is Alice, and I'm preparing a tech talk on ReAct today. Remember this!")
                        .build(),
                ctx).block();
        System.out.println("[turn1] " + turn1.getTextContent());

//
        ctx = RuntimeContext.builder()
                .sessionId("demo-session")
                .userId("ken")
                .build();
        Msg turn2 = agent.call(
                Msg.builder().role(MsgRole.USER)
                        .textContent("I like eating apples. Remember this!")
                        .build(),
                ctx).block();
        System.out.println("[turn2] " + turn2.getTextContent());

        // wait 5 seconds for asynchronous memory flush to write
        Thread.sleep(5000);
    }

    private static void initWorkspaceIfAbsent(Path workspace) throws Exception {
        Files.createDirectories(workspace);
        Path agentsMd = workspace.resolve("AGENTS.md");
        if (Files.exists(agentsMd)) return;
        Files.writeString(agentsMd, """
                # Note-taking Assistant

                You are an assistant that helps users organize notes and knowledge.

                ## Behavior Guidelines
                - Actively record key facts the user mentions (names, plans, preferences, etc.)
                - Reply concisely, using bullet lists when helpful
                - For uncertain information, say so clearly rather than guessing
                """);
    }
}
