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
package io.agentscope.examples.werewolf.localization;

import io.agentscope.examples.werewolf.entity.Role;

/**
 * Interface for providing UI messages in different languages.
 *
 * <p>This interface defines all user-facing messages displayed during the game,
 * including titles, status messages, and announcements.
 */
public interface GameMessages {

    String getWelcomeTitle();

    String getWelcomeDescription();

    String getRoleSymbol(Role role);

    String getRoleDisplayName(Role role);

    String getPlayerAssignments();

    String getInitializingGame();

    String getNightPhaseTitle();

    String getDayPhaseTitle();

    String getVotingPhaseTitle();

    String getNightPhaseComplete();

    String getWerewolvesDiscussion();

    String getWerewolfDiscussionRound(int round);

    String getWerewolfVoting();

    String getWerewolvesChose(String name);

    String getWitchActions();

    String getWitchSeesVictim(String name);

    String getWitchHealDecision(String name, String decision, String reason);

    String getWitchUsedHeal(String name);

    String getWitchPoisonDecision(String name, String decision, String target, String reason);

    String getWitchUsedPoison(String name);

    String getSeerCheck();

    String getSeerCheckDecision(String seerName, String targetName, String reason);

    String getSeerCheckResult(String name, String identity);

    String getDayDiscussion();

    String getDiscussionRound(int round);

    String getVotingResults();

    String getNoValidVotes();

    String getTieMessage(String players, String selected);

    String getVoteCount(String name, int votes);

    String getPlayerEliminated(String name, String role);

    String getHunterShoot();

    String getHunterShootDecision(
            String hunterName, String decision, String targetName, String reason);

    String getHunterShotPlayer(String targetName, String role);

    String getHunterNoShoot();

    String getGameOver();

    String getVillagersWin();

    String getVillagersWinExplanation();

    String getWerewolvesWin();

    String getWerewolvesWinExplanation();

    String getMaxRoundsReached();

    String getFinalStatus();

    String getAlivePlayers();

    String getAllPlayersAndRoles();

    String getGameStatus(int round);

    String getAliveStatus(int alive, int werewolves, int villagers);

    String getStatusLabel(boolean isAlive);

    String getVoteParsingError(String name);

    String getErrorInDecision(String context);

    String getIsWerewolf();

    String getNotWerewolf();

    String getDecisionYes();

    String getDecisionNo();

    String getWitchHealYes();

    String getWitchPoisonYes();

    String getHunterShootYes();

    String getHunterShootNo();

    String getVoteDetail(String voterName, String targetName, String reason);

    String getSystemWerewolfKillResult(String playerName);

    String getSystemVotingResult(String playerName);

    String getSystemWerewolfDiscussing();

    String getSystemWitchActing();

    String getSystemWitchSeesVictim(String playerName);

    String getSystemSeerActing();

    String getSystemDayDiscussionStart();

    String getSystemVotingStart();

    String getSystemHunterSkill();

    String getActionWitchUseHeal();

    String getActionWitchHealResult();

    String getActionWitchHealSkip();

    String getActionWitchUsePoison();

    String getActionWitchPoisonResult();

    String getActionWitchPoisonSkip();

    String getActionSeerCheck();

    String getActionHunterShoot();

    String getActionHunterShootResult();

    String getActionHunterShootSkip();

    String getErrorWitchHeal(String error);

    String getErrorWitchPoison(String error);

    String getErrorSeerCheck(String error);

    String getErrorHunterShoot(String error);
}
