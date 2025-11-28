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
 * Interface for providing UI messages in different languages.
 *
 * <p>This interface defines all user-facing messages displayed during the game,
 * including titles, status messages, and announcements.
 */
public interface GameMessages {

    /**
     * Get the welcome message title.
     *
     * @return Welcome title
     */
    String getWelcomeTitle();

    /**
     * Get the welcome message description.
     *
     * @return Welcome description
     */
    String getWelcomeDescription();

    /**
     * Get the emoji symbol for a role.
     *
     * @param role Role to get symbol for
     * @return Emoji symbol
     */
    String getRoleSymbol(Role role);

    /**
     * Get the display name for a role.
     *
     * @param role Role to get name for
     * @return Display name
     */
    String getRoleDisplayName(Role role);

    /**
     * Get the "Player Assignments" header.
     *
     * @return Player assignments header
     */
    String getPlayerAssignments();

    /**
     * Get the "Initializing Game" header.
     *
     * @return Initializing header
     */
    String getInitializingGame();

    /**
     * Get the night phase title.
     *
     * @return Night phase title
     */
    String getNightPhaseTitle();

    /**
     * Get the day phase title.
     *
     * @return Day phase title
     */
    String getDayPhaseTitle();

    /**
     * Get the voting phase title.
     *
     * @return Voting phase title
     */
    String getVotingPhaseTitle();

    /**
     * Get the "Night phase complete" message.
     *
     * @return Night complete message
     */
    String getNightPhaseComplete();

    /**
     * Get the werewolves discussion header.
     *
     * @return Werewolves discussion header
     */
    String getWerewolvesDiscussion();

    /**
     * Get the werewolf discussion round message.
     *
     * @param round Round number
     * @return Discussion round message
     */
    String getWerewolfDiscussionRound(int round);

    /**
     * Get the werewolf voting header.
     *
     * @return Werewolf voting header
     */
    String getWerewolfVoting();

    /**
     * Get message for werewolves choosing a victim.
     *
     * @param name Victim name
     * @return Message
     */
    String getWerewolvesChose(String name);

    /**
     * Get the witch actions header.
     *
     * @return Witch actions header
     */
    String getWitchActions();

    /**
     * Get message for witch seeing victim.
     *
     * @param name Victim name
     * @return Message
     */
    String getWitchSeesVictim(String name);

    /**
     * Get message for witch heal decision.
     *
     * @param name Witch name
     * @param decision Decision (YES/NO)
     * @param reason Reason
     * @return Message
     */
    String getWitchHealDecision(String name, String decision, String reason);

    /**
     * Get message for witch using heal potion.
     *
     * @param name Saved player name
     * @return Message
     */
    String getWitchUsedHeal(String name);

    /**
     * Get message for witch poison decision.
     *
     * @param name Witch name
     * @param decision Decision (YES/NO)
     * @param target Target name
     * @param reason Reason
     * @return Message
     */
    String getWitchPoisonDecision(String name, String decision, String target, String reason);

    /**
     * Get message for witch using poison potion.
     *
     * @param name Poisoned player name
     * @return Message
     */
    String getWitchUsedPoison(String name);

    /**
     * Get the seer check header.
     *
     * @return Seer check header
     */
    String getSeerCheck();

    /**
     * Get message for seer checking player.
     *
     * @param seerName Seer name
     * @param targetName Target name
     * @param reason Reason
     * @return Message
     */
    String getSeerCheckDecision(String seerName, String targetName, String reason);

    /**
     * Get message for seer check result.
     *
     * @param name Checked player name
     * @param identity Identity result
     * @return Message
     */
    String getSeerCheckResult(String name, String identity);

    /**
     * Get the day discussion header.
     *
     * @return Day discussion header
     */
    String getDayDiscussion();

    /**
     * Get the discussion round message.
     *
     * @param round Round number
     * @return Discussion round message
     */
    String getDiscussionRound(int round);

    /**
     * Get the voting results header.
     *
     * @return Voting results header
     */
    String getVotingResults();

    /**
     * Get the no valid votes message.
     *
     * @return No valid votes message
     */
    String getNoValidVotes();

    /**
     * Get the tie detected message.
     *
     * @param players Tied players
     * @param selected Selected player
     * @return Tie message
     */
    String getTieMessage(String players, String selected);

    /**
     * Get the vote count message.
     *
     * @param name Player name
     * @param votes Vote count
     * @return Vote count message
     */
    String getVoteCount(String name, int votes);

    /**
     * Get the player eliminated message.
     *
     * @param name Player name
     * @param role Role name
     * @return Elimination message
     */
    String getPlayerEliminated(String name, String role);

    /**
     * Get the hunter shoot header.
     *
     * @return Hunter shoot header
     */
    String getHunterShoot();

    /**
     * Get message for hunter shoot decision.
     *
     * @param hunterName Hunter name
     * @param decision Decision (YES/NO)
     * @param targetName Target name
     * @param reason Reason
     * @return Message
     */
    String getHunterShootDecision(
            String hunterName, String decision, String targetName, String reason);

    /**
     * Get message for hunter shooting player.
     *
     * @param targetName Target name
     * @param role Target role
     * @return Message
     */
    String getHunterShotPlayer(String targetName, String role);

    /**
     * Get message for hunter not shooting.
     *
     * @return Message
     */
    String getHunterNoShoot();

    /**
     * Get the game over header.
     *
     * @return Game over header
     */
    String getGameOver();

    /**
     * Get the villagers win message.
     *
     * @return Villagers win message
     */
    String getVillagersWin();

    /**
     * Get the villagers win explanation.
     *
     * @return Win explanation
     */
    String getVillagersWinExplanation();

    /**
     * Get the werewolves win message.
     *
     * @return Werewolves win message
     */
    String getWerewolvesWin();

    /**
     * Get the werewolves win explanation.
     *
     * @return Win explanation
     */
    String getWerewolvesWinExplanation();

    /**
     * Get the max rounds reached message.
     *
     * @return Max rounds message
     */
    String getMaxRoundsReached();

    /**
     * Get the final status header.
     *
     * @return Final status header
     */
    String getFinalStatus();

    /**
     * Get the alive players label.
     *
     * @return Alive players label
     */
    String getAlivePlayers();

    /**
     * Get the all players and roles header.
     *
     * @return All players header
     */
    String getAllPlayersAndRoles();

    /**
     * Get the game status header.
     *
     * @param round Round number
     * @return Game status header
     */
    String getGameStatus(int round);

    /**
     * Get the alive status message.
     *
     * @param alive Alive count
     * @param werewolves Werewolf count
     * @param villagers Villager count
     * @return Status message
     */
    String getAliveStatus(int alive, int werewolves, int villagers);

    /**
     * Get the status label (alive/dead).
     *
     * @param isAlive Whether player is alive
     * @return Status label
     */
    String getStatusLabel(boolean isAlive);

    /**
     * Get the vote parsing error message.
     *
     * @param name Player name
     * @return Error message
     */
    String getVoteParsingError(String name);

    /**
     * Get the error in decision message.
     *
     * @param context Decision context
     * @return Error message
     */
    String getErrorInDecision(String context);

    /**
     * Get the "is werewolf" identity text.
     *
     * @return Identity text
     */
    String getIsWerewolf();

    /**
     * Get the "not werewolf" identity text.
     *
     * @return Identity text
     */
    String getNotWerewolf();

    /**
     * Get the "YES" decision text.
     *
     * @return YES text
     */
    String getDecisionYes();

    /**
     * Get the "NO" decision text.
     *
     * @return NO text
     */
    String getDecisionNo();

    /**
     * Get the witch heal "YES" decision text.
     *
     * @return Witch heal YES text
     */
    String getWitchHealYes();

    /**
     * Get the witch poison "YES" decision text.
     *
     * @return Witch poison YES text
     */
    String getWitchPoisonYes();

    /**
     * Get the hunter shoot "YES" decision text.
     *
     * @return Hunter shoot YES text
     */
    String getHunterShootYes();

    /**
     * Get the hunter shoot "NO" decision text.
     *
     * @return Hunter shoot NO text
     */
    String getHunterShootNo();

    /**
     * Get the vote detail format.
     *
     * @param voterName Voter name
     * @param targetName Target player name
     * @param reason Reason
     * @return Formatted vote detail
     */
    String getVoteDetail(String voterName, String targetName, String reason);

    /**
     * Get the system message for werewolf kill result.
     *
     * @param playerName Name of the killed player, or null/empty if no one
     * @return System message for werewolf kill decision
     */
    String getSystemWerewolfKillResult(String playerName);

    /**
     * Get the system message for voting elimination result.
     *
     * @param playerName Name of the eliminated player, or null/empty if no one
     * @return System message for voting elimination result
     */
    String getSystemVotingResult(String playerName);
}
