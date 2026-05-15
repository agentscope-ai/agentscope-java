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
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.examples.werewolf.entity.GameState;
import io.agentscope.examples.werewolf.entity.Player;
import io.agentscope.examples.werewolf.entity.Role;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;

public class MessageSourcePromptProvider implements PromptProvider {

    private final MessageSource messageSource;
    private final Locale locale;

    public MessageSourcePromptProvider(MessageSource messageSource, Locale locale) {
        this.messageSource = messageSource;
        this.locale = locale;
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, locale);
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    private Msg buildMsg(String text) {
        return Msg.builder()
                .name("Moderator Message")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    @Override
    public String getSystemPrompt(Role role, String[] args, String partiner) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(msg("prompt.role", args)).append("\n");
        if (partiner != null && !partiner.isEmpty()) {
            prompt.append(msg("prompt.partiner", partiner)).append("\n");
        }
        prompt.append(msg("prompt.info_source")).append("\n");
        prompt.append(msg("prompt.history_guide")).append("\n");
        prompt.append(msg("prompt.important_tip")).append("\n");

        return prompt.toString();
    }

    @Override
    public Msg createWerewolfDiscussionPrompt(GameState state, Integer round) {
        StringBuilder prompt = new StringBuilder();
        String alivePlayers =
                state.getAlivePlayers().stream()
                        .map(Player::getName)
                        .collect(Collectors.joining(", "));
        prompt.append(msg("prompt.werewolf.discussion", alivePlayers));

        if (round.equals(1)) {
            prompt.append(msg("prompt.werewolf.first.night.tips"));
        }

        return buildMsg(prompt.toString());
    }

    @Override
    public Msg createWerewolfVotingPrompt(GameState state) {
        List<Player> targetPlayers;

        // First night: werewolves can target anyone (including themselves for self-kill strategy)
        // Later nights: only non-werewolves
        if (state.getCurrentRound() == 1) {
            targetPlayers = state.getAlivePlayers();
        } else {
            targetPlayers =
                    state.getAlivePlayers().stream()
                            .filter(p -> p.getRole() != Role.WEREWOLF)
                            .toList();
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append(msg("prompt.night.header", state.getCurrentRound()));
        String targetPlayersText =
                targetPlayers.stream().map(Player::getName).collect(Collectors.joining(", "));
        prompt.append(msg("prompt.werewolf.voting.header", targetPlayersText));
        prompt.append(msg("prompt.werewolf.voting.footer"));
        return buildMsg(prompt.toString());
    }

    @Override
    public Msg createWitchHealPrompt(Player victim, GameState state) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(msg("prompt.night.header", state.getCurrentRound()));
        prompt.append(msg("prompt.witch.heal", victim.getName(), victim.getName()));
        return buildMsg(prompt.toString());
    }

    @Override
    public Msg createWitchPoisonPrompt(GameState state, boolean usedHeal) {
        List<Player> alivePlayers = state.getAlivePlayers();

        StringBuilder prompt = new StringBuilder();
        prompt.append(msg("prompt.night.header", state.getCurrentRound()));
        if (usedHeal) {
            prompt.append(msg("prompt.witch.poison.header.healed"));
        }
        String alivePlayersText =
                alivePlayers.stream().map(Player::getName).collect(Collectors.joining(", "));
        prompt.append(msg("prompt.witch.poison.header", alivePlayersText));
        prompt.append(msg("prompt.witch.poison.footer"));

        return buildMsg(prompt.toString());
    }

    @Override
    public Msg createSeerCheckPrompt(GameState state) {
        List<Player> alivePlayers = state.getAlivePlayers();

        StringBuilder prompt = new StringBuilder();
        prompt.append(msg("prompt.night.header", state.getCurrentRound()));
        String alivePlayersText =
                alivePlayers.stream()
                        .filter(p -> p.getRole() != Role.SEER)
                        .map(Player::getName)
                        .collect(Collectors.joining(", "));
        prompt.append(msg("prompt.seer.check.header", alivePlayersText));

        return buildMsg(prompt.toString());
    }

    @Override
    public Msg createSeerResultPrompt(Player target) {
        String identity =
                target.getRole() == Role.WEREWOLF
                        ? msg("prompt.seer.is.werewolf")
                        : msg("prompt.seer.not.werewolf");
        String prompt = msg("prompt.seer.result", target.getName(), identity);
        return buildMsg(prompt);
    }

    @Override
    public String createNightResultAnnouncement(GameState state) {
        StringBuilder announcement = new StringBuilder();
        announcement.append(msg("prompt.night.result.header"));

        Player nightVictim = state.getLastNightVictim();
        Player poisonVictim = state.getLastPoisonedVictim();
        boolean wasResurrected = state.isLastVictimResurrected();

        // Only announce deaths, not witch actions
        if (nightVictim == null && poisonVictim == null) {
            announcement.append(msg("prompt.night.result.peaceful"));
        } else if (wasResurrected && poisonVictim == null) {
            // Healed by witch, but don't reveal this info - just say peaceful night
            announcement.append(msg("prompt.night.result.peaceful"));
        } else {
            announcement.append(msg("prompt.night.result.deaths"));
            if (!wasResurrected && nightVictim != null) {
                announcement.append("  - ").append(nightVictim.getName()).append("\n");
            }
            if (poisonVictim != null) {
                announcement.append("  - ").append(poisonVictim.getName()).append("\n");
            }
        }

        announcement.append(msg("prompt.night.result.alive", state.getAlivePlayers().size()));
        for (Player p : state.getAlivePlayers()) {
            announcement.append("  - ").append(p.getName()).append("\n");
        }

        return announcement.toString();
    }

    @Override
    public Msg createDiscussionPrompt(GameState state, String discussionOrders) {
        String prompt = msg("prompt.discussion.header", discussionOrders);
        return buildMsg(prompt);
    }

    @Override
    public Msg createVotingPrompt(GameState state) {
        List<Player> alivePlayers = state.getAlivePlayers();

        StringBuilder prompt = new StringBuilder();
        prompt.append(msg("prompt.voting.header"));
        for (Player p : alivePlayers) {
            prompt.append("  - ").append(p.getName()).append("\n");
        }
        prompt.append(msg("prompt.voting.footer"));

        return buildMsg(prompt.toString());
    }

    @Override
    public Msg createHunterShootPrompt(GameState state, Player hunter) {
        List<Player> alivePlayers = state.getAlivePlayers();

        StringBuilder prompt = new StringBuilder();
        prompt.append(msg("prompt.hunter.header", hunter.getName()));
        for (Player p : alivePlayers) {
            if (!p.equals(hunter)) {
                prompt.append("  - ").append(p.getName()).append("\n");
            }
        }
        prompt.append(msg("prompt.hunter.footer"));

        return buildMsg(prompt.toString());
    }

    @Override
    public Msg createSheriffElectionStartPrompt(GameState state, List<Player> candidates) {
        StringBuilder prompt = new StringBuilder(msg("prompt.sheriff.election.start"));
        prompt.append(",上警的玩家有")
                .append(candidates.stream().map(Player::getName).collect(Collectors.joining(",")))
                .append("。");
        prompt.append("发言顺序从");
        prompt.append(candidates.stream().map(Player::getName).collect(Collectors.joining("->")));
        return buildMsg(prompt.toString());
    }

    @Override
    public Msg createSheriffRegistrationPrompt(GameState state) {
        String prompt = msg("prompt.sheriff.register");
        return buildMsg(prompt);
    }

    @Override
    public Msg createSheriffCampaignPrompt(GameState state, Player candidate) {
        String prompt;
        prompt = msg("prompt.sheriff.campaign", candidate.getName());
        return buildMsg(prompt);
    }

    @Override
    public Msg createSheriffVotingPrompt(GameState state, List<Player> candidates) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(msg("prompt.sheriff.voting.header"));
        for (Player c : candidates) {
            prompt.append("  - ").append(c.getName()).append("\n");
        }
        prompt.append(msg("prompt.sheriff.voting.footer"));
        return buildMsg(prompt.toString());
    }

    @Override
    public Msg createSpeakOrderPrompt(Player sheriff) {
        String prompt = msg("prompt.sheriff.speak.order", sheriff.getName());
        return buildMsg(prompt);
    }

    @Override
    public Msg createSpeakOrderFromPositionPrompt(GameState state, Player newSheriff) {
        String prompt = msg("prompt.sheriff.speak.order.from.position", newSheriff.getName());
        return buildMsg(prompt);
    }

    @Override
    public Msg createSheriffTransferPrompt(GameState state, Player sheriff) {
        List<Player> alivePlayers = state.getAlivePlayers();
        StringBuilder prompt = new StringBuilder();
        if (sheriff.getRole() == Role.SEER) {
            prompt.append(msg("prompt.sheriff.transfer.seer", sheriff.getName()));
        } else {
            prompt.append(msg("prompt.sheriff.transfer.default", sheriff.getName()));
        }
        for (Player p : alivePlayers) {
            if (!p.equals(sheriff)) {
                prompt.append("  - ").append(p.getName()).append("\n");
            }
        }
        prompt.append(msg("prompt.sheriff.transfer.footer"));
        return buildMsg(prompt.toString());
    }
}
