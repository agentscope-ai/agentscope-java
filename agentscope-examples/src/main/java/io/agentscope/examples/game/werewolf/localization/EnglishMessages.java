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
package io.agentscope.examples.game.werewolf.localization;

import io.agentscope.examples.game.werewolf.entity.Role;

/**
 * English implementation of GameMessages.
 */
public class EnglishMessages implements GameMessages {

    @Override
    public String getWelcomeTitle() {
        return "Werewolf Game - 9 Player Multi-Agent Game";
    }

    @Override
    public String getWelcomeDescription() {
        return "A complex social deduction game where players are divided into villagers and"
                + " werewolves.\n"
                + "Villagers must identify and eliminate werewolves through discussion and"
                + " voting.\n"
                + "Werewolves must eliminate villagers without revealing their identities.\n\n"
                + "Roles:\n"
                + "  - 3 Villagers: No special abilities\n"
                + "  - 3 Werewolves: Eliminate one villager each night\n"
                + "  - 1 Seer: Can check one player's identity each night\n"
                + "  - 1 Witch: Has heal and poison potions (one-time use each)\n"
                + "  - 1 Hunter: Can shoot one player when eliminated";
    }

    @Override
    public String getRoleSymbol(Role role) {
        return switch (role) {
            case VILLAGER -> "👤";
            case WEREWOLF -> "🐺";
            case SEER -> "🔮";
            case WITCH -> "🧪";
            case HUNTER -> "🏹";
        };
    }

    @Override
    public String getRoleDisplayName(Role role) {
        return switch (role) {
            case VILLAGER -> "Villager";
            case WEREWOLF -> "Werewolf";
            case SEER -> "Seer";
            case WITCH -> "Witch";
            case HUNTER -> "Hunter";
        };
    }

    @Override
    public String getPlayerAssignments() {
        return "Player Assignments:";
    }

    @Override
    public String getInitializingGame() {
        return "Initializing Werewolf Game";
    }

    @Override
    public String getNightPhaseTitle() {
        return "🌙 Night Phase - Everyone close your eyes...";
    }

    @Override
    public String getDayPhaseTitle() {
        return "☀️ Day Phase - Everyone open your eyes...";
    }

    @Override
    public String getVotingPhaseTitle() {
        return "🗳️ Voting Phase";
    }

    @Override
    public String getNightPhaseComplete() {
        return "🌙 Night phase complete. Waiting for sunrise...";
    }

    @Override
    public String getWerewolvesDiscussion() {
        return "--- Werewolves Discussion ---";
    }

    @Override
    public String getWerewolfDiscussionRound(int round) {
        return String.format("  Werewolf Discussion Round %d:", round);
    }

    @Override
    public String getWerewolfVoting() {
        return "  Werewolf Voting:";
    }

    @Override
    public String getWerewolvesChose(String name) {
        return "Werewolves chose to eliminate: " + name;
    }

    @Override
    public String getWitchActions() {
        return "--- Witch Actions ---";
    }

    @Override
    public String getWitchSeesVictim(String name) {
        return String.format("  Witch sees: %s was attacked by werewolves", name);
    }

    @Override
    public String getWitchHealDecision(String name, String decision, String reason) {
        return String.format("  [%s] Heal decision: %s (Reason: %s)", name, decision, reason);
    }

    @Override
    public String getWitchUsedHeal(String name) {
        return "  ✓ Witch used heal potion to save " + name;
    }

    @Override
    public String getWitchPoisonDecision(
            String name, String decision, String target, String reason) {
        return String.format(
                "  [%s] Poison decision: %s (Target: %s, Reason: %s)",
                name, decision, target, reason);
    }

    @Override
    public String getWitchUsedPoison(String name) {
        return "  ✓ Witch used poison potion on " + name;
    }

    @Override
    public String getSeerCheck() {
        return "--- Seer Check ---";
    }

    @Override
    public String getSeerCheckDecision(String seerName, String targetName, String reason) {
        return String.format(
                "  [%s] wants to check: %s (Reason: %s)", seerName, targetName, reason);
    }

    @Override
    public String getSeerCheckResult(String name, String identity) {
        return String.format("  ✓ Result: %s is %s", name, identity);
    }

    @Override
    public String getDayDiscussion() {
        return "--- Day Discussion ---";
    }

    @Override
    public String getDiscussionRound(int round) {
        return String.format("  Discussion Round %d:", round);
    }

    @Override
    public String getVotingResults() {
        return "\nVoting Results:";
    }

    @Override
    public String getNoValidVotes() {
        return "\nNo valid votes. No one is eliminated.";
    }

    @Override
    public String getTieMessage(String players, String selected) {
        return String.format("\nTie detected among: %s. Randomly selected: %s", players, selected);
    }

    @Override
    public String getVoteCount(String name, int votes) {
        return String.format("  %s: %d votes", name, votes);
    }

    @Override
    public String getPlayerEliminated(String name, String role) {
        return String.format("\n%s was eliminated by vote. They were a %s.", name, role);
    }

    @Override
    public String getHunterShoot() {
        return "--- Hunter's Last Shot ---";
    }

    @Override
    public String getHunterShootDecision(
            String hunterName, String decision, String targetName, String reason) {
        return String.format(
                "  [%s] Shoot decision: %s (Target: %s, Reason: %s)",
                hunterName, decision, targetName, reason);
    }

    @Override
    public String getHunterShotPlayer(String targetName, String role) {
        return String.format("  ✓ Hunter shot %s. They were a %s.", targetName, role);
    }

    @Override
    public String getHunterNoShoot() {
        return "  Hunter chose not to shoot.";
    }

    @Override
    public String getGameOver() {
        return "GAME OVER";
    }

    @Override
    public String getVillagersWin() {
        return "🎉 VILLAGERS WIN! 🎉";
    }

    @Override
    public String getVillagersWinExplanation() {
        return "All werewolves have been eliminated.";
    }

    @Override
    public String getWerewolvesWin() {
        return "🐺 WEREWOLVES WIN! 🐺";
    }

    @Override
    public String getWerewolvesWinExplanation() {
        return "Werewolves have overtaken the village.";
    }

    @Override
    public String getMaxRoundsReached() {
        return "Game ended without a clear winner (max rounds reached).";
    }

    @Override
    public String getFinalStatus() {
        return "\nFinal Status:";
    }

    @Override
    public String getAlivePlayers() {
        return "Alive players: ";
    }

    @Override
    public String getAllPlayersAndRoles() {
        return "\nAll players and their roles:";
    }

    @Override
    public String getGameStatus(int round) {
        return String.format("Round %d - Game Status", round);
    }

    @Override
    public String getAliveStatus(int alive, int werewolves, int villagers) {
        return String.format(
                "Alive: %d players | Werewolves: %d | Villagers: %d", alive, werewolves, villagers);
    }

    @Override
    public String getStatusLabel(boolean isAlive) {
        return isAlive ? "alive" : "dead";
    }

    @Override
    public String getVoteParsingError(String name) {
        return String.format("  [%s] vote parsing error", name);
    }

    @Override
    public String getErrorInDecision(String context) {
        return "  Error in " + context + " decision: ";
    }

    @Override
    public String getIsWerewolf() {
        return "a Werewolf";
    }

    @Override
    public String getNotWerewolf() {
        return "not a Werewolf";
    }

    @Override
    public String getDecisionYes() {
        return "YES";
    }

    @Override
    public String getDecisionNo() {
        return "NO";
    }

    @Override
    public String getWitchHealYes() {
        return "YES, use heal potion";
    }

    @Override
    public String getWitchPoisonYes() {
        return "YES, use poison";
    }

    @Override
    public String getHunterShootYes() {
        return "YES, will shoot";
    }

    @Override
    public String getHunterShootNo() {
        return "NO, won't shoot";
    }

    @Override
    public String getVoteDetail(String voterName, String targetName, String reason) {
        return String.format("  [%s] votes for: %s (Reason: %s)", voterName, targetName, reason);
    }

    @Override
    public String getSystemWerewolfKillResult(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return "Werewolves have decided to kill: no one";
        }
        return String.format("Werewolves have decided to kill: %s", playerName);
    }

    @Override
    public String getSystemVotingResult(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return "Voting result: no one (tie) will be eliminated.";
        }
        return String.format("Voting result: %s will be eliminated.", playerName);
    }
}
