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
package io.agentscope.examples.werewolf;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.examples.werewolf.entity.GameState;
import io.agentscope.examples.werewolf.entity.Player;
import io.agentscope.examples.werewolf.localization.GameMessages;
import io.agentscope.examples.werewolf.model.VoteModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Utility methods for the Werewolf game.
 */
public class WerewolfUtils {

    private static final Random RANDOM = new Random();
    private final GameMessages messages;

    /**
     * Constructor with dependency injection.
     *
     * @param messages Game messages provider
     */
    public WerewolfUtils(GameMessages messages) {
        this.messages = messages;
    }

    /**
     * Counts votes and returns the player with the most votes.
     * In case of a tie, randomly selects one of the tied players.
     */
    public Player countVotes(List<Msg> votes, GameState state) {
        Map<String, Integer> voteCount = new HashMap<>();
        Map<String, String> voteReasons = new HashMap<>();

        // Count votes
        for (Msg voteMsg : votes) {
            try {
                VoteModel vote = voteMsg.getStructuredData(VoteModel.class);
                if (vote.targetPlayer != null && !vote.targetPlayer.isEmpty()) {
                    voteCount.put(
                            vote.targetPlayer, voteCount.getOrDefault(vote.targetPlayer, 0) + 1);
                    voteReasons.put(voteMsg.getName(), vote.reason);
                }
            } catch (Exception e) {
                System.err.println(
                        "Error parsing vote from " + voteMsg.getName() + ": " + e.getMessage());
            }
        }

        // Print voting results
        System.out.println(messages.getVotingResults());
        for (Map.Entry<String, Integer> entry : voteCount.entrySet()) {
            System.out.println(messages.getVoteCount(entry.getKey(), entry.getValue()));
        }

        // Find the player(s) with the most votes
        int maxVotes = voteCount.values().stream().max(Integer::compareTo).orElse(0);

        if (maxVotes == 0) {
            System.out.println(messages.getNoValidVotes());
            return null;
        }

        List<String> mostVotedPlayers =
                voteCount.entrySet().stream()
                        .filter(e -> e.getValue() == maxVotes)
                        .map(Map.Entry::getKey)
                        .toList();

        // Handle tie
        String votedOutName;
        if (mostVotedPlayers.size() > 1) {
            votedOutName = mostVotedPlayers.get(RANDOM.nextInt(mostVotedPlayers.size()));
            System.out.println(
                    messages.getTieMessage(String.join(", ", mostVotedPlayers), votedOutName));
        } else {
            votedOutName = mostVotedPlayers.get(0);
        }

        return state.findPlayerByName(votedOutName);
    }

    /**
     * Formats a list of players as a comma-separated string.
     */
    public String formatPlayerList(List<Player> players) {
        if (players.isEmpty()) {
            return "none";
        }
        return players.stream().map(Player::getName).reduce((a, b) -> a + ", " + b).orElse("");
    }

    /**
     * Prints the current game status.
     */
    public void printGameStatus(GameState state) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println(messages.getGameStatus(state.getCurrentRound()));
        System.out.println("=".repeat(60));
        System.out.println(
                messages.getAliveStatus(
                        state.getAlivePlayers().size(),
                        state.getAliveWerewolves().size(),
                        state.getAliveVillagers().size()));
        System.out.println("=".repeat(60) + "\n");
    }

    /**
     * Announces the game winner.
     */
    public void announceWinner(GameState state) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println(messages.getGameOver());
        System.out.println("=".repeat(60) + "\n");

        if (state.checkVillagersWin()) {
            System.out.println(messages.getVillagersWin());
            System.out.println(messages.getVillagersWinExplanation());
        } else if (state.checkWerewolvesWin()) {
            System.out.println(messages.getWerewolvesWin());
            System.out.println(messages.getWerewolvesWinExplanation());
        } else {
            System.out.println(messages.getMaxRoundsReached());
        }

        System.out.println(messages.getFinalStatus());
        System.out.println(messages.getAlivePlayers() + formatPlayerList(state.getAlivePlayers()));

        System.out.println(messages.getAllPlayersAndRoles());
        for (Player player : state.getAllPlayers()) {
            String status = messages.getStatusLabel(player.isAlive());
            String roleName = messages.getRoleDisplayName(player.getRole());
            System.out.println(String.format("  %s - %s (%s)", player.getName(), roleName, status));
        }

        System.out.println("\n" + "=".repeat(60));
    }

    /**
     * Prints a section header.
     */
    public void printSectionHeader(String title) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println(title);
        System.out.println("=".repeat(60) + "\n");
    }

    /**
     * Validates that a player name exists and is alive.
     */
    public boolean isValidAlivePlayer(String playerName, GameState state) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return false;
        }
        Player player = state.findPlayerByName(playerName);
        return player != null && player.isAlive();
    }

    /**
     * Extracts text content from a message for display.
     */
    public String extractTextContent(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        return msg.getContent().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }
}
