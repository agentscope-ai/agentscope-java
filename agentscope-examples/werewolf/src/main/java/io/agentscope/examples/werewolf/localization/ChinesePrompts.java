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
 * Chinese implementation of PromptProvider.
 */
public class ChinesePrompts implements PromptProvider {

    @Override
    public String getSystemPrompt(Role role, String playerName) {
        return switch (role) {
            case VILLAGER ->
                    String.format(
                            "你是%s，狼人杀游戏中的一名普通村民。\n"
                                    + "你的目标是通过讨论和投票找出并消灭所有狼人。\n"
                                    + "仔细观察其他玩家的行为，明智地投票。\n"
                                    + "你没有特殊能力，但你的分析能力至关重要。\n"
                                    + "请保持回复简洁（2-3句话）。",
                            playerName);

            case WEREWOLF ->
                    String.format(
                            "你是%s，一名狼人。\n"
                                    + "你的目标是在不暴露身份的情况下消灭所有村民。\n"
                                    + "在夜晚阶段，与其他狼人协调选择一个受害者。\n"
                                    + "在白天阶段，混入村民中并转移嫌疑。\n"
                                    + "请保持回复简洁（2-3句话）。",
                            playerName);

            case SEER ->
                    String.format(
                            "你是%s，预言家（特殊村民角色）。\n"
                                    + "你的目标是帮助村民识别狼人。\n"
                                    + "每个夜晚，你可以查验一名玩家的真实身份（是否为狼人）。\n"
                                    + "谨慎使用这个信息 - 过早暴露自己可能会成为狼人的目标。\n"
                                    + "请保持回复简洁（2-3句话）。",
                            playerName);

            case WITCH ->
                    String.format(
                            "你是%s，女巫（特殊村民角色）。\n"
                                    + "你的目标是帮助村民消灭狼人。\n"
                                    + "你有两瓶药水（每瓶只能使用一次）：\n"
                                    + "  - 解药：可以在每个夜晚救活被狼人杀死的玩家\n"
                                    + "  - 毒药：可以在每个夜晚毒死一名玩家\n"
                                    + "策略性地使用你的药水 - 它们在整个游戏中只能各使用一次。\n"
                                    + "请保持回复简洁（2-3句话）。",
                            playerName);

            case HUNTER ->
                    String.format(
                            "你是%s，猎人（特殊村民角色）。\n"
                                    + "你的目标是帮助村民消灭狼人。\n"
                                    + "当你被淘汰时（被狼人杀死或被投票出局），你可以选择射杀一名玩家。\n"
                                    + "你的射击可以改变游戏的走向 - 明智地使用它。\n"
                                    + "请保持回复简洁（2-3句话）。",
                            playerName);
        };
    }

    @Override
    public Msg createWerewolfDiscussionPrompt(GameState state) {
        List<Player> aliveNonWerewolves =
                state.getAlivePlayers().stream().filter(p -> p.getRole() != Role.WEREWOLF).toList();

        StringBuilder prompt = new StringBuilder();
        prompt.append("夜晚阶段 - 狼人们，请睁开眼睛。\n\n");
        prompt.append("你们需要选择今晚要消灭的玩家。\n\n");
        prompt.append("可选目标：\n");
        for (Player p : aliveNonWerewolves) {
            prompt.append(String.format("  - %s\n", p.getName()));
        }
        prompt.append("\n与你的狼人同伴讨论并就消灭谁达成共识。");

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
        prompt.append("现在投票选择你们今晚要消灭的玩家。\n\n");
        prompt.append("可选目标：\n");
        for (Player p : aliveNonWerewolves) {
            prompt.append(String.format("  - %s\n", p.getName()));
        }
        prompt.append("\n你必须选择一名玩家。");

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
                        "女巫，请睁开眼睛。\n\n"
                                + "今晚，%s被狼人攻击了。\n\n"
                                + "你有一瓶解药。你想用它来救%s吗？\n"
                                + "记住：这瓶药在整个游戏中只能使用一次。",
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
            prompt.append("你已经使用了解药。\n\n");
        }
        prompt.append("你有一瓶毒药。你想用它在今晚毒死某人吗？\n\n");
        prompt.append("可选目标：\n");
        for (Player p : alivePlayers) {
            if (p.getRole() != Role.WITCH) {
                prompt.append(String.format("  - %s\n", p.getName()));
            }
        }
        prompt.append("\n记住：这瓶药在整个游戏中只能使用一次。");

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
        prompt.append("预言家，请睁开眼睛。\n\n");
        prompt.append("选择一名玩家来查验他们的身份。\n\n");
        prompt.append("可选玩家：\n");
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
        String identity = target.getRole() == Role.WEREWOLF ? "狼人" : "不是狼人";
        String prompt = String.format("你查验了%s。他们%s。", target.getName(), identity);

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
        announcement.append("白天阶段 - 所有人请睁开眼睛。\n");
        announcement.append("=".repeat(60) + "\n\n");

        Player nightVictim = state.getLastNightVictim();
        Player poisonVictim = state.getLastPoisonedVictim();
        boolean wasResurrected = state.isLastVictimResurrected();

        if (nightVictim == null && poisonVictim == null) {
            announcement.append("昨晚是平安夜。没有人死亡。\n");
        } else if (wasResurrected && poisonVictim == null) {
            announcement.append("昨晚是平安夜。没有人死亡。\n");
            announcement.append("（女巫使用了解药）\n");
        } else {
            announcement.append("昨晚，以下玩家死亡：\n");
            if (!wasResurrected && nightVictim != null) {
                announcement.append(String.format("  - %s（被狼人杀死）\n", nightVictim.getName()));
            }
            if (poisonVictim != null) {
                announcement.append(String.format("  - %s（被女巫毒死）\n", poisonVictim.getName()));
            }
        }

        announcement.append("\n存活玩家（" + state.getAlivePlayers().size() + "人）：\n");
        for (Player p : state.getAlivePlayers()) {
            announcement.append(String.format("  - %s\n", p.getName()));
        }

        return announcement.toString();
    }

    @Override
    public Msg createDiscussionPrompt(GameState state, int round) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format("\n讨论环节 第%d轮\n", round));
        prompt.append("-".repeat(40) + "\n");
        prompt.append("分享你的想法、怀疑和观察。\n");
        prompt.append("根据讨论尝试识别狼人。\n");
        prompt.append("请保持发言简洁（2-3句话）。");

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
        prompt.append("投票阶段\n");
        prompt.append("=".repeat(60) + "\n\n");
        prompt.append("投票选择你想要淘汰的玩家。\n\n");
        prompt.append("可选玩家：\n");
        for (Player p : alivePlayers) {
            prompt.append(String.format("  - %s\n", p.getName()));
        }
        prompt.append("\n你必须投票给一名玩家。");

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
        prompt.append(String.format("\n%s，你是猎人，你已经被淘汰了。\n\n", hunter.getName()));
        prompt.append("你可以在死前选择射杀一名玩家。\n\n");
        prompt.append("可选目标：\n");
        for (Player p : alivePlayers) {
            if (!p.equals(hunter)) {
                prompt.append(String.format("  - %s\n", p.getName()));
            }
        }
        prompt.append("\n你想开枪吗？如果是，你想射杀谁？");

        return Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(prompt.toString()).build())
                .build();
    }
}
