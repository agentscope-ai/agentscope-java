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
package io.agentscope.examples.werewolf.web;

import static io.agentscope.examples.werewolf.WerewolfGameConfig.HUNTER_COUNT;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.MAX_DISCUSSION_ROUNDS;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.MAX_ROUNDS;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.SEER_COUNT;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.VILLAGER_COUNT;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.WEREWOLF_COUNT;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.WITCH_COUNT;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.pipeline.FanoutPipeline;
import io.agentscope.core.pipeline.MsgHub;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.werewolf.WerewolfGameConfig;
import io.agentscope.examples.werewolf.WerewolfUtils;
import io.agentscope.examples.werewolf.entity.GameState;
import io.agentscope.examples.werewolf.entity.Player;
import io.agentscope.examples.werewolf.entity.Role;
import io.agentscope.examples.werewolf.localization.GameMessages;
import io.agentscope.examples.werewolf.localization.LanguageConfig;
import io.agentscope.examples.werewolf.localization.LocalizationBundle;
import io.agentscope.examples.werewolf.localization.PromptProvider;
import io.agentscope.examples.werewolf.model.HunterShootModel;
import io.agentscope.examples.werewolf.model.SeerCheckModel;
import io.agentscope.examples.werewolf.model.VoteModel;
import io.agentscope.examples.werewolf.model.WitchHealModel;
import io.agentscope.examples.werewolf.model.WitchPoisonModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Web-enabled Werewolf Game with event emission.
 *
 * <p>This is a modified version of WerewolfGame that emits events instead of printing to console,
 * suitable for web interface display.
 */
public class WerewolfWebGame {

    private final GameEventEmitter emitter;
    private final PromptProvider prompts;
    private final GameMessages messages;
    private final LanguageConfig langConfig;
    private final WerewolfUtils utils;

    private DashScopeChatModel model;
    private GameState gameState;

    public WerewolfWebGame(GameEventEmitter emitter, LocalizationBundle bundle) {
        this.emitter = emitter;
        this.prompts = bundle.prompts();
        this.messages = bundle.messages();
        this.langConfig = bundle.langConfig();
        this.utils = new WerewolfUtils(messages);
    }

    public GameState getGameState() {
        return gameState;
    }

    public void start() throws Exception {
        emitter.emitSystemMessage(messages.getInitializingGame());

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        model =
                DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(WerewolfGameConfig.DEFAULT_MODEL)
                        .formatter(new DashScopeMultiAgentFormatter())
                        .stream(false)
                        .build();

        gameState = initializeGame();
        emitStatsUpdate();

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            gameState.nextRound();
            emitter.emitPhaseChange(round, "night");

            nightPhase();

            if (checkGameEnd()) {
                break;
            }

            emitter.emitPhaseChange(round, "day");
            dayPhase();

            if (checkGameEnd()) {
                break;
            }
        }

        announceWinner();
    }

    private GameState initializeGame() {
        List<Role> roles = new ArrayList<>();
        for (int i = 0; i < VILLAGER_COUNT; i++) roles.add(Role.VILLAGER);
        for (int i = 0; i < WEREWOLF_COUNT; i++) roles.add(Role.WEREWOLF);
        for (int i = 0; i < SEER_COUNT; i++) roles.add(Role.SEER);
        for (int i = 0; i < WITCH_COUNT; i++) roles.add(Role.WITCH);
        for (int i = 0; i < HUNTER_COUNT; i++) roles.add(Role.HUNTER);
        Collections.shuffle(roles);

        List<Player> players = new ArrayList<>();
        List<String> playerNames = langConfig.getPlayerNames();
        for (int i = 0; i < roles.size(); i++) {
            String name = playerNames.get(i);
            Role role = roles.get(i);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(name)
                            .sysPrompt(prompts.getSystemPrompt(role, name))
                            .model(model)
                            .memory(new InMemoryMemory())
                            .toolkit(new Toolkit())
                            .build();

            Player player = Player.builder().agent(agent).name(name).role(role).build();
            players.add(player);
        }

        // Emit player initialization
        List<Map<String, Object>> playersInfo = new ArrayList<>();
        for (Player player : players) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", player.getName());
            info.put("role", player.getRole().name());
            info.put("roleDisplay", messages.getRoleDisplayName(player.getRole()));
            info.put("roleSymbol", messages.getRoleSymbol(player.getRole()));
            info.put("alive", true);
            playersInfo.add(info);
        }
        emitter.emitGameInit(playersInfo);

        return new GameState(players);
    }

    private void nightPhase() {
        emitter.emitSystemMessage(messages.getNightPhaseTitle());
        gameState.clearNightResults();

        Player victim = werewolvesKill();
        if (victim != null) {
            gameState.setLastNightVictim(victim);
            victim.kill();
            emitter.emitSystemMessage(messages.getWerewolvesChose(victim.getName()));
        }

        if (gameState.getWitch() != null && gameState.getWitch().isAlive()) {
            witchActions();
        }

        if (gameState.getSeer() != null && gameState.getSeer().isAlive()) {
            seerCheck();
        }

        // Emit player_eliminated events for night deaths at end of night phase
        // This ensures deaths are shown even if game ends during night
        Player nightVictim = gameState.getLastNightVictim();
        boolean wasResurrected = gameState.isLastVictimResurrected();
        if (nightVictim != null && !wasResurrected) {
            emitter.emitPlayerEliminated(
                    nightVictim.getName(),
                    messages.getRoleDisplayName(nightVictim.getRole()),
                    "killed");
        }

        emitStatsUpdate();
        emitter.emitSystemMessage(messages.getNightPhaseComplete());
    }

    private Player werewolvesKill() {
        List<Player> werewolves = gameState.getAliveWerewolves();
        if (werewolves.isEmpty()) {
            return null;
        }

        emitter.emitSystemMessage(messages.getSystemWerewolfDiscussing());

        try (MsgHub werewolfHub =
                MsgHub.builder()
                        .name("WerewolfDiscussion")
                        .participants(
                                werewolves.stream()
                                        .map(Player::getAgent)
                                        .toArray(ReActAgent[]::new))
                        .announcement(prompts.createWerewolfDiscussionPrompt(gameState))
                        .enableAutoBroadcast(true)
                        .build()) {

            werewolfHub.enter().block();

            for (int i = 0; i < 2; i++) {
                for (Player werewolf : werewolves) {
                    Msg response = werewolf.getAgent().call().block();
                    String content = utils.extractTextContent(response);
                    emitter.emitPlayerSpeak(werewolf.getName(), content, "werewolf_discussion");
                }
            }

            werewolfHub.setAutoBroadcast(false);
            Msg votingPrompt = prompts.createWerewolfVotingPrompt(gameState);

            FanoutPipeline votingPipeline =
                    FanoutPipeline.builder()
                            .addAgents(
                                    werewolves.stream().map(p -> (AgentBase) p.getAgent()).toList())
                            .concurrent()
                            .build();
            List<Msg> votes = votingPipeline.execute(votingPrompt, VoteModel.class).block();

            for (Msg vote : votes) {
                try {
                    VoteModel voteData = vote.getStructuredData(VoteModel.class);
                    emitter.emitPlayerVote(vote.getName(), voteData.targetPlayer, voteData.reason);
                } catch (Exception e) {
                    emitter.emitSystemMessage(messages.getVoteParsingError(vote.getName()));
                }
            }

            Player killedPlayer = utils.countVotes(votes, gameState);

            List<Msg> broadcastMsgs = new ArrayList<>(votes);
            broadcastMsgs.add(
                    Msg.builder()
                            .name("system")
                            .role(MsgRole.USER)
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    messages.getSystemWerewolfKillResult(
                                                            killedPlayer != null
                                                                    ? killedPlayer.getName()
                                                                    : null))
                                            .build())
                            .build());
            werewolfHub.broadcast(broadcastMsgs).block();

            return killedPlayer;
        }
    }

    private void witchActions() {
        Player witch = gameState.getWitch();
        Player victim = gameState.getLastNightVictim();

        emitter.emitSystemMessage(messages.getSystemWitchActing());

        boolean usedHeal = false;

        if (witch.isWitchHasHealPotion() && victim != null) {
            try {
                emitter.emitSystemMessage(messages.getSystemWitchSeesVictim(victim.getName()));
                Msg healDecision =
                        witch.getAgent()
                                .call(prompts.createWitchHealPrompt(victim), WitchHealModel.class)
                                .block();

                WitchHealModel healModel = healDecision.getStructuredData(WitchHealModel.class);

                if (Boolean.TRUE.equals(healModel.useHealPotion)) {
                    victim.resurrect();
                    witch.useHealPotion();
                    gameState.setLastVictimResurrected(true);
                    usedHeal = true;
                    emitter.emitPlayerAction(
                            witch.getName(),
                            messages.getRoleDisplayName(Role.WITCH),
                            messages.getActionWitchUseHeal(),
                            victim.getName(),
                            messages.getActionWitchHealResult());
                    emitter.emitPlayerResurrected(victim.getName());
                } else {
                    emitter.emitPlayerAction(
                            witch.getName(),
                            messages.getRoleDisplayName(Role.WITCH),
                            messages.getActionWitchUseHeal(),
                            null,
                            messages.getActionWitchHealSkip());
                }
            } catch (Exception e) {
                emitter.emitError(messages.getErrorWitchHeal(e.getMessage()));
            }
        }

        if (witch.isWitchHasPoisonPotion()) {
            try {
                Msg poisonDecision =
                        witch.getAgent()
                                .call(
                                        prompts.createWitchPoisonPrompt(gameState, usedHeal),
                                        WitchPoisonModel.class)
                                .block();

                WitchPoisonModel poisonModel =
                        poisonDecision.getStructuredData(WitchPoisonModel.class);

                if (Boolean.TRUE.equals(poisonModel.usePoisonPotion)
                        && poisonModel.targetPlayer != null) {
                    Player targetPlayer = gameState.findPlayerByName(poisonModel.targetPlayer);
                    if (targetPlayer != null && targetPlayer.isAlive()) {
                        targetPlayer.kill();
                        witch.usePoisonPotion();
                        gameState.setLastPoisonedVictim(targetPlayer);
                        emitter.emitPlayerAction(
                                witch.getName(),
                                messages.getRoleDisplayName(Role.WITCH),
                                messages.getActionWitchUsePoison(),
                                targetPlayer.getName(),
                                messages.getActionWitchPoisonResult());
                        emitter.emitPlayerEliminated(
                                targetPlayer.getName(),
                                messages.getRoleDisplayName(targetPlayer.getRole()),
                                "poisoned");
                    }
                } else {
                    emitter.emitPlayerAction(
                            witch.getName(),
                            messages.getRoleDisplayName(Role.WITCH),
                            messages.getActionWitchUsePoison(),
                            null,
                            messages.getActionWitchPoisonSkip());
                }
            } catch (Exception e) {
                emitter.emitError(messages.getErrorWitchPoison(e.getMessage()));
            }
        }

        emitStatsUpdate();
    }

    private void seerCheck() {
        Player seer = gameState.getSeer();

        emitter.emitSystemMessage(messages.getSystemSeerActing());

        try {
            Msg checkDecision =
                    seer.getAgent()
                            .call(prompts.createSeerCheckPrompt(gameState), SeerCheckModel.class)
                            .block();

            SeerCheckModel checkModel = checkDecision.getStructuredData(SeerCheckModel.class);

            if (checkModel.targetPlayer != null) {
                Player target = gameState.findPlayerByName(checkModel.targetPlayer);
                if (target != null && target.isAlive()) {
                    String identity =
                            target.getRole() == Role.WEREWOLF
                                    ? messages.getIsWerewolf()
                                    : messages.getNotWerewolf();
                    emitter.emitPlayerAction(
                            seer.getName(),
                            messages.getRoleDisplayName(Role.SEER),
                            messages.getActionSeerCheck(),
                            target.getName(),
                            target.getName() + " " + identity);
                    seer.getAgent().call(prompts.createSeerResultPrompt(target)).block();
                }
            }
        } catch (Exception e) {
            emitter.emitError(messages.getErrorSeerCheck(e.getMessage()));
        }
    }

    private void dayPhase() {
        emitter.emitSystemMessage(messages.getDayPhaseTitle());

        String nightAnnouncement = prompts.createNightResultAnnouncement(gameState);
        emitter.emitSystemMessage(nightAnnouncement);

        // Night deaths are already emitted at end of nightPhase()

        Player hunter = gameState.getHunter();
        if (hunter != null
                && !hunter.isAlive()
                && (hunter.equals(gameState.getLastNightVictim())
                        || hunter.equals(gameState.getLastPoisonedVictim()))) {
            hunterShoot(hunter);
            if (checkGameEnd()) {
                return;
            }
        }

        discussionPhase();

        Player votedOut = votingPhase();

        if (votedOut != null) {
            votedOut.kill();
            String roleName = messages.getRoleDisplayName(votedOut.getRole());
            emitter.emitPlayerEliminated(votedOut.getName(), roleName, "voted");

            if (votedOut.getRole() == Role.HUNTER) {
                hunterShoot(votedOut);
            }
        }

        emitStatsUpdate();
    }

    private void discussionPhase() {
        List<Player> alivePlayers = gameState.getAlivePlayers();
        if (alivePlayers.size() <= 2) {
            return;
        }

        emitter.emitSystemMessage(messages.getSystemDayDiscussionStart());

        try (MsgHub discussionHub =
                MsgHub.builder()
                        .name("DayDiscussion")
                        .participants(
                                alivePlayers.stream()
                                        .map(Player::getAgent)
                                        .toArray(ReActAgent[]::new))
                        .announcement(
                                Msg.builder()
                                        .name("system")
                                        .role(MsgRole.USER)
                                        .content(
                                                TextBlock.builder()
                                                        .text(
                                                                prompts
                                                                        .createNightResultAnnouncement(
                                                                                gameState))
                                                        .build())
                                        .build())
                        .enableAutoBroadcast(true)
                        .build()) {

            discussionHub.enter().block();

            for (int round = 1; round <= MAX_DISCUSSION_ROUNDS; round++) {
                emitter.emitSystemMessage(messages.getDiscussionRound(round));

                if (round > 1) {
                    Msg roundPrompt = prompts.createDiscussionPrompt(gameState, round);
                    for (Player player : alivePlayers) {
                        player.getAgent().getMemory().addMessage(roundPrompt);
                    }
                }

                for (Player player : alivePlayers) {
                    Msg response = player.getAgent().call().block();
                    String content = utils.extractTextContent(response);
                    emitter.emitPlayerSpeak(player.getName(), content, "day_discussion");
                }
            }
        }
    }

    private Player votingPhase() {
        List<Player> alivePlayers = gameState.getAlivePlayers();
        if (alivePlayers.size() <= 1) {
            return null;
        }

        emitter.emitSystemMessage(messages.getSystemVotingStart());

        try (MsgHub votingHub =
                MsgHub.builder()
                        .name("DayVoting")
                        .participants(
                                alivePlayers.stream()
                                        .map(Player::getAgent)
                                        .toArray(ReActAgent[]::new))
                        .enableAutoBroadcast(true)
                        .build()) {

            votingHub.enter().block();
            votingHub.setAutoBroadcast(false);

            Msg votingPrompt = prompts.createVotingPrompt(gameState);

            FanoutPipeline votingPipeline =
                    FanoutPipeline.builder()
                            .addAgents(
                                    alivePlayers.stream()
                                            .map(p -> (AgentBase) p.getAgent())
                                            .toList())
                            .concurrent()
                            .build();
            List<Msg> votes = votingPipeline.execute(votingPrompt, VoteModel.class).block();

            for (Msg vote : votes) {
                try {
                    VoteModel voteData = vote.getStructuredData(VoteModel.class);
                    emitter.emitPlayerVote(vote.getName(), voteData.targetPlayer, voteData.reason);
                } catch (Exception e) {
                    emitter.emitSystemMessage(messages.getVoteParsingError(vote.getName()));
                }
            }

            Player votedOut = utils.countVotes(votes, gameState);

            List<Msg> broadcastMsgs = new ArrayList<>(votes);
            broadcastMsgs.add(
                    Msg.builder()
                            .name("system")
                            .role(MsgRole.USER)
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    messages.getSystemVotingResult(
                                                            votedOut != null
                                                                    ? votedOut.getName()
                                                                    : null))
                                            .build())
                            .build());
            votingHub.broadcast(broadcastMsgs).block();

            return votedOut;
        }
    }

    private void hunterShoot(Player hunter) {
        emitter.emitSystemMessage(messages.getSystemHunterSkill());

        try {
            Msg shootDecision =
                    hunter.getAgent()
                            .call(
                                    prompts.createHunterShootPrompt(gameState, hunter),
                                    HunterShootModel.class)
                            .block();

            HunterShootModel shootModel = shootDecision.getStructuredData(HunterShootModel.class);

            if (Boolean.TRUE.equals(shootModel.willShoot) && shootModel.targetPlayer != null) {
                Player targetPlayer = gameState.findPlayerByName(shootModel.targetPlayer);
                if (targetPlayer != null && targetPlayer.isAlive()) {
                    targetPlayer.kill();
                    String roleName = messages.getRoleDisplayName(targetPlayer.getRole());
                    emitter.emitPlayerAction(
                            hunter.getName(),
                            messages.getRoleDisplayName(Role.HUNTER),
                            messages.getActionHunterShoot(),
                            targetPlayer.getName(),
                            messages.getActionHunterShootResult());
                    emitter.emitPlayerEliminated(targetPlayer.getName(), roleName, "shot");
                }
            } else {
                emitter.emitPlayerAction(
                        hunter.getName(),
                        messages.getRoleDisplayName(Role.HUNTER),
                        messages.getActionHunterShoot(),
                        null,
                        messages.getActionHunterShootSkip());
            }
        } catch (Exception e) {
            emitter.emitError(messages.getErrorHunterShoot(e.getMessage()));
        }

        emitStatsUpdate();
    }

    private boolean checkGameEnd() {
        return gameState.checkVillagersWin() || gameState.checkWerewolvesWin();
    }

    private void announceWinner() {
        if (gameState.checkVillagersWin()) {
            emitter.emitGameEnd("villagers", messages.getVillagersWinExplanation());
        } else if (gameState.checkWerewolvesWin()) {
            emitter.emitGameEnd("werewolves", messages.getWerewolvesWinExplanation());
        } else {
            emitter.emitGameEnd("none", messages.getMaxRoundsReached());
        }
    }

    private void emitStatsUpdate() {
        emitter.emitStatsUpdate(
                gameState.getAlivePlayers().size(),
                gameState.getAliveWerewolves().size(),
                gameState.getAliveVillagers().size());
    }
}
