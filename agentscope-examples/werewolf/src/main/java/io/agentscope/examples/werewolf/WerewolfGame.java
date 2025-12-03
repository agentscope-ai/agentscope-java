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
package io.agentscope.examples.werewolf;

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
import io.agentscope.examples.werewolf.entity.GameState;
import io.agentscope.examples.werewolf.entity.Player;
import io.agentscope.examples.werewolf.entity.Role;
import io.agentscope.examples.werewolf.localization.GameMessages;
import io.agentscope.examples.werewolf.localization.LanguageConfig;
import io.agentscope.examples.werewolf.localization.PromptProvider;
import io.agentscope.examples.werewolf.model.HunterShootModel;
import io.agentscope.examples.werewolf.model.SeerCheckModel;
import io.agentscope.examples.werewolf.model.VoteModel;
import io.agentscope.examples.werewolf.model.WitchHealModel;
import io.agentscope.examples.werewolf.model.WitchPoisonModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Werewolf Game - A 9-player multi-agent game demonstration.
 *
 * <p>This example demonstrates:
 *
 * <ul>
 *   <li>Complex multi-agent coordination with 9 players
 *   <li>Role-based agent behaviors (Villager, Werewolf, Seer, Witch, Hunter)
 *   <li>Private communication (werewolves) and public discussion
 *   <li>Sequential discussion using MsgHub with auto-broadcast
 *   <li>Parallel voting using FanoutPipeline with structured output
 *   <li>Structured output for controlled agent decisions
 *   <li>Internationalization support (English and Chinese)
 * </ul>
 *
 * <p>This class is the core game logic and should be instantiated with language-specific
 * configurations. See {@link WerewolfGameEnglish} and {@link WerewolfGameChinese} for startup
 * classes.
 */
public class WerewolfGame {

    private final PromptProvider prompts;
    private final GameMessages messages;
    private final LanguageConfig langConfig;
    private final WerewolfUtils utils;
    private DashScopeChatModel model;

    /**
     * Constructor for WerewolfGame with dependency injection.
     *
     * @param prompts Provider for game prompts in specific language
     * @param messages Provider for UI messages in specific language
     * @param langConfig Language-specific configuration
     */
    public WerewolfGame(PromptProvider prompts, GameMessages messages, LanguageConfig langConfig) {
        this.prompts = prompts;
        this.messages = messages;
        this.langConfig = langConfig;
        this.utils = new WerewolfUtils(messages);
    }

    /**
     * Start the Werewolf game.
     *
     * @throws Exception if game initialization or execution fails
     */
    public void start() throws Exception {
        ExampleUtils.printWelcome(messages.getWelcomeTitle(), messages.getWelcomeDescription());

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Create shared model
        model =
                DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(WerewolfGameConfig.DEFAULT_MODEL)
                        .formatter(new DashScopeMultiAgentFormatter())
                        .stream(false)
                        .build();

        // Initialize game
        GameState gameState = initializeGame();

        // Main game loop
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            gameState.nextRound();
            utils.printGameStatus(gameState);

            // Night phase
            nightPhase(gameState);

            // Check winning condition
            if (checkGameEnd(gameState)) {
                break;
            }

            // Day phase
            dayPhase(gameState);

            // Check winning condition
            if (checkGameEnd(gameState)) {
                break;
            }
        }

        // Announce winner
        utils.announceWinner(gameState);
    }

    // ==================== Game Initialization ====================

    private GameState initializeGame() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println(messages.getInitializingGame());
        System.out.println("=".repeat(60) + "\n");

        // Prepare roles
        List<Role> roles = new ArrayList<>();
        for (int i = 0; i < VILLAGER_COUNT; i++) {
            roles.add(Role.VILLAGER);
        }
        for (int i = 0; i < WEREWOLF_COUNT; i++) {
            roles.add(Role.WEREWOLF);
        }
        for (int i = 0; i < SEER_COUNT; i++) {
            roles.add(Role.SEER);
        }
        for (int i = 0; i < WITCH_COUNT; i++) {
            roles.add(Role.WITCH);
        }
        for (int i = 0; i < HUNTER_COUNT; i++) {
            roles.add(Role.HUNTER);
        }
        Collections.shuffle(roles);

        // Create players
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

        // Print player assignments
        System.out.println(messages.getPlayerAssignments());
        for (Player player : players) {
            String roleSymbol = messages.getRoleSymbol(player.getRole());
            String roleName = messages.getRoleDisplayName(player.getRole());
            System.out.println(
                    String.format("  %s %s - %s", roleSymbol, player.getName(), roleName));
        }
        System.out.println();

        return new GameState(players);
    }

    // ==================== Night Phase ====================

    private void nightPhase(GameState state) {
        utils.printSectionHeader(messages.getNightPhaseTitle());

        state.clearNightResults();

        // 1. Werewolves choose victim
        Player victim = werewolvesKill(state);
        if (victim != null) {
            state.setLastNightVictim(victim);
            victim.kill();
            System.out.println(messages.getWerewolvesChose(victim.getName()));
        }

        // 2. Witch actions
        if (state.getWitch() != null && state.getWitch().isAlive()) {
            witchActions(state);
        }

        // 3. Seer checks identity
        if (state.getSeer() != null && state.getSeer().isAlive()) {
            seerCheck(state);
        }

        System.out.println(messages.getNightPhaseComplete());
    }

    private Player werewolvesKill(GameState state) {
        List<Player> werewolves = state.getAliveWerewolves();
        if (werewolves.isEmpty()) {
            return null;
        }

        System.out.println(messages.getWerewolvesDiscussion());

        try (MsgHub werewolfHub =
                MsgHub.builder()
                        .name("WerewolfDiscussion")
                        .participants(
                                werewolves.stream()
                                        .map(Player::getAgent)
                                        .toArray(ReActAgent[]::new))
                        .announcement(prompts.createWerewolfDiscussionPrompt(state))
                        .enableAutoBroadcast(true)
                        .build()) {

            werewolfHub.enter().block();

            // Discussion rounds
            for (int i = 0; i < 2; i++) {
                System.out.println(messages.getWerewolfDiscussionRound(i + 1));
                for (Player werewolf : werewolves) {
                    Msg response = werewolf.getAgent().call().block();
                    String content = utils.extractTextContent(response);
                    System.out.println(String.format("  [%s]: %s", werewolf.getName(), content));
                }
            }

            // Voting - parallel voting without auto-broadcast (following Python pattern)
            System.out.println(messages.getWerewolfVoting());

            // Disable auto-broadcast to prevent vote leaking
            werewolfHub.setAutoBroadcast(false);

            Msg votingPrompt = prompts.createWerewolfVotingPrompt(state);

            // Parallel voting with structured output (using FanoutPipeline)
            FanoutPipeline votingPipeline =
                    FanoutPipeline.builder()
                            .addAgents(
                                    werewolves.stream().map(p -> (AgentBase) p.getAgent()).toList())
                            .concurrent()
                            .build();
            List<Msg> votes = votingPipeline.execute(votingPrompt, VoteModel.class).block();

            // Print vote details
            for (Msg vote : votes) {
                try {
                    VoteModel voteData = vote.getStructuredData(VoteModel.class);
                    System.out.println(
                            messages.getVoteDetail(
                                    vote.getName(), voteData.targetPlayer, voteData.reason));
                } catch (Exception e) {
                    System.out.println(messages.getVoteParsingError(vote.getName()));
                }
            }

            Player killedPlayer = utils.countVotes(votes, state);

            // Manually broadcast all votes together (following Python pattern)
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

    private void witchActions(GameState state) {
        Player witch = state.getWitch();
        Player victim = state.getLastNightVictim();

        System.out.println(messages.getWitchActions());

        boolean usedHeal = false;

        // Heal potion decision
        if (witch.isWitchHasHealPotion() && victim != null) {
            try {
                System.out.println(messages.getWitchSeesVictim(victim.getName()));
                Msg healDecision =
                        witch.getAgent()
                                .call(prompts.createWitchHealPrompt(victim), WitchHealModel.class)
                                .block();

                WitchHealModel healModel = healDecision.getStructuredData(WitchHealModel.class);
                String decision =
                        healModel.useHealPotion
                                ? messages.getWitchHealYes()
                                : messages.getDecisionNo();
                System.out.println(
                        messages.getWitchHealDecision(witch.getName(), decision, healModel.reason));

                if (Boolean.TRUE.equals(healModel.useHealPotion)) {
                    victim.resurrect();
                    witch.useHealPotion();
                    state.setLastVictimResurrected(true);
                    usedHeal = true;
                    System.out.println(messages.getWitchUsedHeal(victim.getName()));
                }
            } catch (Exception e) {
                System.err.println(messages.getErrorInDecision("witch heal") + e.getMessage());
            }
        }

        // Poison potion decision
        if (witch.isWitchHasPoisonPotion()) {
            try {
                Msg poisonDecision =
                        witch.getAgent()
                                .call(
                                        prompts.createWitchPoisonPrompt(state, usedHeal),
                                        WitchPoisonModel.class)
                                .block();

                WitchPoisonModel poisonModel =
                        poisonDecision.getStructuredData(WitchPoisonModel.class);
                String decision =
                        poisonModel.usePoisonPotion
                                ? messages.getWitchPoisonYes()
                                : messages.getDecisionNo();
                String target = poisonModel.targetPlayer != null ? poisonModel.targetPlayer : "";
                System.out.println(
                        messages.getWitchPoisonDecision(
                                witch.getName(), decision, target, poisonModel.reason));

                if (Boolean.TRUE.equals(poisonModel.usePoisonPotion)
                        && poisonModel.targetPlayer != null) {
                    Player targetPlayer = state.findPlayerByName(poisonModel.targetPlayer);
                    if (targetPlayer != null && targetPlayer.isAlive()) {
                        targetPlayer.kill();
                        witch.usePoisonPotion();
                        state.setLastPoisonedVictim(targetPlayer);
                        System.out.println(messages.getWitchUsedPoison(targetPlayer.getName()));
                    }
                }
            } catch (Exception e) {
                System.err.println(messages.getErrorInDecision("witch poison") + e.getMessage());
            }
        }
    }

    private void seerCheck(GameState state) {
        Player seer = state.getSeer();

        System.out.println(messages.getSeerCheck());

        try {
            Msg checkDecision =
                    seer.getAgent()
                            .call(prompts.createSeerCheckPrompt(state), SeerCheckModel.class)
                            .block();

            SeerCheckModel checkModel = checkDecision.getStructuredData(SeerCheckModel.class);
            System.out.println(
                    messages.getSeerCheckDecision(
                            seer.getName(), checkModel.targetPlayer, checkModel.reason));

            if (checkModel.targetPlayer != null) {
                Player target = state.findPlayerByName(checkModel.targetPlayer);
                if (target != null && target.isAlive()) {
                    String identity =
                            target.getRole() == Role.WEREWOLF
                                    ? messages.getIsWerewolf()
                                    : messages.getNotWerewolf();
                    System.out.println(messages.getSeerCheckResult(target.getName(), identity));
                    seer.getAgent().call(prompts.createSeerResultPrompt(target)).block();
                }
            }
        } catch (Exception e) {
            System.err.println(messages.getErrorInDecision("seer check") + e.getMessage());
        }
    }

    // ==================== Day Phase ====================

    private void dayPhase(GameState state) {
        utils.printSectionHeader(messages.getDayPhaseTitle());

        // Announce night results
        System.out.println(prompts.createNightResultAnnouncement(state));

        // Check if hunter died last night
        Player hunter = state.getHunter();
        if (hunter != null
                && !hunter.isAlive()
                && (hunter.equals(state.getLastNightVictim())
                        || hunter.equals(state.getLastPoisonedVictim()))) {
            hunterShoot(state, hunter);
            if (checkGameEnd(state)) {
                return;
            }
        }

        // Discussion phase
        discussionPhase(state);

        // Voting phase
        Player votedOut = votingPhase(state);

        // Process voting result
        if (votedOut != null) {
            votedOut.kill();
            String roleName = messages.getRoleDisplayName(votedOut.getRole());
            System.out.println(messages.getPlayerEliminated(votedOut.getName(), roleName));

            // Check if hunter was voted out
            if (votedOut.getRole() == Role.HUNTER) {
                hunterShoot(state, votedOut);
            }
        }
    }

    private void discussionPhase(GameState state) {
        List<Player> alivePlayers = state.getAlivePlayers();
        if (alivePlayers.size() <= 2) {
            return; // Not enough players to discuss
        }

        System.out.println(messages.getDayDiscussion());

        // Create MsgHub for public discussion (all players can see each other's messages)
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
                                                                                state))
                                                        .build())
                                        .build())
                        .enableAutoBroadcast(true)
                        .build()) {

            discussionHub.enter().block();

            // Discussion rounds with broadcast
            for (int round = 1; round <= MAX_DISCUSSION_ROUNDS; round++) {
                System.out.println(messages.getDiscussionRound(round));

                // Optional: Add round-specific prompt
                if (round > 1) {
                    Msg roundPrompt = prompts.createDiscussionPrompt(state, round);
                    // Broadcast round prompt to all players
                    for (Player player : alivePlayers) {
                        player.getAgent().getMemory().addMessage(roundPrompt);
                    }
                }

                // Each player speaks in turn, and all messages are auto-broadcasted
                for (Player player : alivePlayers) {
                    Msg response = player.getAgent().call().block();
                    String content = utils.extractTextContent(response);
                    System.out.println(String.format("  [%s]: %s", player.getName(), content));
                }
            }
        }
    }

    private Player votingPhase(GameState state) {
        List<Player> alivePlayers = state.getAlivePlayers();
        if (alivePlayers.size() <= 1) {
            return null; // Not enough players to vote
        }

        utils.printSectionHeader(messages.getVotingPhaseTitle());

        // Create MsgHub for voting phase (following Python pattern)
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

            // Disable auto-broadcast to prevent vote leaking (following Python pattern)
            votingHub.setAutoBroadcast(false);

            Msg votingPrompt = prompts.createVotingPrompt(state);

            // Parallel voting with structured output (using FanoutPipeline)
            FanoutPipeline votingPipeline =
                    FanoutPipeline.builder()
                            .addAgents(
                                    alivePlayers.stream()
                                            .map(p -> (AgentBase) p.getAgent())
                                            .toList())
                            .concurrent()
                            .build();
            List<Msg> votes = votingPipeline.execute(votingPrompt, VoteModel.class).block();

            // Print vote details
            for (Msg vote : votes) {
                try {
                    VoteModel voteData = vote.getStructuredData(VoteModel.class);
                    System.out.println(
                            messages.getVoteDetail(
                                    vote.getName(), voteData.targetPlayer, voteData.reason));
                } catch (Exception e) {
                    System.out.println(messages.getVoteParsingError(vote.getName()));
                }
            }

            Player votedOut = utils.countVotes(votes, state);

            // Manually broadcast all votes together (following Python pattern)
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

    private void hunterShoot(GameState state, Player hunter) {
        System.out.println(messages.getHunterShoot());

        try {
            Msg shootDecision =
                    hunter.getAgent()
                            .call(
                                    prompts.createHunterShootPrompt(state, hunter),
                                    HunterShootModel.class)
                            .block();

            HunterShootModel shootModel = shootDecision.getStructuredData(HunterShootModel.class);
            String decision =
                    shootModel.willShoot
                            ? messages.getHunterShootYes()
                            : messages.getHunterShootNo();
            String target = shootModel.targetPlayer != null ? shootModel.targetPlayer : "";
            System.out.println(
                    messages.getHunterShootDecision(
                            hunter.getName(), decision, target, shootModel.reason));

            if (Boolean.TRUE.equals(shootModel.willShoot) && shootModel.targetPlayer != null) {
                Player targetPlayer = state.findPlayerByName(shootModel.targetPlayer);
                if (targetPlayer != null && targetPlayer.isAlive()) {
                    targetPlayer.kill();
                    String roleName = messages.getRoleDisplayName(targetPlayer.getRole());
                    System.out.println(
                            messages.getHunterShotPlayer(targetPlayer.getName(), roleName));
                }
            } else {
                System.out.println(messages.getHunterNoShoot());
            }
        } catch (Exception e) {
            System.err.println(messages.getErrorInDecision("hunter shoot") + e.getMessage());
        }
    }

    // ==================== Game End Checking ====================

    private boolean checkGameEnd(GameState state) {
        if (state.checkVillagersWin() || state.checkWerewolvesWin()) {
            return true;
        }
        return false;
    }
}
