/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.werewolf.localization;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.examples.werewolf.entity.GameState;
import io.agentscope.examples.werewolf.entity.Player;
import io.agentscope.examples.werewolf.entity.Role;
import java.util.List;

/**
 * English implementation of PromptProvider.
 */
public class EnglishPrompts implements PromptProvider {

    @Override
    public String getSystemPrompt(Role role, String playerName) {
        return switch (role) {
            case VILLAGER ->
                    String.format(
                            "You are %s, a Villager in the Werewolf game.\n"
                                + "Your goal is to identify and eliminate all werewolves through"
                                + " discussion and voting.\n"
                                + "Observe other players' behaviors carefully and vote wisely.\n"
                                + "You have no special abilities, but your analytical skills are"
                                + " crucial.\n"
                                + "Keep your responses concise (2-3 sentences).",
                            playerName);

            case WEREWOLF ->
                    String.format(
                            "You are %s, a Werewolf.\n"
                                + "Your goal is to eliminate all villagers without exposing your"
                                + " identity.\n"
                                + "During night phase, coordinate with other werewolves to choose a"
                                + " victim.\n"
                                + "During day phase, blend in with villagers and deflect"
                                + " suspicion.\n"
                                + "Keep your responses concise (2-3 sentences).",
                            playerName);

            case SEER ->
                    String.format(
                            "You are %s, the Seer (a special villager role).\n"
                                + "Your goal is to help villagers identify werewolves.\n"
                                + "Each night, you can check one player's true identity (Werewolf"
                                + " or not).\n"
                                + "Use this information carefully - revealing yourself too early"
                                + " may make you a target.\n"
                                + "Keep your responses concise (2-3 sentences).",
                            playerName);

            case WITCH ->
                    String.format(
                            "You are %s, the Witch (a special villager role).\n"
                                + "Your goal is to help villagers eliminate werewolves.\n"
                                + "You have two potions (one-time use each):\n"
                                + "  - Heal Potion: Can resurrect the werewolves' victim each"
                                + " night\n"
                                + "  - Poison Potion: Can kill one player each night\n"
                                + "Use your potions strategically - they can only be used once per"
                                + " game.\n"
                                + "Keep your responses concise (2-3 sentences).",
                            playerName);

            case HUNTER ->
                    String.format(
                            "You are %s, the Hunter (a special villager role).\n"
                                + "Your goal is to help villagers eliminate werewolves.\n"
                                + "When you are eliminated (by werewolves or by voting), you can"
                                + " choose to shoot one player.\n"
                                + "Your shot can change the course of the game - use it wisely.\n"
                                + "Keep your responses concise (2-3 sentences).",
                            playerName);
        };
    }

    @Override
    public Msg createWerewolfDiscussionPrompt(GameState state) {
        List<Player> aliveNonWerewolves =
                state.getAlivePlayers().stream().filter(p -> p.getRole() != Role.WEREWOLF).toList();

        StringBuilder prompt = new StringBuilder();
        prompt.append("Night phase - Werewolves, open your eyes.\n\n");
        prompt.append("You need to choose one player to eliminate tonight.\n\n");
        prompt.append("Available targets:\n");
        for (Player p : aliveNonWerewolves) {
            prompt.append(String.format("  - %s\n", p.getName()));
        }
        prompt.append(
                "\nDiscuss with your fellow werewolves and reach a consensus on who to eliminate.");

        return Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(prompt.toString()).build())
                .build();
    }

    @Override
    public Msg createWerewolfVotingPrompt(GameState state) {
        List<Player> aliveNonWerewolves =
                state.getAlivePlayers().stream().filter(p -> p.getRole() != Role.WEREWOLF).toList();

        StringBuilder prompt = new StringBuilder();
        prompt.append("Now vote for the player you want to eliminate tonight.\n\n");
        prompt.append("Available targets:\n");
        for (Player p : aliveNonWerewolves) {
            prompt.append(String.format("  - %s\n", p.getName()));
        }
        prompt.append("\nYou must choose one player.");

        return Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(prompt.toString()).build())
                .build();
    }

    @Override
    public Msg createWitchHealPrompt(Player victim) {
        String prompt =
                String.format(
                        "Witch, open your eyes.\n\n"
                                + "Tonight, %s was attacked by werewolves.\n\n"
                                + "You have a Heal Potion. Do you want to use it to save %s?\n"
                                + "Remember: You can only use this potion once per game.",
                        victim.getName(), victim.getName());

        return Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(prompt).build())
                .build();
    }

    @Override
    public Msg createWitchPoisonPrompt(GameState state, boolean usedHeal) {
        List<Player> alivePlayers = state.getAlivePlayers();

        StringBuilder prompt = new StringBuilder();
        if (usedHeal) {
            prompt.append("You have used your Heal Potion.\n\n");
        }
        prompt.append(
                "You have a Poison Potion. Do you want to use it to kill someone tonight?\n\n");
        prompt.append("Available targets:\n");
        for (Player p : alivePlayers) {
            if (p.getRole() != Role.WITCH) {
                prompt.append(String.format("  - %s\n", p.getName()));
            }
        }
        prompt.append("\nRemember: You can only use this potion once per game.");

        return Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(prompt.toString()).build())
                .build();
    }

    @Override
    public Msg createSeerCheckPrompt(GameState state) {
        List<Player> alivePlayers = state.getAlivePlayers();

        StringBuilder prompt = new StringBuilder();
        prompt.append("Seer, open your eyes.\n\n");
        prompt.append("Choose one player to check their identity.\n\n");
        prompt.append("Available players:\n");
        for (Player p : alivePlayers) {
            if (p.getRole() != Role.SEER) {
                prompt.append(String.format("  - %s\n", p.getName()));
            }
        }

        return Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(prompt.toString()).build())
                .build();
    }

    @Override
    public Msg createSeerResultPrompt(Player target) {
        String identity = target.getRole() == Role.WEREWOLF ? "a Werewolf" : "not a Werewolf";
        String prompt = String.format("You checked %s. They are %s.", target.getName(), identity);

        return Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(prompt).build())
                .build();
    }

    @Override
    public String createNightResultAnnouncement(GameState state) {
        StringBuilder announcement = new StringBuilder();
        announcement.append("\n" + "=".repeat(60) + "\n");
        announcement.append("Day Phase - Everyone open your eyes.\n");
        announcement.append("=".repeat(60) + "\n\n");

        Player nightVictim = state.getLastNightVictim();
        Player poisonVictim = state.getLastPoisonedVictim();
        boolean wasResurrected = state.isLastVictimResurrected();

        if (nightVictim == null && poisonVictim == null) {
            announcement.append("Last night was peaceful. No one died.\n");
        } else if (wasResurrected && poisonVictim == null) {
            announcement.append("Last night was peaceful. No one died.\n");
            announcement.append("(The Witch used the heal potion)\n");
        } else {
            announcement.append("Last night, the following players died:\n");
            if (!wasResurrected && nightVictim != null) {
                announcement.append(
                        String.format("  - %s (killed by werewolves)\n", nightVictim.getName()));
            }
            if (poisonVictim != null) {
                announcement.append(
                        String.format("  - %s (poisoned by witch)\n", poisonVictim.getName()));
            }
        }

        announcement.append("\nAlive players (" + state.getAlivePlayers().size() + "):\n");
        for (Player p : state.getAlivePlayers()) {
            announcement.append(String.format("  - %s\n", p.getName()));
        }

        return announcement.toString();
    }

    @Override
    public Msg createDiscussionPrompt(GameState state, int round) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format("\nDiscussion Round %d\n", round));
        prompt.append("-".repeat(40) + "\n");
        prompt.append("Share your thoughts, suspicions, and observations.\n");
        prompt.append("Try to identify the werewolves based on the discussion.\n");
        prompt.append("Keep your statement concise (2-3 sentences).");

        return Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(prompt.toString()).build())
                .build();
    }

    @Override
    public Msg createVotingPrompt(GameState state) {
        List<Player> alivePlayers = state.getAlivePlayers();

        StringBuilder prompt = new StringBuilder();
        prompt.append("\n" + "=".repeat(60) + "\n");
        prompt.append("Voting Phase\n");
        prompt.append("=".repeat(60) + "\n\n");
        prompt.append("Vote for the player you want to eliminate.\n\n");
        prompt.append("Available players:\n");
        for (Player p : alivePlayers) {
            prompt.append(String.format("  - %s\n", p.getName()));
        }
        prompt.append("\nYou must vote for one player.");

        return Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(prompt.toString()).build())
                .build();
    }

    @Override
    public Msg createHunterShootPrompt(GameState state, Player hunter) {
        List<Player> alivePlayers = state.getAlivePlayers();

        StringBuilder prompt = new StringBuilder();
        prompt.append(
                String.format(
                        "\n%s, you are the Hunter and you have been eliminated.\n\n",
                        hunter.getName()));
        prompt.append("You can choose to shoot one player before you die.\n\n");
        prompt.append("Available targets:\n");
        for (Player p : alivePlayers) {
            if (!p.equals(hunter)) {
                prompt.append(String.format("  - %s\n", p.getName()));
            }
        }
        prompt.append("\nDo you want to shoot? If yes, who do you want to shoot?");

        return Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(prompt.toString()).build())
                .build();
    }
}
