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
import java.util.Locale;
import org.springframework.context.MessageSource;

public class MessageSourceGameMessages implements GameMessages {

    private final MessageSource messageSource;
    private final Locale locale;

    public MessageSourceGameMessages(MessageSource messageSource, Locale locale) {
        this.messageSource = messageSource;
        this.locale = locale;
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, locale);
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    @Override
    public String getWelcomeTitle() {
        return msg("game.welcome.title");
    }

    @Override
    public String getWelcomeDescription() {
        return msg("game.welcome.description");
    }

    @Override
    public String getRoleSymbol(Role role) {
        return msg("role." + role.name().toLowerCase() + ".symbol");
    }

    @Override
    public String getRoleDisplayName(Role role) {
        return msg("role." + role.name().toLowerCase() + ".name");
    }

    @Override
    public String getPlayerAssignments() {
        return msg("game.player.assignments");
    }

    @Override
    public String getInitializingGame() {
        return msg("game.initializing");
    }

    @Override
    public String getNightPhaseTitle() {
        return msg("phase.night.title");
    }

    @Override
    public String getDayPhaseTitle() {
        return msg("phase.day.title");
    }

    @Override
    public String getVotingPhaseTitle() {
        return msg("phase.voting.title");
    }

    @Override
    public String getNightPhaseComplete() {
        return msg("phase.night.complete");
    }

    @Override
    public String getWerewolvesDiscussion() {
        return msg("werewolf.discussion");
    }

    @Override
    public String getWerewolfDiscussionRound(int round) {
        return msg("werewolf.discussion.round", round);
    }

    @Override
    public String getWerewolfVoting() {
        return msg("werewolf.voting");
    }

    @Override
    public String getWerewolvesChose(String name) {
        return msg("werewolf.chose", name);
    }

    @Override
    public String getWitchActions() {
        return msg("witch.actions");
    }

    @Override
    public String getWitchSeesVictim(String name) {
        return msg("witch.sees.victim", name);
    }

    @Override
    public String getWitchHealDecision(String name, String decision, String reason) {
        return msg("witch.heal.decision", name, decision, reason);
    }

    @Override
    public String getWitchUsedHeal(String name) {
        return msg("witch.used.heal", name);
    }

    @Override
    public String getWitchPoisonDecision(
            String name, String decision, String target, String reason) {
        return msg("witch.poison.decision", name, decision, target, reason);
    }

    @Override
    public String getWitchUsedPoison(String name) {
        return msg("witch.used.poison", name);
    }

    @Override
    public String getSeerCheck() {
        return msg("seer.check");
    }

    @Override
    public String getSeerCheckDecision(String seerName, String targetName, String reason) {
        return msg("seer.check.decision", seerName, targetName, reason);
    }

    @Override
    public String getSeerCheckResult(String name, String identity) {
        return msg("seer.check.result", name, identity);
    }

    @Override
    public String getDayDiscussion() {
        return msg("day.discussion");
    }

    @Override
    public String getDiscussionRound(int round) {
        return msg("discussion.round", round);
    }

    @Override
    public String getVotingResults() {
        return msg("voting.results");
    }

    @Override
    public String getNoValidVotes() {
        return msg("voting.no.valid");
    }

    @Override
    public String getTieMessage(String players, String selected) {
        return msg("voting.tie", players, selected);
    }

    @Override
    public String getVoteCount(String name, int votes) {
        return msg("voting.count", name, votes);
    }

    @Override
    public String getPlayerEliminated(String name, String role) {
        return msg("voting.eliminated", name, role);
    }

    @Override
    public String getHunterShoot() {
        return msg("hunter.shoot");
    }

    @Override
    public String getHunterShootDecision(
            String hunterName, String decision, String targetName, String reason) {
        return msg("hunter.shoot.decision", hunterName, decision, targetName, reason);
    }

    @Override
    public String getHunterShotPlayer(String targetName, String role) {
        return msg("hunter.shot.player", targetName, role);
    }

    @Override
    public String getHunterNoShoot() {
        return msg("hunter.no.shoot");
    }

    @Override
    public String getGameOver() {
        return msg("game.over");
    }

    @Override
    public String getVillagersWin() {
        return msg("game.villagers.win");
    }

    @Override
    public String getVillagersWinExplanation() {
        return msg("game.villagers.win.explanation");
    }

    @Override
    public String getWerewolvesWin() {
        return msg("game.werewolves.win");
    }

    @Override
    public String getWerewolvesWinExplanation() {
        return msg("game.werewolves.win.explanation");
    }

    @Override
    public String getMaxRoundsReached() {
        return msg("game.max.rounds");
    }

    @Override
    public String getFinalStatus() {
        return msg("status.final");
    }

    @Override
    public String getAlivePlayers() {
        return msg("status.alive.players");
    }

    @Override
    public String getAllPlayersAndRoles() {
        return msg("status.all.players");
    }

    @Override
    public String getGameStatus(int round) {
        return msg("status.round", round);
    }

    @Override
    public String getAliveStatus(int alive, int werewolves, int villagers) {
        return msg("status.alive", alive, werewolves, villagers);
    }

    @Override
    public String getStatusLabel(boolean isAlive) {
        return isAlive ? msg("status.label.alive") : msg("status.label.dead");
    }

    @Override
    public String getVoteParsingError(String name) {
        return msg("error.vote.parsing", name);
    }

    @Override
    public String getErrorInDecision(String context) {
        return msg("error.decision", context);
    }

    @Override
    public String getIsWerewolf() {
        return msg("decision.is.werewolf");
    }

    @Override
    public String getNotWerewolf() {
        return msg("decision.not.werewolf");
    }

    @Override
    public String getDecisionYes() {
        return msg("decision.yes");
    }

    @Override
    public String getDecisionNo() {
        return msg("decision.no");
    }

    @Override
    public String getWitchHealYes() {
        return msg("decision.witch.heal.yes");
    }

    @Override
    public String getWitchPoisonYes() {
        return msg("decision.witch.poison.yes");
    }

    @Override
    public String getHunterShootYes() {
        return msg("decision.hunter.shoot.yes");
    }

    @Override
    public String getHunterShootNo() {
        return msg("decision.hunter.shoot.no");
    }

    @Override
    public String getVoteDetail(String voterName, String targetName, String reason) {
        return msg("voting.detail", voterName, targetName, reason);
    }

    @Override
    public String getSystemWerewolfKillResult(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return msg("system.werewolf.kill.none");
        }
        return msg("system.werewolf.kill.result", playerName);
    }

    @Override
    public String getSystemVotingResult(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return msg("system.voting.result.none");
        }
        return msg("system.voting.result", playerName);
    }

    @Override
    public String getSystemWerewolfDiscussing() {
        return msg("system.werewolf.discussing");
    }

    @Override
    public String getSystemWitchActing() {
        return msg("system.witch.acting");
    }

    @Override
    public String getSystemWitchSeesVictim(String playerName) {
        return msg("system.witch.sees.victim", playerName);
    }

    @Override
    public String getSystemSeerActing() {
        return msg("system.seer.acting");
    }

    @Override
    public String getSystemDayDiscussionStart() {
        return msg("system.day.discussion.start");
    }

    @Override
    public String getSystemVotingStart() {
        return msg("system.voting.start");
    }

    @Override
    public String getSystemHunterSkill() {
        return msg("system.hunter.skill");
    }

    @Override
    public String getActionWitchUseHeal() {
        return msg("action.witch.use.heal");
    }

    @Override
    public String getActionWitchHealResult() {
        return msg("action.witch.heal.result");
    }

    @Override
    public String getActionWitchHealSkip() {
        return msg("action.witch.heal.skip");
    }

    @Override
    public String getActionWitchUsePoison() {
        return msg("action.witch.use.poison");
    }

    @Override
    public String getActionWitchPoisonResult() {
        return msg("action.witch.poison.result");
    }

    @Override
    public String getActionWitchPoisonSkip() {
        return msg("action.witch.poison.skip");
    }

    @Override
    public String getActionSeerCheck() {
        return msg("action.seer.check");
    }

    @Override
    public String getActionHunterShoot() {
        return msg("action.hunter.shoot");
    }

    @Override
    public String getActionHunterShootResult() {
        return msg("action.hunter.shoot.result");
    }

    @Override
    public String getActionHunterShootSkip() {
        return msg("action.hunter.shoot.skip");
    }

    @Override
    public String getErrorWitchHeal(String error) {
        return msg("error.witch.heal", error);
    }

    @Override
    public String getErrorWitchPoison(String error) {
        return msg("error.witch.poison", error);
    }

    @Override
    public String getErrorSeerCheck(String error) {
        return msg("error.seer.check", error);
    }

    @Override
    public String getErrorHunterShoot(String error) {
        return msg("error.hunter.shoot", error);
    }
}
