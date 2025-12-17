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
 * Chinese implementation of GameMessages.
 */
public class ChineseMessages implements GameMessages {

    @Override
    public String getWelcomeTitle() {
        return "狼人杀游戏 - 9人多智能体对战";
    }

    @Override
    public String getWelcomeDescription() {
        return "一个复杂的社交推理游戏，玩家分为村民和狼人两个阵营。\n"
                + "村民必须通过讨论和投票找出并消灭狼人。\n"
                + "狼人必须在不暴露身份的情况下消灭村民。\n\n"
                + "角色：\n"
                + "  - 3名村民：没有特殊能力\n"
                + "  - 3名狼人：每晚消灭一名村民\n"
                + "  - 1名预言家：每晚可以查验一名玩家的身份\n"
                + "  - 1名女巫：拥有解药和毒药各一瓶（每瓶只能使用一次）\n"
                + "  - 1名猎人：被淘汰时可以射杀一名玩家";
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
            case VILLAGER -> "村民";
            case WEREWOLF -> "狼人";
            case SEER -> "预言家";
            case WITCH -> "女巫";
            case HUNTER -> "猎人";
        };
    }

    @Override
    public String getPlayerAssignments() {
        return "玩家身份分配：";
    }

    @Override
    public String getInitializingGame() {
        return "初始化狼人杀游戏";
    }

    @Override
    public String getNightPhaseTitle() {
        return "🌙 夜晚阶段 - 所有人闭上眼睛...";
    }

    @Override
    public String getDayPhaseTitle() {
        return "☀️ 白天阶段 - 所有人睁开眼睛...";
    }

    @Override
    public String getVotingPhaseTitle() {
        return "🗳️ 投票阶段";
    }

    @Override
    public String getNightPhaseComplete() {
        return "\n🌙 夜晚阶段结束。等待天亮...\n";
    }

    @Override
    public String getWerewolvesDiscussion() {
        return "\n--- 狼人讨论 ---";
    }

    @Override
    public String getWerewolfDiscussionRound(int round) {
        return String.format("  狼人讨论 第%d轮：", round);
    }

    @Override
    public String getWerewolfVoting() {
        return "\n  狼人投票：";
    }

    @Override
    public String getWerewolvesChose(String name) {
        return "狼人选择消灭：" + name;
    }

    @Override
    public String getWitchActions() {
        return "\n--- 女巫行动 ---";
    }

    @Override
    public String getWitchSeesVictim(String name) {
        return String.format("  女巫看到：%s被狼人攻击了", name);
    }

    @Override
    public String getWitchHealDecision(String name, String decision, String reason) {
        return String.format("  [%s] 解药决定：%s（理由：%s）", name, decision, reason);
    }

    @Override
    public String getWitchUsedHeal(String name) {
        return "  ✓ 女巫使用解药救了 " + name;
    }

    @Override
    public String getWitchPoisonDecision(
            String name, String decision, String target, String reason) {
        return String.format("  [%s] 毒药决定：%s（目标：%s，理由：%s）", name, decision, target, reason);
    }

    @Override
    public String getWitchUsedPoison(String name) {
        return "  ✓ 女巫使用毒药毒死了 " + name;
    }

    @Override
    public String getSeerCheck() {
        return "\n--- 预言家查验 ---";
    }

    @Override
    public String getSeerCheckDecision(String seerName, String targetName, String reason) {
        return String.format("  [%s] 想要查验：%s（理由：%s）", seerName, targetName, reason);
    }

    @Override
    public String getSeerCheckResult(String name, String identity) {
        return String.format("  ✓ 查验结果：%s %s", name, identity);
    }

    @Override
    public String getDayDiscussion() {
        return "\n--- 白天讨论 ---";
    }

    @Override
    public String getDiscussionRound(int round) {
        return String.format("\n  讨论环节 第%d轮：", round);
    }

    @Override
    public String getVotingResults() {
        return "\n投票结果：";
    }

    @Override
    public String getNoValidVotes() {
        return "\n没有有效投票。无人被淘汰。";
    }

    @Override
    public String getTieMessage(String players, String selected) {
        return String.format("\n检测到平票：%s。随机选择：%s", players, selected);
    }

    @Override
    public String getVoteCount(String name, int votes) {
        return String.format("  %s：%d票", name, votes);
    }

    @Override
    public String getPlayerEliminated(String name, String role) {
        return String.format("\n%s被投票淘汰。他们的身份是%s。", name, role);
    }

    @Override
    public String getHunterShoot() {
        return "\n--- 猎人的最后一枪 ---";
    }

    @Override
    public String getHunterShootDecision(
            String hunterName, String decision, String targetName, String reason) {
        return String.format(
                "  [%s] 射击决定：%s（目标：%s，理由：%s）", hunterName, decision, targetName, reason);
    }

    @Override
    public String getHunterShotPlayer(String targetName, String role) {
        return String.format("  ✓ 猎人射杀了%s。他们的身份是%s。", targetName, role);
    }

    @Override
    public String getHunterNoShoot() {
        return "  猎人选择不开枪。";
    }

    @Override
    public String getGameOver() {
        return "游戏结束";
    }

    @Override
    public String getVillagersWin() {
        return "🎉 村民胜利！🎉";
    }

    @Override
    public String getVillagersWinExplanation() {
        return "所有狼人已被消灭。";
    }

    @Override
    public String getWerewolvesWin() {
        return "🐺 狼人胜利！🐺";
    }

    @Override
    public String getWerewolvesWinExplanation() {
        return "狼人占领了村庄。";
    }

    @Override
    public String getMaxRoundsReached() {
        return "游戏在达到最大回合数后结束，没有明确的赢家。";
    }

    @Override
    public String getFinalStatus() {
        return "\n最终状态：";
    }

    @Override
    public String getAlivePlayers() {
        return "存活玩家：";
    }

    @Override
    public String getAllPlayersAndRoles() {
        return "\n所有玩家及其身份：";
    }

    @Override
    public String getGameStatus(int round) {
        return String.format("第%d回合 - 游戏状态", round);
    }

    @Override
    public String getAliveStatus(int alive, int werewolves, int villagers) {
        return String.format("存活：%d人 | 狼人：%d人 | 村民：%d人", alive, werewolves, villagers);
    }

    @Override
    public String getStatusLabel(boolean isAlive) {
        return isAlive ? "存活" : "死亡";
    }

    @Override
    public String getVoteParsingError(String name) {
        return String.format("  [%s] 投票解析错误", name);
    }

    @Override
    public String getErrorInDecision(String context) {
        return "  " + context + "决定错误：";
    }

    @Override
    public String getIsWerewolf() {
        return "是狼人";
    }

    @Override
    public String getNotWerewolf() {
        return "不是狼人";
    }

    @Override
    public String getDecisionYes() {
        return "是";
    }

    @Override
    public String getDecisionNo() {
        return "否";
    }

    @Override
    public String getWitchHealYes() {
        return "是，使用解药";
    }

    @Override
    public String getWitchPoisonYes() {
        return "是，使用毒药";
    }

    @Override
    public String getHunterShootYes() {
        return "是，开枪";
    }

    @Override
    public String getHunterShootNo() {
        return "否，不开枪";
    }

    @Override
    public String getVoteDetail(String voterName, String targetName, String reason) {
        return String.format("  [%s] 投票给：%s（理由：%s）", voterName, targetName, reason);
    }

    @Override
    public String getSystemWerewolfKillResult(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return "狼人决定杀死：无人";
        }
        return String.format("狼人决定杀死：%s", playerName);
    }

    @Override
    public String getSystemVotingResult(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return "投票结果：无人被淘汰（平票）";
        }
        return String.format("投票结果：%s 将被淘汰", playerName);
    }
}
