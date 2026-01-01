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
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    @Override
    public String getSystemPrompt(Role role, String playerName) {
        String key = "prompt.role." + role.name().toLowerCase();
        return msg(key, playerName);
    }

    @Override
    public Msg createWerewolfDiscussionPrompt(GameState state) {
        List<Player> aliveNonWerewolves =
                state.getAlivePlayers().stream().filter(p -> p.getRole() != Role.WEREWOLF).toList();

        StringBuilder prompt = new StringBuilder();
        prompt.append(msg("prompt.werewolf.discussion.header"));
        for (Player p : aliveNonWerewolves) {
            prompt.append("  - ").append(p.getName()).append("\n");
        }
        prompt.append(msg("prompt.werewolf.discussion.footer"));

        return buildMsg(prompt.toString());
    }

    @Override
    public Msg createWerewolfVotingPrompt(GameState state) {
        List<Player> aliveNonWerewolves =
                state.getAlivePlayers().stream().filter(p -> p.getRole() != Role.WEREWOLF).toList();

        StringBuilder prompt = new StringBuilder();
        prompt.append(msg("prompt.werewolf.voting.header"));
        for (Player p : aliveNonWerewolves) {
            prompt.append("  - ").append(p.getName()).append("\n");
        }
        prompt.append(msg("prompt.werewolf.voting.footer"));

        return buildMsg(prompt.toString());
    }

    @Override
    public Msg createWitchHealPrompt(Player victim) {
        String prompt = msg("prompt.witch.heal", victim.getName(), victim.getName());
        return buildMsg(prompt);
    }

    @Override
    public Msg createWitchPoisonPrompt(GameState state, boolean usedHeal) {
        List<Player> alivePlayers = state.getAlivePlayers();

        StringBuilder prompt = new StringBuilder();
        if (usedHeal) {
            prompt.append(msg("prompt.witch.poison.header.healed"));
        }
        prompt.append(msg("prompt.witch.poison.header"));
        for (Player p : alivePlayers) {
            if (p.getRole() != Role.WITCH) {
                prompt.append("  - ").append(p.getName()).append("\n");
            }
        }
        prompt.append(msg("prompt.witch.poison.footer"));

        return buildMsg(prompt.toString());
    }

    @Override
    public Msg createSeerCheckPrompt(GameState state) {
        List<Player> alivePlayers = state.getAlivePlayers();

        StringBuilder prompt = new StringBuilder();
        prompt.append(msg("prompt.seer.check.header"));
        for (Player p : alivePlayers) {
            if (p.getRole() != Role.SEER) {
                prompt.append("  - ").append(p.getName()).append("\n");
            }
        }

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

        if (nightVictim == null && poisonVictim == null) {
            announcement.append(msg("prompt.night.result.peaceful"));
        } else if (wasResurrected && poisonVictim == null) {
            announcement.append(msg("prompt.night.result.peaceful.healed"));
        } else {
            announcement.append(msg("prompt.night.result.deaths"));
            if (!wasResurrected && nightVictim != null) {
                announcement.append(msg("prompt.night.result.killed", nightVictim.getName()));
            }
            if (poisonVictim != null) {
                announcement.append(msg("prompt.night.result.poisoned", poisonVictim.getName()));
            }
        }

        announcement.append(msg("prompt.night.result.alive", state.getAlivePlayers().size()));
        for (Player p : state.getAlivePlayers()) {
            announcement.append("  - ").append(p.getName()).append("\n");
        }

        return announcement.toString();
    }

    @Override
    public Msg createDiscussionPrompt(GameState state, int round) {
        String prompt = msg("prompt.discussion.header", round);
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
}
