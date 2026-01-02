# Multi-Agent Debate

Multi-Agent Debate is a workflow pattern that simulates a multi-turn discussion between different agents. This pattern is particularly useful for problem-solving tasks where multiple perspectives can lead to better solutions.

## Overview

The debate workflow typically involves:

- **Solver Agents (Debaters)**: Generate and exchange their answers, arguing from different perspectives
- **Aggregator Agent (Moderator)**: Collects and evaluates the arguments, deciding when a correct answer has been reached

This pattern is inspired by research showing that multi-agent debate can improve reasoning accuracy in Large Language Models (reference: "Encouraging Divergent Thinking in Large Language Models through Multi-Agent Debate", EMNLP 2024).

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Debate Loop                          │
│                                                             │
│   ┌─────────┐    MsgHub     ┌─────────┐                    │
│   │ Debater │ ◄──────────► │ Debater │                    │
│   │  Alice  │   broadcast   │   Bob   │                    │
│   └────┬────┘              └────┬────┘                    │
│        │                        │                          │
│        └──────────┬─────────────┘                          │
│                   ▼                                         │
│            ┌────────────┐                                   │
│            │ Moderator  │ ── Structured Output ──► finished?│
│            └────────────┘                                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## Implementation

### Step 1: Create Debater Agents

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;

// Define the debate topic
String topic = """
    The two circles are externally tangent and there is no relative sliding.
    The radius of circle A is 1/3 the radius of circle B. Circle A rolls
    around circle B one trip back to its starting point. How many times will
    circle A revolve in total?
    """;

// Create model with MultiAgentFormatter for multi-agent communication
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .formatter(new DashScopeMultiAgentFormatter())
        .build();

// Create debater Alice
ReActAgent alice = ReActAgent.builder()
        .name("Alice")
        .sysPrompt(String.format("""
            You're a debater named Alice. Welcome to the debate competition.
            It's unnecessary to fully agree with each other's perspectives,
            as our objective is to find the correct answer.
            The debate topic is: %s
            """, topic))
        .model(model)
        .memory(new InMemoryMemory())
        .build();

// Create debater Bob
ReActAgent bob = ReActAgent.builder()
        .name("Bob")
        .sysPrompt(String.format("""
            You're a debater named Bob. Welcome to the debate competition.
            It's unnecessary to fully agree with each other's perspectives,
            as our objective is to find the correct answer.
            The debate topic is: %s
            """, topic))
        .model(model)
        .memory(new InMemoryMemory())
        .build();
```

### Step 2: Create Moderator Agent

The moderator evaluates the debate and decides when a correct answer has been found:

```java
// Create moderator agent
ReActAgent moderator = ReActAgent.builder()
        .name("Moderator")
        .sysPrompt(String.format("""
            You're a moderator. There will be two debaters involved in a debate.
            They will present their answers and discuss their perspectives on the topic:
            ```
            %s
            ```
            At the end of each round, you will evaluate both sides' answers
            and decide which one is correct. If you determine the correct answer,
            set 'finished' to true and provide the 'correctAnswer'.
            """, topic))
        .model(model)
        .memory(new InMemoryMemory())
        .build();
```

### Step 3: Define Structured Output for Judgment

Use a structured output class to capture the moderator's decision:

```java
/**
 * Structured output model for the moderator's judgment.
 */
public class JudgeResult {
    /**
     * Whether the debate has reached a conclusion.
     */
    public boolean finished;

    /**
     * The correct answer, if the debate is finished.
     */
    public String correctAnswer;
}
```

### Step 4: Implement the Debate Loop

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.pipeline.MsgHub;

public void runDebate() {
    int maxRounds = 5;

    for (int round = 1; round <= maxRounds; round++) {
        System.out.println("\n=== Round " + round + " ===\n");

        // Debaters discuss within MsgHub
        // Their messages are automatically broadcast to each other
        try (MsgHub hub = MsgHub.builder()
                .participants(alice, bob, moderator)
                .build()) {

            hub.enter().block();

            // Alice presents her argument (affirmative side)
            Msg aliceMsg = alice.call(Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("You are the affirmative side. Please express your viewpoints.")
                            .build())
                    .build()).block();
            System.out.println("Alice: " + aliceMsg.getTextContent());

            // Bob presents his argument (negative side)
            Msg bobMsg = bob.call(Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("You are the negative side. You may disagree. Provide your reason and answer.")
                            .build())
                    .build()).block();
            System.out.println("Bob: " + bobMsg.getTextContent());
        }

        // Moderator evaluates (outside MsgHub - debaters don't need to see this)
        Msg judgeMsg = moderator.call(
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder()
                                .text("Now you have heard the answers from both debaters. " +
                                      "Has the debate finished? Can you determine the correct answer?")
                                .build())
                        .build(),
                JudgeResult.class  // Request structured output
        ).block();

        // Extract structured judgment
        JudgeResult result = judgeMsg.getStructuredData(JudgeResult.class);

        if (result.finished) {
            System.out.println("\n=== Debate Concluded ===");
            System.out.println("The correct answer is: " + result.correctAnswer);
            return;
        }

        System.out.println("Moderator: Debate continues to next round...");
    }

    System.out.println("\n=== Max rounds reached without conclusion ===");
}
```

## Complete Example

Here's a complete, runnable example:

```java
package io.agentscope.examples;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.pipeline.MsgHub;

public class MultiAgentDebateExample {

    public static class JudgeResult {
        public boolean finished;
        public String correctAnswer;
    }

    public static void main(String[] args) {
        // Topic
        String topic = """
            The two circles are externally tangent and there is no relative sliding.
            The radius of circle A is 1/3 the radius of circle B. Circle A rolls
            around circle B one trip back to its starting point. How many times will
            circle A revolve in total?
            """;

        // Create model
        DashScopeChatModel model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen3-max")
                .formatter(new DashScopeMultiAgentFormatter())
                .build();

        // Create debaters
        ReActAgent alice = ReActAgent.builder()
                .name("Alice")
                .sysPrompt("You're a debater named Alice. Topic: " + topic)
                .model(model)
                .memory(new InMemoryMemory())
                .build();

        ReActAgent bob = ReActAgent.builder()
                .name("Bob")
                .sysPrompt("You're a debater named Bob. Topic: " + topic)
                .model(model)
                .memory(new InMemoryMemory())
                .build();

        // Create moderator
        ReActAgent moderator = ReActAgent.builder()
                .name("Moderator")
                .sysPrompt("You're a moderator evaluating a debate on: " + topic)
                .model(model)
                .memory(new InMemoryMemory())
                .build();

        // Run debate
        for (int round = 1; round <= 5; round++) {
            System.out.println("\n=== Round " + round + " ===\n");

            try (MsgHub hub = MsgHub.builder()
                    .participants(alice, bob, moderator)
                    .build()) {

                hub.enter().block();

                Msg aliceMsg = alice.call(Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder()
                                .text("Present your argument.")
                                .build())
                        .build()).block();
                System.out.println("Alice: " + aliceMsg.getTextContent());

                Msg bobMsg = bob.call(Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder()
                                .text("Respond to Alice and present your argument.")
                                .build())
                        .build()).block();
                System.out.println("Bob: " + bobMsg.getTextContent());
            }

            // Moderator judgment
            Msg judgeMsg = moderator.call(
                    Msg.builder()
                            .name("user")
                            .role(MsgRole.USER)
                            .content(TextBlock.builder()
                                    .text("Evaluate the debate. Is there a correct answer?")
                                    .build())
                            .build(),
                    JudgeResult.class
            ).block();

            JudgeResult result = judgeMsg.getStructuredData(JudgeResult.class);

            if (result.finished) {
                System.out.println("\n=== Debate Concluded ===");
                System.out.println("Answer: " + result.correctAnswer);
                break;
            }
        }
    }
}
```

## Variations

### Multiple Debaters

You can extend the pattern to include more than two debaters:

```java
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob, charlie, moderator)
        .build()) {

    hub.enter().block();

    alice.call(prompt).block();
    bob.call(prompt).block();
    charlie.call(prompt).block();
}
```

### Debate Without Moderator

For simpler scenarios, you can run a debate without a moderator and let the debaters reach consensus:

```java
// Fixed number of rounds without moderator evaluation
for (int round = 0; round < 3; round++) {
    try (MsgHub hub = MsgHub.builder()
            .participants(alice, bob)
            .build()) {

        hub.enter().block();
        alice.call().block();
        bob.call().block();
    }
}

// Final synthesis
ReActAgent synthesizer = ReActAgent.builder()
        .name("Synthesizer")
        .sysPrompt("Synthesize all perspectives and provide a final answer.")
        .model(model)
        .build();

Msg finalAnswer = synthesizer.call(summaryMessage).block();
```

## Related Documentation

- [MsgHub](./msghub.md) - Message broadcasting for multi-agent conversations
- [Pipeline](./pipeline.md) - Sequential and parallel agent execution
- [Structured Output](../task/structured-output.md) - Extracting structured data from agent responses
