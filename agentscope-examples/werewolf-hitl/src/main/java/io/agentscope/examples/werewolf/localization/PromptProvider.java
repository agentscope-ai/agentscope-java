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

import io.agentscope.core.message.Msg;
import io.agentscope.examples.werewolf.entity.GameState;
import io.agentscope.examples.werewolf.entity.Player;
import io.agentscope.examples.werewolf.entity.Role;
import java.util.List;

/**
 * Interface for providing game prompts in different languages.
 *
 * <p>This interface defines all prompts that will be sent to agents during the game, including
 * system prompts for role initialization and various phase-specific prompts.
 */
public interface PromptProvider {

    String getSystemPrompt(Role role, String[] args, String partner);

    Msg createWerewolfDiscussionPrompt(GameState state, Integer round);

    Msg createWerewolfVotingPrompt(GameState state);

    Msg createWitchHealPrompt(Player victim, GameState state);

    Msg createWitchPoisonPrompt(GameState state, boolean usedHeal);

    Msg createSeerCheckPrompt(GameState state);

    Msg createSeerResultPrompt(Player target);

    String createNightResultAnnouncement(GameState state);

    Msg createDiscussionPrompt(GameState state, String discussionOrders);

    Msg createVotingPrompt(GameState state);

    Msg createHunterShootPrompt(GameState state, Player hunter);

    Msg createSheriffElectionStartPrompt(GameState state, List<Player> candidates);

    // Sheriff election related prompts
    Msg createSheriffRegistrationPrompt(GameState state);

    Msg createSheriffCampaignPrompt(GameState state, Player candidate);

    Msg createSheriffVotingPrompt(GameState state, List<Player> candidates);

    Msg createSpeakOrderPrompt(Player sheriff);

    Msg createSpeakOrderFromPositionPrompt(GameState state, Player newSheriff);

    Msg createSheriffTransferPrompt(GameState state, Player sheriff);
}
