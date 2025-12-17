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

import io.agentscope.core.message.Msg;
import io.agentscope.examples.game.werewolf.entity.GameState;
import io.agentscope.examples.game.werewolf.entity.Player;
import io.agentscope.examples.game.werewolf.entity.Role;

/**
 * Interface for providing game prompts in different languages.
 *
 * <p>This interface defines all prompts that will be sent to agents during the game,
 * including system prompts for role initialization and various phase-specific prompts.
 */
public interface PromptProvider {

    /**
     * Get the system prompt for an agent based on their role.
     *
     * @param role Role of the agent
     * @param playerName Name of the player
     * @return System prompt describing the role and objectives
     */
    String getSystemPrompt(Role role, String playerName);

    /**
     * Create prompt for werewolf discussion phase.
     *
     * @param state Current game state
     * @return Msg containing discussion prompt
     */
    Msg createWerewolfDiscussionPrompt(GameState state);

    /**
     * Create prompt for werewolf voting phase.
     *
     * @param state Current game state
     * @return Msg containing voting prompt
     */
    Msg createWerewolfVotingPrompt(GameState state);

    /**
     * Create prompt for witch heal potion decision.
     *
     * @param victim Player who was attacked
     * @return Msg containing heal prompt
     */
    Msg createWitchHealPrompt(Player victim);

    /**
     * Create prompt for witch poison potion decision.
     *
     * @param state Current game state
     * @param usedHeal Whether heal potion was already used this night
     * @return Msg containing poison prompt
     */
    Msg createWitchPoisonPrompt(GameState state, boolean usedHeal);

    /**
     * Create prompt for seer check phase.
     *
     * @param state Current game state
     * @return Msg containing check prompt
     */
    Msg createSeerCheckPrompt(GameState state);

    /**
     * Create prompt to inform seer of check result.
     *
     * @param target Player who was checked
     * @return Msg containing result information
     */
    Msg createSeerResultPrompt(Player target);

    /**
     * Create announcement for night phase results.
     *
     * @param state Current game state
     * @return String containing night result announcement
     */
    String createNightResultAnnouncement(GameState state);

    /**
     * Create prompt for day discussion phase.
     *
     * @param state Current game state
     * @param round Discussion round number
     * @return Msg containing discussion prompt
     */
    Msg createDiscussionPrompt(GameState state, int round);

    /**
     * Create prompt for day voting phase.
     *
     * @param state Current game state
     * @return Msg containing voting prompt
     */
    Msg createVotingPrompt(GameState state);

    /**
     * Create prompt for hunter shoot decision.
     *
     * @param state Current game state
     * @param hunter Hunter player
     * @return Msg containing shoot prompt
     */
    Msg createHunterShootPrompt(GameState state, Player hunter);
}
