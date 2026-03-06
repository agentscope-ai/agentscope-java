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

import static io.agentscope.examples.werewolf.WerewolfGameConfig.MAX_ROUNDS;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.user.UserAgent;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;
import io.agentscope.core.model.tts.Qwen3TTSFlashVoice;
import io.agentscope.core.pipeline.FanoutPipeline;
import io.agentscope.core.pipeline.MsgHub;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.werewolf.GameConfiguration;
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
import io.agentscope.examples.werewolf.model.SheriffCampaignModel;
import io.agentscope.examples.werewolf.model.SheriffRegistrationModel;
import io.agentscope.examples.werewolf.model.SheriffTransferModel;
import io.agentscope.examples.werewolf.model.SheriffVoteModel;
import io.agentscope.examples.werewolf.model.SpeakOrderModel;
import io.agentscope.examples.werewolf.model.VoteModel;
import io.agentscope.examples.werewolf.model.WitchHealModel;
import io.agentscope.examples.werewolf.model.WitchPoisonModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Web-enabled Werewolf Game with event emission and human player support.
 *
 * <p>This is a modified version of WerewolfGame that:
 *
 * <ul>
 *   <li>Emits events instead of printing to console for web interface display
 *   <li>Supports one human player with role-based event visibility
 *   <li>Allows human interaction via WebUserInput
 * </ul>
 */
public class WerewolfWebGame {

    private final GameEventEmitter emitter;
    private final PromptProvider prompts;
    private final GameMessages messages;
    private final LanguageConfig langConfig;
    private final WerewolfUtils utils;
    private final WebUserInput userInput;
    private final Role selectedHumanRole;
    private final GameConfiguration gameConfig;

    private DashScopeChatModel model;
    private GameState gameState;
    private Player humanPlayer;
    // Mapping from player name to assigned TTS voice (randomized per game)
    private Map<String, Qwen3TTSFlashVoice> playerVoices;

    public WerewolfWebGame(GameEventEmitter emitter, LocalizationBundle bundle) {
        this(emitter, bundle, null, null, new GameConfiguration());
    }

    public WerewolfWebGame(
            GameEventEmitter emitter, LocalizationBundle bundle, WebUserInput userInput) {
        this(emitter, bundle, userInput, null, new GameConfiguration());
    }

    public WerewolfWebGame(
            GameEventEmitter emitter,
            LocalizationBundle bundle,
            WebUserInput userInput,
            Role selectedHumanRole) {
        this(emitter, bundle, userInput, selectedHumanRole, new GameConfiguration());
    }

    /**
     * Create a new WerewolfWebGame with optional human role selection and game configuration.
     *
     * @param emitter The game event emitter
     * @param bundle The localization bundle
     * @param userInput The user input handler (null for AI-only game)
     * @param selectedHumanRole The role selected by human player (null for random)
     * @param gameConfig The game configuration with player and role counts
     */
    public WerewolfWebGame(
            GameEventEmitter emitter,
            LocalizationBundle bundle,
            WebUserInput userInput,
            Role selectedHumanRole,
            GameConfiguration gameConfig) {
        this.emitter = emitter;
        this.prompts = bundle.prompts();
        this.messages = bundle.messages();
        this.langConfig = bundle.langConfig();
        this.utils = new WerewolfUtils(messages);
        this.userInput = userInput;
        this.selectedHumanRole = selectedHumanRole;
        this.gameConfig = gameConfig;
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

            nightPhase(round);

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
        // Initialize per-game TTS voice mapping
        playerVoices = new HashMap<>();

        List<Role> roles = new ArrayList<>();
        for (int i = 0; i < gameConfig.getVillagerCount(); i++) roles.add(Role.VILLAGER);
        for (int i = 0; i < gameConfig.getWerewolfCount(); i++) roles.add(Role.WEREWOLF);
        for (int i = 0; i < gameConfig.getSeerCount(); i++) roles.add(Role.SEER);
        for (int i = 0; i < gameConfig.getWitchCount(); i++) roles.add(Role.WITCH);
        for (int i = 0; i < gameConfig.getHunterCount(); i++) roles.add(Role.HUNTER);
        Collections.shuffle(roles);

        // Determine human player index based on selected role
        int humanPlayerIndex = -1;
        if (userInput != null) {
            if (selectedHumanRole != null) {
                // Find the first index with the selected role
                for (int i = 0; i < roles.size(); i++) {
                    if (roles.get(i) == selectedHumanRole) {
                        humanPlayerIndex = i;
                        break;
                    }
                }
                // If somehow the role wasn't found (shouldn't happen), fall back to random
                if (humanPlayerIndex == -1) {
                    humanPlayerIndex = new Random().nextInt(roles.size());
                }
            } else {
                // Random role selection
                humanPlayerIndex = new Random().nextInt(roles.size());
            }
        }
        List<Integer> wolfRoleIndex = new ArrayList<>();
        for (int i = 0; i < roles.size(); i++) {
            if (roles.get(i) == Role.WEREWOLF) {
                wolfRoleIndex.add(i);
            }
        }
        List<Player> players = new ArrayList<>();
        List<String> playerNames = new ArrayList<>(langConfig.getPlayerNames());
        int totalPlayers = gameConfig.getTotalPlayerCount();
        // Generate player names if needed
        for (int i = playerNames.size(); i < totalPlayers; i++) {
            playerNames.add(String.valueOf(i + 1));
        }
        String allWolfNames =
                String.join(
                        ",",
                        wolfRoleIndex.stream()
                                .map(index -> playerNames.get(index))
                                .collect(Collectors.toUnmodifiableList()));

        for (int i = 0; i < roles.size(); i++) {
            String name = playerNames.get(i);
            Role role = roles.get(i);

            AgentBase agent;
            boolean isHuman = (i == humanPlayerIndex);

            if (isHuman) {
                // Create UserAgent for human player
                agent = UserAgent.builder().name(name).inputMethod(userInput).build();
            } else {
                // Create AI agent for other players
                List<String> argList = new ArrayList<>();
                // {0} = role name, {1} = player number, {2} = total players, {3} = villager count,
                // {4} = werewolf count
                argList.add(messages.getRoleDisplayName(role));
                argList.add(name);
                argList.add(String.valueOf(gameConfig.getTotalPlayerCount()));
                argList.add(String.valueOf(gameConfig.getVillagerCount()));
                argList.add(String.valueOf(gameConfig.getWerewolfCount()));
                String partnerInfo = role == Role.WEREWOLF ? allWolfNames : "";
                String systemPrompt =
                        prompts.getSystemPrompt(role, argList.toArray(new String[0]), partnerInfo);
                agent =
                        ReActAgent.builder()
                                .name(name)
                                .sysPrompt(systemPrompt)
                                .model(model)
                                .memory(new InMemoryMemory())
                                .toolkit(new Toolkit())
                                .structuredOutputReminder(StructuredOutputReminder.PROMPT)
                                .build();
            }

            Player player =
                    Player.builder().agent(agent).name(name).role(role).isHuman(isHuman).build();
            players.add(player);

            if (isHuman) {
                humanPlayer = player;
            }
        }

        // Set human player info in emitter for role-based visibility
        if (humanPlayer != null) {
            emitter.setHumanPlayer(humanPlayer.getRole(), humanPlayer.getName());
        }

        // Emit player initialization
        // God view: complete player info with roles
        List<Map<String, Object>> godViewPlayersInfo = new ArrayList<>();
        // Player view: depends on human player's role
        List<Map<String, Object>> playerViewInfo = new ArrayList<>();

        // For werewolves, they can see other werewolves
        List<String> werewolfNames =
                players.stream()
                        .filter(p -> p.getRole() == Role.WEREWOLF)
                        .map(Player::getName)
                        .collect(Collectors.toList());

        for (Player player : players) {
            // God view - includes all role information
            Map<String, Object> godInfo = new HashMap<>();
            godInfo.put("name", player.getName());
            godInfo.put("role", player.getRole().name());
            godInfo.put("roleDisplay", messages.getRoleDisplayName(player.getRole()));
            godInfo.put("roleSymbol", messages.getRoleSymbol(player.getRole()));
            godInfo.put("alive", true);
            godInfo.put("isHuman", player.isHuman());
            godViewPlayersInfo.add(godInfo);

            // Player view - what the human player can see
            Map<String, Object> playerInfo = new HashMap<>();
            playerInfo.put("name", player.getName());
            playerInfo.put("alive", true);
            playerInfo.put("isHuman", player.isHuman());

            // Human player can see their own role
            if (player.isHuman()) {
                playerInfo.put("role", player.getRole().name());
                playerInfo.put("roleDisplay", messages.getRoleDisplayName(player.getRole()));
                playerInfo.put("roleSymbol", messages.getRoleSymbol(player.getRole()));
            } else if (humanPlayer != null
                    && humanPlayer.getRole() == Role.WEREWOLF
                    && player.getRole() == Role.WEREWOLF) {
                // Werewolf can see other werewolves
                playerInfo.put("role", player.getRole().name());
                playerInfo.put("roleDisplay", messages.getRoleDisplayName(player.getRole()));
                playerInfo.put("roleSymbol", messages.getRoleSymbol(player.getRole()));
            } else {
                // Hide other players' roles
                playerInfo.put("role", null);
                playerInfo.put("roleDisplay", "???");
                playerInfo.put("roleSymbol", "👤");
            }
            playerViewInfo.add(playerInfo);
        }
        emitter.emitGameInit(godViewPlayersInfo, playerViewInfo);

        // Emit role assignment event for human player
        if (humanPlayer != null) {
            List<String> teammates =
                    humanPlayer.getRole() == Role.WEREWOLF
                            ? werewolfNames.stream()
                                    .filter(n -> !n.equals(humanPlayer.getName()))
                                    .collect(Collectors.toList())
                            : List.of();
            emitter.emitPlayerRoleAssignment(
                    humanPlayer.getName(),
                    humanPlayer.getRole().name(),
                    messages.getRoleDisplayName(humanPlayer.getRole()),
                    teammates);
        }

        // Assign random TTS voice to each player (independent of roles)
        List<Qwen3TTSFlashVoice> voices = new ArrayList<>(List.of(Qwen3TTSFlashVoice.values()));
        Collections.shuffle(voices);
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            Qwen3TTSFlashVoice voice = voices.get(i % voices.size());
            playerVoices.put(player.getName(), voice);
        }

        return new GameState(players);
    }

    private void nightPhase(int round) {
        emitter.emitSystemMessage(messages.getNightPhaseTitle());
        gameState.clearNightResults();

        Player victim = werewolvesKill(round);
        if (victim != null) {
            gameState.setLastNightVictim(victim);
            // Don't kill immediately - mark for death at end of night
            // Werewolf kill decision is private (god view only for non-werewolves)
            emitter.emitSystemMessage(
                    messages.getWerewolvesChose(victim.getName()), EventVisibility.WEREWOLF_ONLY);

            // Sheriff badge transfer moved to day phase after death announcement
            // Mark sheriff as killed for later transfer in day phase
            if (victim.isSheriff()) {
                gameState.setSheriffKilledInNight(true);
            }
        }

        // Handle all witches
        for (Player witch : gameState.getWitches()) {
            if (witch.isAlive()) {
                witchActions(witch);
            }
        }

        // Handle all seers
        for (Player seer : gameState.getSeers()) {
            if (seer.isAlive()) {
                seerCheck(seer);
            }
        }

        // Apply all night deaths at the end after all role actions
        Player nightVictim = gameState.getLastNightVictim();
        boolean wasResurrected = gameState.isLastVictimResurrected();
        if (nightVictim != null && !wasResurrected) {
            nightVictim.kill();
            emitter.emitPlayerEliminated(
                    nightVictim.getName(),
                    messages.getRoleDisplayName(nightVictim.getRole()),
                    "killed");
        }

        Player poisonedVictim = gameState.getLastPoisonedVictim();
        if (poisonedVictim != null && poisonedVictim.isAlive()) {
            poisonedVictim.kill();
            // Elimination event already emitted in witchActions
        }

        emitStatsUpdate();
        emitter.emitSystemMessage(messages.getNightPhaseComplete());
    }

    private Player werewolvesKill(int round) {
        List<Player> werewolves = gameState.getAliveWerewolves();
        if (werewolves.isEmpty()) {
            return null;
        }

        // Werewolf discussion is visible only to werewolves
        emitter.emitSystemMessage(
                messages.getSystemWerewolfDiscussing(), EventVisibility.WEREWOLF_ONLY);

        boolean hasHumanWerewolf = werewolves.stream().anyMatch(Player::isHuman);

        try (MsgHub werewolfHub =
                MsgHub.builder()
                        .name("WerewolfDiscussion")
                        .participants(
                                werewolves.stream().map(Player::getAgent).toArray(AgentBase[]::new))
                        .announcement(prompts.createWerewolfDiscussionPrompt(gameState, round))
                        .enableAutoBroadcast(true)
                        .build()) {

            werewolfHub.enter().block();

            // Werewolves discuss in rounds, human participates in each round
            for (int discussRound = 0; discussRound < 1; discussRound++) {
                for (Player werewolf : werewolves) {
                    if (werewolf.isHuman()) {
                        // Human werewolf speaks
                        String humanInput =
                                userInput
                                        .waitForInput(
                                                WebUserInput.INPUT_SPEAK,
                                                messages.getPromptWerewolfDiscussion(),
                                                null)
                                        .block();
                        if (humanInput != null && !humanInput.isEmpty()) {
                            emitter.emitPlayerSpeak(
                                    humanPlayer.getName(), humanInput, "werewolf_discussion");
                            // Broadcast to other werewolves
                            werewolfHub
                                    .broadcast(
                                            List.of(
                                                    Msg.builder()
                                                            .name(humanPlayer.getName())
                                                            .role(MsgRole.USER)
                                                            .content(
                                                                    TextBlock.builder()
                                                                            .text(humanInput)
                                                                            .build())
                                                            .build()))
                                    .block();
                        }
                    } else {
                        // AI werewolf speaks
                        Msg response = werewolf.getAgent().call().block();
                        String content = utils.extractTextContent(response);
                        emitter.emitPlayerSpeak(werewolf.getName(), content, "werewolf_discussion");
                    }
                }
            }

            Msg votingPrompt = prompts.createWerewolfVotingPrompt(gameState);

            // Collect votes - AI werewolves vote via model, human votes via input
            List<Msg> votes = new ArrayList<>();
            List<Player> aiWerewolves = new ArrayList<>();

            // First night: werewolves can kill anyone (including self-kill)
            // Later nights: werewolves can only kill non-werewolves
            List<String> voteTargetOptions;
            if (gameState.getCurrentRound() == 1) {
                // First night: all alive players are valid targets (can self-kill)
                voteTargetOptions =
                        gameState.getAlivePlayers().stream()
                                .map(Player::getName)
                                .collect(Collectors.toList());
            } else {
                // Later nights: only non-werewolves
                voteTargetOptions =
                        gameState.getAlivePlayers().stream()
                                .filter(p -> p.getRole() != Role.WEREWOLF)
                                .map(Player::getName)
                                .collect(Collectors.toList());
            }

            for (Player werewolf : werewolves) {
                if (werewolf.isHuman()) {
                    // Human werewolf votes
                    String voteTarget =
                            userInput
                                    .waitForInput(
                                            WebUserInput.INPUT_VOTE,
                                            messages.getPromptWerewolfVote(),
                                            voteTargetOptions)
                                    .block();
                    emitter.emitPlayerVote(
                            werewolf.getName(), voteTarget, "", EventVisibility.WEREWOLF_ONLY);
                    Msg voteMsg =
                            Msg.builder()
                                    .name(werewolf.getName())
                                    .role(MsgRole.USER)
                                    .content(TextBlock.builder().text(voteTarget).build())
                                    .metadata(
                                            Map.of(
                                                    MessageMetadataKeys.STRUCTURED_OUTPUT,
                                                    Map.of(
                                                            "targetPlayer",
                                                            voteTarget,
                                                            "reason",
                                                            "")))
                                    .build();
                    votes.add(voteMsg);
                } else {
                    // AI werewolf - add to parallel list
                    aiWerewolves.add(werewolf);
                }
            }

            // Parallel voting for AI werewolves
            if (!aiWerewolves.isEmpty()) {
                List<AgentBase> aiWerewolfAgents =
                        aiWerewolves.stream().map(Player::getAgent).collect(Collectors.toList());
                List<Msg> aiVotes =
                        new FanoutPipeline(aiWerewolfAgents)
                                .execute(votingPrompt, VoteModel.class)
                                .onErrorReturn(new ArrayList<>())
                                .block();
                if (aiVotes == null) {
                    aiVotes = new ArrayList<>();
                }

                // Process results
                for (Msg vote : aiVotes) {
                    votes.add(vote);
                    try {
                        VoteModel voteData = vote.getStructuredData(VoteModel.class);
                        emitter.emitPlayerVote(
                                vote.getName(),
                                voteData.targetPlayer,
                                voteData.reason,
                                EventVisibility.WEREWOLF_ONLY);
                    } catch (Exception e) {
                        emitter.emitSystemMessage(
                                messages.getVoteParsingError(vote.getName()),
                                EventVisibility.WEREWOLF_ONLY);
                    }
                }
            }
            Player killedPlayer = utils.countVotes(votes, gameState);
            List<Msg> broadcastMsgs = new ArrayList<>();
            broadcastMsgs.add(
                    Msg.builder()
                            .name("Moderator Message")
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

            // Emit popup event for werewolf kill result (visible to werewolves only)
            if (killedPlayer != null) {
                emitter.emitNightActionWerewolfKill(killedPlayer.getName());
            }

            return killedPlayer;
        }
    }

    private void witchActions(Player witch) {
        Player victim = gameState.getLastNightVictim();
        boolean isHumanWitch = witch.isHuman();

        // Witch actions visibility based on role
        emitter.emitSystemMessage(messages.getSystemWitchActing(), EventVisibility.WITCH_ONLY);

        // Inform witch about last night's victim (regardless of heal potion availability)
        // 女巫始终能看到死亡信息，即使自己死亡
        if (victim != null && witch.getAgent() instanceof ReActAgent) {
            ReActAgent witchAgent = (ReActAgent) witch.getAgent();
            if (witchAgent.getMemory() != null) {
                String victimInfo = messages.getSystemWitchSeesVictim(victim.getName());
                Msg victimMsg =
                        Msg.builder()
                                .name("Moderator Message")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(victimInfo).build())
                                .build();
                witchAgent.getMemory().addMessage(victimMsg);
            }
        }

        boolean usedHeal = false;
        boolean isWitchDead = victim != null && victim.getName().equals(witch.getName());

        // 女巫有解药、有受害者、且受害者不是自己时才能使用解药
        if (witch.isWitchHasHealPotion() && victim != null && !isWitchDead) {
            emitter.emitSystemMessage(
                    messages.getSystemWitchSeesVictim(victim.getName()),
                    EventVisibility.WITCH_ONLY);

            if (isHumanWitch) {
                // Human witch decides to heal
                String healChoice =
                        userInput
                                .waitForInput(
                                        WebUserInput.INPUT_WITCH_HEAL,
                                        messages.getPromptWitchHeal(victim.getName()),
                                        List.of("yes", "no"))
                                .block();

                if ("yes".equalsIgnoreCase(healChoice)) {
                    victim.resurrect();
                    witch.useHealPotion();
                    gameState.setLastVictimResurrected(true);
                    usedHeal = true;
                    emitter.emitPlayerAction(
                            witch.getName(),
                            messages.getRoleDisplayName(Role.WITCH),
                            messages.getActionWitchUseHeal(),
                            victim.getName(),
                            messages.getActionWitchHealResult(),
                            EventVisibility.WITCH_ONLY);
                    emitter.emitPlayerResurrected(victim.getName());
                    // Emit popup event for witch heal result
                    emitter.emitNightActionWitchHeal(victim.getName());
                } else {
                    emitter.emitPlayerAction(
                            witch.getName(),
                            messages.getRoleDisplayName(Role.WITCH),
                            messages.getActionWitchUseHeal(),
                            null,
                            messages.getActionWitchHealSkip(),
                            EventVisibility.WITCH_ONLY);
                }
            } else {
                // AI witch decides
                try {
                    Msg healDecision =
                            witch.getAgent()
                                    .call(
                                            prompts.createWitchHealPrompt(victim, gameState),
                                            WitchHealModel.class)
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
                                messages.getActionWitchHealResult(),
                                EventVisibility.WITCH_ONLY);
                        emitter.emitPlayerResurrected(victim.getName());
                        // Emit popup event for witch heal result
                        emitter.emitNightActionWitchHeal(victim.getName());
                    } else {
                        emitter.emitPlayerAction(
                                witch.getName(),
                                messages.getRoleDisplayName(Role.WITCH),
                                messages.getActionWitchUseHeal(),
                                null,
                                messages.getActionWitchHealSkip(),
                                EventVisibility.WITCH_ONLY);
                    }
                } catch (Exception e) {
                    emitter.emitError(messages.getErrorWitchHeal(e.getMessage()));
                }
            }
        }

        if (witch.isWitchHasPoisonPotion() && !usedHeal) {
            List<String> poisonTargetOptions =
                    gameState.getAlivePlayers().stream()
                            .filter(p -> !p.getName().equals(witch.getName()))
                            .map(Player::getName)
                            .collect(Collectors.toList());
            poisonTargetOptions.add(0, "skip"); // Add skip option

            if (isHumanWitch) {
                // Human witch decides to poison
                String poisonTarget =
                        userInput
                                .waitForInput(
                                        WebUserInput.INPUT_WITCH_POISON,
                                        messages.getPromptWitchPoison(),
                                        poisonTargetOptions)
                                .block();

                if (poisonTarget != null
                        && !poisonTarget.isEmpty()
                        && !"skip".equalsIgnoreCase(poisonTarget)) {
                    Player targetPlayer = gameState.findPlayerByName(poisonTarget);
                    if (targetPlayer != null && targetPlayer.isAlive()) {
                        // Don't kill immediately - mark for death at end of night
                        witch.usePoisonPotion();
                        gameState.setLastPoisonedVictim(targetPlayer);
                        emitter.emitPlayerAction(
                                witch.getName(),
                                messages.getRoleDisplayName(Role.WITCH),
                                messages.getActionWitchUsePoison(),
                                targetPlayer.getName(),
                                messages.getActionWitchPoisonResult(),
                                EventVisibility.WITCH_ONLY);
                        emitter.emitPlayerEliminated(
                                targetPlayer.getName(),
                                messages.getRoleDisplayName(targetPlayer.getRole()),
                                "poisoned");
                        // Emit popup event for witch poison result
                        emitter.emitNightActionWitchPoison(targetPlayer.getName());
                    }
                } else {
                    emitter.emitPlayerAction(
                            witch.getName(),
                            messages.getRoleDisplayName(Role.WITCH),
                            messages.getActionWitchUsePoison(),
                            null,
                            messages.getActionWitchPoisonSkip(),
                            EventVisibility.WITCH_ONLY);
                }
            } else {
                // AI witch decides
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
                            // Don't kill immediately - mark for death at end of night
                            witch.usePoisonPotion();
                            gameState.setLastPoisonedVictim(targetPlayer);
                            emitter.emitPlayerAction(
                                    witch.getName(),
                                    messages.getRoleDisplayName(Role.WITCH),
                                    messages.getActionWitchUsePoison(),
                                    targetPlayer.getName(),
                                    messages.getActionWitchPoisonResult(),
                                    EventVisibility.WITCH_ONLY);
                            emitter.emitPlayerEliminated(
                                    targetPlayer.getName(),
                                    messages.getRoleDisplayName(targetPlayer.getRole()),
                                    "poisoned");
                            // Emit popup event for witch poison result
                            emitter.emitNightActionWitchPoison(targetPlayer.getName());
                        }
                    } else {
                        emitter.emitPlayerAction(
                                witch.getName(),
                                messages.getRoleDisplayName(Role.WITCH),
                                messages.getActionWitchUsePoison(),
                                null,
                                messages.getActionWitchPoisonSkip(),
                                EventVisibility.WITCH_ONLY);
                    }
                } catch (Exception e) {
                    emitter.emitError(messages.getErrorWitchPoison(e.getMessage()));
                }
            }
        }

        emitStatsUpdate();
    }

    private void seerCheck(Player seer) {
        boolean isHumanSeer = seer.isHuman();

        // Seer actions visibility based on role
        emitter.emitSystemMessage(messages.getSystemSeerActing(), EventVisibility.SEER_ONLY);

        List<String> checkTargetOptions =
                gameState.getAlivePlayers().stream()
                        .filter(p -> !p.getName().equals(seer.getName()))
                        .map(Player::getName)
                        .collect(Collectors.toList());

        if (isHumanSeer) {
            // Human seer chooses who to check
            String targetName =
                    userInput
                            .waitForInput(
                                    WebUserInput.INPUT_SEER_CHECK,
                                    messages.getPromptSeerCheck(),
                                    checkTargetOptions)
                            .block();

            if (targetName != null && !targetName.isEmpty()) {
                Player target = gameState.findPlayerByName(targetName);
                if (target != null && target.isAlive()) {
                    boolean isWerewolf = target.getRole() == Role.WEREWOLF;
                    String identity =
                            isWerewolf ? messages.getIsWerewolf() : messages.getNotWerewolf();
                    emitter.emitPlayerAction(
                            seer.getName(),
                            messages.getRoleDisplayName(Role.SEER),
                            messages.getActionSeerCheck(),
                            target.getName(),
                            target.getName() + " " + identity,
                            EventVisibility.SEER_ONLY);
                    // Emit popup event for seer check result
                    emitter.emitNightActionSeerCheck(target.getName(), isWerewolf);
                }
            }
        } else {
            // AI seer decides
            try {
                Msg checkDecision =
                        seer.getAgent()
                                .call(
                                        prompts.createSeerCheckPrompt(gameState),
                                        SeerCheckModel.class)
                                .block();

                SeerCheckModel checkModel = checkDecision.getStructuredData(SeerCheckModel.class);

                if (checkModel.targetPlayer != null) {
                    Player target = gameState.findPlayerByName(checkModel.targetPlayer);
                    if (target != null && target.isAlive()) {
                        boolean isWerewolf = target.getRole() == Role.WEREWOLF;
                        String identity =
                                isWerewolf ? messages.getIsWerewolf() : messages.getNotWerewolf();
                        emitter.emitPlayerAction(
                                seer.getName(),
                                messages.getRoleDisplayName(Role.SEER),
                                messages.getActionSeerCheck(),
                                target.getName(),
                                target.getName() + " " + identity,
                                EventVisibility.SEER_ONLY);
                        // Emit popup event for seer check result
                        emitter.emitNightActionSeerCheck(target.getName(), isWerewolf);
                        ((ReActAgent) seer.getAgent())
                                .getMemory()
                                .addMessage(prompts.createSeerResultPrompt(target));
                    }
                }
            } catch (Exception e) {
                emitter.emitError(messages.getErrorSeerCheck(e.getMessage()));
            }
        }
    }

    private void dayPhase() {
        emitter.emitSystemMessage(messages.getDayPhaseTitle());
        String nightAnnouncement = prompts.createNightResultAnnouncement(gameState);
        emitter.emitSystemMessage(nightAnnouncement);
        // Broadcast night result to all agents
        Msg nightResultMsg =
                Msg.builder()
                        .name("Moderator Message")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(nightAnnouncement).build())
                        .build();

        // Send night result to all alive agents
        for (Player player : gameState.getAlivePlayers()) {
            if (!player.isHuman() && player.getAgent() instanceof ReActAgent) {
                ((ReActAgent) player.getAgent()).getMemory().addMessage(nightResultMsg);
            }
        }

        // Handle all hunters who were eliminated
        for (Player hunter : gameState.getHunters()) {
            if (!hunter.isAlive()
                    && (hunter.equals(gameState.getLastNightVictim())
                            || hunter.equals(gameState.getLastPoisonedVictim()))) {
                hunterShoot(hunter, false);
                if (checkGameEnd()) {
                    return;
                }
            }
        }

        // Handle sheriff badge transfer if sheriff was killed at night
        // This happens after death announcement in day phase
        if (gameState.isSheriffKilledInNight()) {
            Player killedSheriff = gameState.getSheriff();
            if (killedSheriff != null && !killedSheriff.isAlive()) {
                sheriffTransferBadge(killedSheriff);
                // Reset the flag after transfer
                gameState.setSheriffKilledInNight(false);
            }
        }

        // Sheriff election only on first day
        if (gameState.getCurrentRound() == 1) {
            // Sheriff election
            emitter.emitSystemMessage(messages.getSystemSheriffElectionStart());
            sheriffElectionPhase();
        }
        discussionPhase();
        Player votedOut = votingPhase();

        if (votedOut != null) {
            votedOut.kill();
            String roleName = messages.getRoleDisplayName(votedOut.getRole());
            emitter.emitPlayerEliminated(votedOut.getName(), roleName, "voted");

            // Handle sheriff badge transfer if sheriff is voted out
            if (votedOut.isSheriff()) {
                sheriffTransferBadge(votedOut);
            }

            if (votedOut.getRole() == Role.HUNTER) {
                // Day vote out: reveal hunter identity and broadcast to all agents
                hunterShoot(votedOut, true);
            }
        }

        emitStatsUpdate();
    }

    private void discussionPhase() {
        List<Player> alivePlayers = gameState.getAlivePlayers();
        if (alivePlayers.size() <= 2) {
            return;
        }

        // Get sheriff for speak order determination
        Player sheriff = gameState.getSheriff();

        // Apply speak order: start from next player after sheriff, sheriff speaks last
        if (sheriff != null && alivePlayers.contains(sheriff)) {
            // Reorder list: start from player after sheriff
            List<Player> reordered = new ArrayList<>();
            int sheriffIndex = alivePlayers.indexOf(sheriff);

            // Determine direction based on speak order
            boolean reversed = gameState.isSpeakOrderReversed();

            if (!reversed) {
                // Normal order: start from player after sheriff
                for (int i = sheriffIndex + 1; i < alivePlayers.size(); i++) {
                    reordered.add(alivePlayers.get(i));
                }
                for (int i = 0; i < sheriffIndex; i++) {
                    reordered.add(alivePlayers.get(i));
                }
            } else {
                // Reversed order: start from player before sheriff
                for (int i = sheriffIndex - 1; i >= 0; i--) {
                    reordered.add(alivePlayers.get(i));
                }
                for (int i = alivePlayers.size() - 1; i > sheriffIndex; i--) {
                    reordered.add(alivePlayers.get(i));
                }
            }
            // Sheriff always speaks last
            reordered.add(sheriff);
            alivePlayers = reordered;
        }

        emitter.emitSystemMessage(messages.getSystemDayDiscussionStart());

        try (MsgHub discussionHub =
                MsgHub.builder()
                        .name("DayDiscussion")
                        .participants(
                                alivePlayers.stream()
                                        .map(Player::getAgent)
                                        .toArray(AgentBase[]::new))
                        .announcement(
                                prompts.createDiscussionPrompt(
                                        gameState,
                                        alivePlayers.stream()
                                                .map(Player::getName)
                                                .collect(Collectors.joining("->"))))
                        .enableAutoBroadcast(true)
                        .build()) {

            discussionHub.enter().block();
            emitter.emitSystemMessage(messages.getDiscussionRound(1));
            for (Player player : alivePlayers) {
                if (player.isHuman()) {
                    // Human player speaks
                    String humanInput =
                            userInput
                                    .waitForInput(
                                            WebUserInput.INPUT_SPEAK,
                                            messages.getPromptDayDiscussion(),
                                            null)
                                    .block();
                    if (humanInput != null && !humanInput.isEmpty()) {
                        emitter.emitPlayerSpeak(player.getName(), humanInput, "day_discussion");
                        // Broadcast to other players
                        discussionHub
                                .broadcast(
                                        List.of(
                                                Msg.builder()
                                                        .name(player.getName())
                                                        .role(MsgRole.USER)
                                                        .content(
                                                                TextBlock.builder()
                                                                        .text(humanInput)
                                                                        .build())
                                                        .build()))
                                .block();
                    }
                } else {
                    // AI player speaks
                    Msg response = player.getAgent().call().block();
                    String content = utils.extractTextContent(response);
                    emitter.emitPlayerSpeak(player.getName(), content, "day_discussion");

                    // Generate TTS for AI speech (only during day discussion)
                    generateTTSForSpeech(player.getName(), content);
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
                                        .toArray(AgentBase[]::new))
                        .enableAutoBroadcast(true)
                        .build()) {

            votingHub.enter().block();
            votingHub.setAutoBroadcast(false);

            Msg votingPrompt = prompts.createVotingPrompt(gameState);

            // Collect votes
            List<Msg> votes = new ArrayList<>();
            List<Player> aiPlayers = new ArrayList<>();
            List<String> voteTargetOptions =
                    alivePlayers.stream().map(Player::getName).collect(Collectors.toList());

            for (Player player : alivePlayers) {
                if (player.isHuman()) {
                    // Human player votes
                    List<String> optionsExcludingSelf =
                            voteTargetOptions.stream()
                                    .filter(n -> !n.equals(player.getName()))
                                    .collect(Collectors.toList());

                    String voteTarget =
                            userInput
                                    .waitForInput(
                                            WebUserInput.INPUT_VOTE,
                                            messages.getPromptDayVote(),
                                            optionsExcludingSelf)
                                    .block();

                    emitter.emitPlayerVote(
                            player.getName(), voteTarget, "", EventVisibility.PUBLIC);
                    Msg voteMsg =
                            Msg.builder()
                                    .name(player.getName())
                                    .role(MsgRole.USER)
                                    .content(TextBlock.builder().text(voteTarget).build())
                                    .metadata(
                                            Map.of(
                                                    MessageMetadataKeys.STRUCTURED_OUTPUT,
                                                    Map.of(
                                                            "targetPlayer",
                                                            voteTarget,
                                                            "reason",
                                                            "")))
                                    .build();
                    votes.add(voteMsg);
                } else {
                    // AI player votes - parallel execution
                    aiPlayers.add(player);
                }
            }

            // Parallel voting for AI players
            if (!aiPlayers.isEmpty()) {
                List<AgentBase> aiPlayerAgents =
                        aiPlayers.stream().map(Player::getAgent).collect(Collectors.toList());
                List<Msg> aiVotes =
                        new FanoutPipeline(aiPlayerAgents)
                                .execute(votingPrompt, VoteModel.class)
                                .onErrorReturn(new ArrayList<>())
                                .block();
                if (aiVotes == null) {
                    aiVotes = new ArrayList<>();
                }

                // Process results
                for (Msg vote : aiVotes) {
                    votes.add(vote);
                    try {
                        VoteModel voteData = vote.getStructuredData(VoteModel.class);
                        emitter.emitPlayerVote(
                                vote.getName(),
                                voteData.targetPlayer,
                                voteData.reason,
                                EventVisibility.PUBLIC);
                    } catch (Exception e) {
                        emitter.emitSystemMessage(messages.getVoteParsingError(vote.getName()));
                    }
                }
            }

            Player votedOut = utils.countVotes(votes, gameState);

            // Build detailed voting result with each player's vote
            StringBuilder detailedResult = new StringBuilder();
            detailedResult.append("【投票放逐结果】\n");
            Map<String, List<String>> votesByTarget = new HashMap<>();
            for (Msg vote : votes) {
                try {
                    VoteModel voteData = vote.getStructuredData(VoteModel.class);
                    votesByTarget
                            .computeIfAbsent(voteData.targetPlayer, k -> new ArrayList<>())
                            .add(vote.getName());
                } catch (Exception e) {
                    // Skip invalid votes
                }
            }
            for (Map.Entry<String, List<String>> entry : votesByTarget.entrySet()) {
                String target = entry.getKey();
                List<String> voters = entry.getValue();
                detailedResult.append(String.format("%s ⬅ %s\n", target, String.join("、", voters)));
            }
            if (votedOut == null) {
                detailedResult.append("（无人被放逐）");
            } else {
                detailedResult.append(String.format("被放逐玩家：%s", votedOut.getName()));
            }

            // Build the voting result message to broadcast to all participants
            Msg votedOutMsg =
                    Msg.builder()
                            .name("Moderator Message")
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(detailedResult.toString()).build())
                            .build();
            votingHub.broadcast(votedOutMsg).block();

            return votedOut;
        }
    }

    /**
     * Sheriff election phase on first day. Only the seer and one werewolf will run for sheriff,
     * other players vote.
     */
    /**
     * Sheriff election phase.
     *
     * <p>1. Candidates register 2. Campaign speeches 3. Voting
     */
    private void sheriffElectionPhase() {
        List<Player> alivePlayers = gameState.getAlivePlayers();
        if (alivePlayers.size() < 3) {
            return; // Not enough players for election
        }

        // Step 1: Registration phase
        List<Player> candidates = handleSheriffRegistration(alivePlayers);
        if (candidates.isEmpty()) {
            emitter.emitSystemMessage(messages.getSystemNoCandidates());
            return;
        }

        if (candidates.size() == 1) {
            // Only one candidate, automatically elected
            Player sheriff = candidates.get(0);
            gameState.setSheriff(sheriff);
            // Single candidate gets 1 vote automatically
            Map<String, Object> voteDetails = new HashMap<>();
            Map<String, Object> candidateDetail = new HashMap<>();
            candidateDetail.put("votes", 1);
            candidateDetail.put("voters", Collections.singletonList("自动当选"));
            voteDetails.put(sheriff.getName(), candidateDetail);
            emitter.emitSheriffElected(sheriff.getName(), 1, voteDetails);
            decideSpeakOrder(sheriff);
            return;
        }

        // Step 2: Campaign speeches - use MsgHub to broadcast
        handleCampaignSpeeches(candidates, alivePlayers);

        // Step 3: Voting phase
        Player sheriff = handleSheriffVoting(candidates, alivePlayers);
        if (sheriff != null) {
            decideSpeakOrder(sheriff);
        }
    }

    /**
     * Handle sheriff registration phase.
     *
     * @param alivePlayers all alive players
     * @return list of candidates who registered
     */
    private List<Player> handleSheriffRegistration(List<Player> alivePlayers) {
        emitter.emitSystemMessage("【警长竞选】进入上警环节，所有玩家决定是否上警...");

        List<Player> candidates = new ArrayList<>();

        // Separate human and AI players
        List<Player> aiPlayers = new ArrayList<>();
        for (Player player : alivePlayers) {
            if (player.isHuman()) {
                String input =
                        userInput
                                .waitForInput(
                                        WebUserInput.INPUT_SHERIFF_REGISTER,
                                        messages.getPromptSheriffRegister(),
                                        List.of("是", "否"))
                                .block();
                boolean shouldRegister = "是".equals(input) || "yes".equalsIgnoreCase(input);
                if (shouldRegister) {
                    player.registerForSheriff();
                    candidates.add(player);
                }
            } else {
                aiPlayers.add(player);
            }
        }

        // Parallel AI registration decisions
        if (!aiPlayers.isEmpty()) {
            Msg registrationPrompt = prompts.createSheriffRegistrationPrompt(gameState);
            List<AgentBase> aiPlayerAgents =
                    aiPlayers.stream().map(Player::getAgent).collect(Collectors.toList());
            List<Msg> registrationMsgs =
                    new FanoutPipeline(aiPlayerAgents)
                            .execute(registrationPrompt, SheriffRegistrationModel.class)
                            .onErrorReturn(new ArrayList<>())
                            .block();
            if (registrationMsgs == null) {
                registrationMsgs = new ArrayList<>();
            }

            // Process results - match msgs back to players by agent name
            Map<String, Player> agentNameToPlayer = new HashMap<>();
            for (Player player : aiPlayers) {
                agentNameToPlayer.put(player.getName(), player);
            }
            for (Msg registrationMsg : registrationMsgs) {
                Player player = agentNameToPlayer.get(registrationMsg.getName());
                if (player == null) {
                    continue;
                }
                try {
                    SheriffRegistrationModel registration =
                            registrationMsg.getStructuredData(SheriffRegistrationModel.class);
                    boolean shouldRegister =
                            registration.registerForSheriff != null
                                    && registration.registerForSheriff;
                    if (shouldRegister) {
                        player.registerForSheriff();
                        candidates.add(player);
                    }
                } catch (Exception e) {
                    // Skip failed results
                }
            }
        }

        // Wait 2 seconds before announcing candidates
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Announce candidates with raise hand icon
        List<String> candidateNames =
                candidates.stream().map(Player::getName).collect(Collectors.toList());
        emitter.emitSheriffCandidatesAnnounced(candidateNames);
        emitter.emitSystemMessage("【上警玩家】" + String.join("、", candidateNames));

        return candidates;
    }

    /**
     * Handle campaign speeches using MsgHub for automatic broadcasting.
     *
     * @param candidates list of candidates
     * @param alivePlayers all alive players
     * @return ordered list of candidates for speech
     */
    private List<Player> handleCampaignSpeeches(
            List<Player> candidates, List<Player> alivePlayers) {
        emitter.emitSystemMessage(messages.getSystemCampaignStart());

        // Randomly select start position and order
        Random random = new Random();
        int startIndex = random.nextInt(candidates.size());
        boolean isReversed = random.nextBoolean();

        List<Player> orderedCandidates = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            int index =
                    isReversed
                            ? (startIndex - i + candidates.size()) % candidates.size()
                            : (startIndex + i) % candidates.size();
            orderedCandidates.add(candidates.get(index));
        }

        // Announce speech order
        String orderAnnouncement =
                String.format(
                        "【发言顺序】随机从 %s 开始，%s发言：%s",
                        orderedCandidates.get(0).getName(),
                        isReversed ? "逆序" : "顺序",
                        orderedCandidates.stream()
                                .map(Player::getName)
                                .collect(Collectors.joining(" -> ")));
        emitter.emitSystemMessage(orderAnnouncement);

        // Use MsgHub to broadcast speeches to all agents
        try (MsgHub campaignHub =
                MsgHub.builder()
                        .name("警长竞选发言")
                        .announcement(
                                prompts.createSheriffElectionStartPrompt(
                                        gameState, orderedCandidates))
                        .enableAutoBroadcast(true)
                        .participants(
                                alivePlayers.stream()
                                        .filter(p -> !p.isHuman())
                                        .map(Player::getAgent)
                                        .toArray(AgentBase[]::new))
                        .build()) {

            campaignHub.enter().block();

            for (Player candidate : orderedCandidates) {
                if (candidate.isHuman()) {
                    String speech =
                            userInput
                                    .waitForInput(
                                            WebUserInput.INPUT_SHERIFF_CAMPAIGN,
                                            messages.getPromptSheriffCampaign(candidate.getName()),
                                            null)
                                    .block();
                    emitter.emitSheriffCampaign(candidate.getName(), speech, null, null);

                    // Console log for debugging
                    System.out.println("\n=== 警长竞选发言 ===");
                    System.out.println("玩家: " + candidate.getName());
                    System.out.println("发言: " + speech);
                    System.out.println("==================\n");
                    campaignHub
                            .broadcast(
                                    Msg.builder()
                                            .name(candidate.getName())
                                            .role(MsgRole.USER)
                                            .content(TextBlock.builder().text(speech).build())
                                            .build())
                            .block();
                } else {
                    try {
                        campaignHub.setAutoBroadcast(false);
                        Msg campaignMsg =
                                candidate
                                        .getAgent()
                                        .call(
                                                prompts.createSheriffCampaignPrompt(
                                                        gameState, candidate),
                                                SheriffCampaignModel.class)
                                        .block();
                        SheriffCampaignModel campaign =
                                campaignMsg.getStructuredData(SheriffCampaignModel.class);
                        // 规范化空值：空白字符串视为null，前端不显示
                        String checkResult =
                                (campaign.checkResult != null
                                                && !campaign.checkResult.trim().isEmpty())
                                        ? campaign.checkResult.trim()
                                        : null;
                        String nextCheckTarget =
                                (campaign.nextCheckTarget != null
                                                && !campaign.nextCheckTarget.trim().isEmpty())
                                        ? campaign.nextCheckTarget.trim()
                                        : null;
                        emitter.emitSheriffCampaign(
                                candidate.getName(),
                                campaign.campaignSpeech,
                                checkResult,
                                nextCheckTarget);

                        // Generate TTS for AI campaign speech
                        generateTTSForSpeech(candidate.getName(), campaign.campaignSpeech);

                        // Console log for debugging
                        System.out.println("\n=== 警长竞选发言 ===");
                        System.out.println("玩家: " + candidate.getName());
                        System.out.println("角色: " + candidate.getRole());
                        System.out.println("发言: " + campaign.campaignSpeech);
                        if (campaign.checkResult != null && !campaign.checkResult.isEmpty()) {
                            System.out.println("验人信息: " + campaign.checkResult);
                        }
                        if (campaign.nextCheckTarget != null
                                && !campaign.nextCheckTarget.isEmpty()) {
                            System.out.println("今晚将验: " + campaign.nextCheckTarget);
                        }
                        System.out.println("==================\n");

                        // Build full speech content
                        StringBuilder fullSpeech = new StringBuilder();
                        fullSpeech.append(campaign.campaignSpeech);
                        if (campaign.checkResult != null && !campaign.checkResult.isEmpty()) {
                            fullSpeech.append(" (验人信息: ").append(campaign.checkResult).append(")");
                        }
                        if (campaign.nextCheckTarget != null
                                && !campaign.nextCheckTarget.isEmpty()) {
                            fullSpeech
                                    .append(" (今晚验: ")
                                    .append(campaign.nextCheckTarget)
                                    .append(")");
                        }

                        // Broadcast to MsgHub
                        campaignHub.setAutoBroadcast(true);
                        campaignHub
                                .broadcast(
                                        Msg.builder()
                                                .name(candidate.getName())
                                                .role(MsgRole.USER)
                                                .content(
                                                        TextBlock.builder()
                                                                .text(fullSpeech.toString())
                                                                .build())
                                                .build())
                                .block();

                    } catch (Exception e) {
                        emitter.emitError(messages.getErrorSheriffCampaign(e.getMessage()));
                    }
                }
            }
        }

        return orderedCandidates;
    }

    /**
     * Handle sheriff voting phase.
     *
     * @param candidates list of all candidates
     * @param alivePlayers all alive players
     * @return elected sheriff, or null if election failed
     */
    private Player handleSheriffVoting(List<Player> candidates, List<Player> alivePlayers) {
        // Only non-candidates vote
        List<Player> voterList =
                alivePlayers.stream()
                        .filter(p -> !p.isRegisteredForSheriff())
                        .collect(Collectors.toList());

        if (voterList.isEmpty()) {
            // All players are candidates, auto-elect first
            Player sheriff = candidates.get(0);
            gameState.setSheriff(sheriff);
            // Build vote details for frontend popup (all candidates get 0 votes)
            Map<String, Object> voteDetails = new HashMap<>();
            for (Player candidate : candidates) {
                Map<String, Object> candidateDetail = new HashMap<>();
                candidateDetail.put("votes", 0);
                candidateDetail.put("voters", new ArrayList<String>());
                voteDetails.put(candidate.getName(), candidateDetail);
            }
            emitter.emitSheriffElected(sheriff.getName(), 0, voteDetails);
            return sheriff;
        }

        emitter.emitSystemMessage(messages.getSystemSheriffVotingStart());

        List<String> candidateNames =
                candidates.stream().map(Player::getName).collect(Collectors.toList());

        Map<String, Integer> voteCount = new HashMap<>();
        Map<String, List<String>> votersByCandidate = new HashMap<>();

        // Initialize vote tracking
        for (String candidateName : candidateNames) {
            voteCount.put(candidateName, 0);
            votersByCandidate.put(candidateName, new ArrayList<>());
        }

        // Collect votes - separate human and AI voters
        List<Player> aiVoters = new ArrayList<>();
        for (Player voter : voterList) {
            if (voter.isHuman()) {
                String voteTarget =
                        userInput
                                .waitForInput(
                                        WebUserInput.INPUT_SHERIFF_VOTE,
                                        messages.getPromptSheriffVote(),
                                        candidateNames)
                                .block();
                // Don't emit individual vote event to frontend
                voteCount.put(voteTarget, voteCount.getOrDefault(voteTarget, 0) + 1);
                votersByCandidate.get(voteTarget).add(voter.getName());
            } else {
                // AI voter - add to parallel list
                aiVoters.add(voter);
            }
        }

        // Parallel voting for AI players
        if (!aiVoters.isEmpty()) {
            Msg votingPrompt = prompts.createSheriffVotingPrompt(gameState, candidates);
            List<AgentBase> aiVoterAgents =
                    aiVoters.stream().map(Player::getAgent).collect(Collectors.toList());
            List<Msg> voteMsgs =
                    new FanoutPipeline(aiVoterAgents)
                            .execute(votingPrompt, SheriffVoteModel.class)
                            .onErrorReturn(new ArrayList<>())
                            .block();
            if (voteMsgs == null) {
                voteMsgs = new ArrayList<>();
            }

            // Process results
            for (Msg voteMsg : voteMsgs) {
                try {
                    SheriffVoteModel vote = voteMsg.getStructuredData(SheriffVoteModel.class);
                    if (vote.targetPlayer != null && candidateNames.contains(vote.targetPlayer)) {
                        voteCount.put(
                                vote.targetPlayer,
                                voteCount.getOrDefault(vote.targetPlayer, 0) + 1);
                        votersByCandidate.get(vote.targetPlayer).add(voteMsg.getName());
                    } else {
                        emitter.emitError(
                                messages.getErrorSheriffVote(
                                        "Invalid vote target: "
                                                + vote.targetPlayer
                                                + ", must be one of: "
                                                + candidateNames));
                    }
                } catch (Exception e) {
                    // Skip failed results
                }
            }
        }

        // Determine winner
        String sheriffName =
                voteCount.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(candidateNames.get(0));

        Player sheriff = gameState.findPlayerByName(sheriffName);
        if (sheriff != null) {
            int totalVotes = voteCount.getOrDefault(sheriffName, 0);

            // Build vote result message for broadcasting to all agents
            StringBuilder voteResultMsg = new StringBuilder();
            voteResultMsg.append("【警长投票结果】\n");
            // Emit vote results for each candidate (showing only vote counts and voter names)
            for (String candidateName : candidateNames) {
                int votes = voteCount.get(candidateName);
                List<String> voterNames = votersByCandidate.get(candidateName);
                String voterListStr = voterNames.isEmpty() ? "无" : String.join("、", voterNames);
                String resultLine = String.format("%s ⬅ %s", candidateName, voterListStr);
                emitter.emitSystemMessage(resultLine);
                voteResultMsg.append(resultLine).append("\n");
            }

            // Build vote details for frontend popup
            Map<String, Object> voteDetails = new HashMap<>();
            for (String candidateName : candidateNames) {
                int votes = voteCount.get(candidateName);
                List<String> voterNames = votersByCandidate.get(candidateName);
                Map<String, Object> candidateDetail = new HashMap<>();
                candidateDetail.put("votes", votes);
                candidateDetail.put("voters", voterNames);
                voteDetails.put(candidateName, candidateDetail);
            }

            // Check if sheriff badge is lost (highest vote count is 0)
            if (totalVotes == 0) {
                emitter.emitSystemMessage("【警徽流失】所有候选人得票为0，警徽流失");
                gameState.setSheriff(null);
                // Still emit sheriff elected event with voteCount=0 to trigger UI cleanup
                emitter.emitSheriffElected(null, 0, voteDetails);
                return null;
            }

            // Normal sheriff election
            gameState.setSheriff(sheriff);
            // Add sheriff elected info to broadcast message
            voteResultMsg.append(
                    String.format("\n【警长当选】%s 获得 %d 票，当选警长", sheriff.getName(), totalVotes));
            // Broadcast updated vote result to all AI agents
            broadcastToAllAgents(voteResultMsg.toString());
            // Emit sheriff elected event
            emitter.emitSheriffElected(sheriff.getName(), totalVotes, voteDetails);
        }

        return sheriff;
    }

    /** Sheriff decides the speaking order for discussion. */
    private void decideSpeakOrder(Player sheriff) {
        if (sheriff.isHuman()) {
            String order =
                    userInput
                            .waitForInput(
                                    WebUserInput.INPUT_SPEAK_ORDER,
                                    messages.getPromptSpeakOrder(),
                                    List.of("normal", "reversed"))
                            .block();
            boolean reversed = "reversed".equalsIgnoreCase(order);
            gameState.setSpeakOrderReversed(reversed);
            emitter.emitSystemMessage(
                    messages.getSystemSpeakOrderDecision(
                            sheriff.getName(), reversed ? "逆序" : "顺序"));
        } else {
            try {
                Msg orderMsg =
                        sheriff.getAgent()
                                .call(
                                        prompts.createSpeakOrderPrompt(sheriff),
                                        SpeakOrderModel.class)
                                .block();
                SpeakOrderModel model = orderMsg.getStructuredData(SpeakOrderModel.class);
                boolean reversed = !Boolean.TRUE.equals(model.normalOrder);
                gameState.setSpeakOrderReversed(reversed);
                emitter.emitSystemMessage(
                        messages.getSystemSpeakOrderDecision(
                                sheriff.getName(), reversed ? "逆序" : "顺序"));
            } catch (Exception e) {
                emitter.emitError(messages.getErrorSpeakOrder(e.getMessage()));
            }
        }
    }

    /**
     * New sheriff decides the speaking order from their position after receiving badge. This is
     * used when sheriff badge is transferred (either night death or day vote out).
     */
    private void decideSpeakOrderFromPlayer(Player newSheriff) {
        // Only ask for speak order if it's not the first day (first day already decided after
        // election)
        if (gameState.getCurrentRound() == 1 && gameState.getSheriff() != null) {
            // First day, speak order already decided after election
            return;
        }

        if (newSheriff.isHuman()) {
            String order =
                    userInput
                            .waitForInput(
                                    WebUserInput.INPUT_SPEAK_ORDER,
                                    messages.getPromptSpeakOrderFromPosition(newSheriff.getName()),
                                    List.of("normal", "reversed"))
                            .block();
            boolean reversed = "reversed".equalsIgnoreCase(order);
            gameState.setSpeakOrderReversed(reversed);
            emitter.emitSystemMessage(
                    messages.getSystemSpeakOrderDecision(
                            newSheriff.getName(), reversed ? "逆序" : "顺序"));
        } else {
            try {
                Msg orderMsg =
                        newSheriff
                                .getAgent()
                                .call(
                                        prompts.createSpeakOrderFromPositionPrompt(
                                                gameState, newSheriff),
                                        SpeakOrderModel.class)
                                .block();
                SpeakOrderModel model = orderMsg.getStructuredData(SpeakOrderModel.class);
                boolean reversed = !Boolean.TRUE.equals(model.normalOrder);
                gameState.setSpeakOrderReversed(reversed);
                emitter.emitSystemMessage(
                        messages.getSystemSpeakOrderDecision(
                                newSheriff.getName(), reversed ? "逆序" : "顺序"));
            } catch (Exception e) {
                emitter.emitError(messages.getErrorSpeakOrder(e.getMessage()));
            }
        }
    }

    /** Sheriff transfers badge when leaving the game. */
    private void sheriffTransferBadge(Player sheriff) {
        emitter.emitSystemMessage(messages.getSystemSheriffTransferStart());

        List<String> transferOptions =
                gameState.getAlivePlayers().stream()
                        .filter(p -> !p.equals(sheriff))
                        .map(Player::getName)
                        .collect(Collectors.toList());
        transferOptions.add(0, "skip");

        if (sheriff.isHuman()) {
            String targetName =
                    userInput
                            .waitForInput(
                                    WebUserInput.INPUT_SHERIFF_TRANSFER,
                                    messages.getPromptSheriffTransfer(),
                                    transferOptions)
                            .block();

            if (targetName != null
                    && !targetName.isEmpty()
                    && !"skip".equalsIgnoreCase(targetName)) {
                Player newSheriff = gameState.findPlayerByName(targetName);
                if (newSheriff != null && newSheriff.isAlive()) {
                    gameState.setSheriff(newSheriff);
                    emitter.emitSheriffTransfer(sheriff.getName(), targetName, null, "");

                    // Broadcast sheriff transfer to all AI agents
                    String transferMsg =
                            String.format("【警徽移交】%s 将警徽移交给 %s", sheriff.getName(), targetName);
                    broadcastToAllAgents(transferMsg);

                    // New sheriff decides speak order from their position
                    decideSpeakOrderFromPlayer(newSheriff);
                }
            } else {
                gameState.setSheriff(null);
                emitter.emitSheriffTransfer(sheriff.getName(), null, null, "");

                // Broadcast sheriff badge loss to all AI agents
                String lossMsg = String.format("【警徽流失】%s 选择不移交警徽，警徽流失", sheriff.getName());
                broadcastToAllAgents(lossMsg);
            }
        } else {
            try {
                Msg agentResponseMsg =
                        sheriff.getAgent()
                                .call(
                                        prompts.createSheriffTransferPrompt(gameState, sheriff),
                                        SheriffTransferModel.class)
                                .block();
                SheriffTransferModel transfer =
                        agentResponseMsg.getStructuredData(SheriffTransferModel.class);

                if (transfer.targetPlayer != null && !transfer.targetPlayer.isEmpty()) {
                    Player newSheriff = gameState.findPlayerByName(transfer.targetPlayer);
                    if (newSheriff != null && newSheriff.isAlive()) {
                        gameState.setSheriff(newSheriff);
                        // Night death: no checkInfo (no last will), day vote out: may have info
                        // For night deaths, checkInfo is always null
                        boolean isNightDeath =
                                sheriff.equals(gameState.getLastNightVictim())
                                        || sheriff.equals(gameState.getLastPoisonedVictim());
                        String checkInfo = isNightDeath ? null : transfer.checkInfo;
                        emitter.emitSheriffTransfer(
                                sheriff.getName(),
                                transfer.targetPlayer,
                                checkInfo,
                                transfer.reason);

                        // Broadcast sheriff transfer to all AI agents
                        String broadcastMsg =
                                String.format(
                                        "【警徽移交】%s 将警徽移交给 %s",
                                        sheriff.getName(), transfer.targetPlayer);
                        if (checkInfo != null && !checkInfo.isEmpty()) {
                            broadcastMsg += "，遗言：" + checkInfo;
                        }
                        if (transfer.reason != null && !transfer.reason.isEmpty()) {
                            broadcastMsg += "。理由：" + transfer.reason;
                        }
                        broadcastToAllAgents(broadcastMsg);

                        // New sheriff decides speak order from their position
                        decideSpeakOrderFromPlayer(newSheriff);
                    }
                } else {
                    gameState.setSheriff(null);
                    emitter.emitSheriffTransfer(sheriff.getName(), null, null, transfer.reason);

                    // Broadcast sheriff badge loss to all AI agents
                    String lossMsg = String.format("【警徽流失】%s 选择不移交警徽，警徽流失", sheriff.getName());
                    if (transfer.reason != null && !transfer.reason.isEmpty()) {
                        lossMsg += "。理由：" + transfer.reason;
                    }
                    broadcastToAllAgents(lossMsg);
                }
            } catch (Exception e) {
                gameState.setSheriff(null);
                emitter.emitError(messages.getErrorSheriffTransfer(e.getMessage()));
            }
        }
    }

    /**
     * Hunter shoots after being eliminated.
     *
     * @param hunter the hunter player
     * @param revealIdentity true if hunter identity should be revealed (day vote out), false if
     *     only target death should be announced (night death)
     * @return hunter shoot information string for broadcasting to agents
     */
    private String hunterShoot(Player hunter, boolean revealIdentity) {
        emitter.emitSystemMessage(messages.getSystemHunterSkill());
        boolean isHumanHunter = hunter.isHuman();

        List<String> shootTargetOptions =
                gameState.getAlivePlayers().stream()
                        .map(Player::getName)
                        .collect(Collectors.toList());
        shootTargetOptions.add(0, "skip"); // Add skip option

        if (isHumanHunter) {
            // Human hunter decides to shoot
            String shootTarget =
                    userInput
                            .waitForInput(
                                    WebUserInput.INPUT_HUNTER_SHOOT,
                                    messages.getPromptHunterShoot(),
                                    shootTargetOptions)
                            .block();

            if (shootTarget != null
                    && !shootTarget.isEmpty()
                    && !"skip".equalsIgnoreCase(shootTarget)) {
                Player targetPlayer = gameState.findPlayerByName(shootTarget);
                if (targetPlayer != null && targetPlayer.isAlive()) {
                    targetPlayer.kill();
                    String roleName = messages.getRoleDisplayName(targetPlayer.getRole());
                    emitter.emitPlayerAction(
                            hunter.getName(),
                            messages.getRoleDisplayName(Role.HUNTER),
                            messages.getActionHunterShoot(),
                            targetPlayer.getName(),
                            messages.getActionHunterShootResult(),
                            EventVisibility.PUBLIC);
                    emitter.emitPlayerEliminated(targetPlayer.getName(), roleName, "shot");
                    // Broadcast hunter shoot info to all alive agents
                    broadcastHunterShootToAllAgents(hunter, targetPlayer, revealIdentity);
                    return String.format(
                            "猎人 %s 开枪带走了 %s", hunter.getName(), targetPlayer.getName());
                }
            } else {
                emitter.emitPlayerAction(
                        hunter.getName(),
                        messages.getRoleDisplayName(Role.HUNTER),
                        messages.getActionHunterShoot(),
                        null,
                        messages.getActionHunterShootSkip(),
                        EventVisibility.PUBLIC);
            }
            return null;
        } else {
            // AI hunter decides
            try {
                Msg shootDecision =
                        hunter.getAgent()
                                .call(
                                        prompts.createHunterShootPrompt(gameState, hunter),
                                        HunterShootModel.class)
                                .block();

                HunterShootModel shootModel =
                        shootDecision.getStructuredData(HunterShootModel.class);

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
                                messages.getActionHunterShootResult(),
                                EventVisibility.PUBLIC);
                        emitter.emitPlayerEliminated(targetPlayer.getName(), roleName, "shot");
                        // Broadcast hunter shoot info to all alive agents
                        broadcastHunterShootToAllAgents(hunter, targetPlayer, revealIdentity);
                        emitStatsUpdate();
                        return String.format(
                                "猎人 %s 开枪带走了 %s", hunter.getName(), targetPlayer.getName());
                    }
                } else {
                    emitter.emitPlayerAction(
                            hunter.getName(),
                            messages.getRoleDisplayName(Role.HUNTER),
                            messages.getActionHunterShoot(),
                            null,
                            messages.getActionHunterShootSkip(),
                            EventVisibility.PUBLIC);
                }
            } catch (Exception e) {
                emitter.emitError(messages.getErrorHunterShoot(e.getMessage()));
            }
        }

        emitStatsUpdate();
        return null;
    }

    /**
     * Broadcast a message to all alive agents' memory.
     *
     * @param message the message to broadcast
     */
    private void broadcastToAllAgents(String message) {
        Msg msg =
                Msg.builder()
                        .name("Moderator Message")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(message).build())
                        .build();

        // Add to all alive players' agent memory (only ReActAgent has memory)
        for (Player player : gameState.getAlivePlayers()) {
            if (player.getAgent() instanceof ReActAgent) {
                ReActAgent reactAgent = (ReActAgent) player.getAgent();
                if (reactAgent.getMemory() != null) {
                    reactAgent.getMemory().addMessage(msg);
                }
            }
        }
    }

    /**
     * Broadcast hunter shoot information to all alive agents' memory. This ensures all AI agents
     * know about the hunter's action for future decision making. Only broadcasts when hunter
     * identity should be revealed (day vote out scenario).
     *
     * @param hunter the hunter player
     * @param target the target player who was shot
     * @param revealIdentity true to broadcast "Hunter X shot Y", false to skip broadcast
     */
    private void broadcastHunterShootToAllAgents(
            Player hunter, Player target, boolean revealIdentity) {
        if (!revealIdentity) {
            // Night death: no need to broadcast, death is already announced in night result
            return;
        }

        // Day vote out: reveal hunter identity and broadcast to all agents
        String announcement =
                messages.getSystemHunterShootAnnouncement(hunter.getName(), target.getName());
        broadcastToAllAgents(announcement);
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

    /**
     * Generate TTS audio for a player's speech and emit audio chunks to frontend. Only called
     * during day discussion phase to avoid generating TTS for votes/actions.
     *
     * @param playerName The name of the speaking player
     * @param text The text content to convert to speech
     */
    private void generateTTSForSpeech(String playerName, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            // Skip TTS if no API key
            return;
        }

        // Resolve voice for this player (fallback to a default if not assigned)
        Qwen3TTSFlashVoice voice = playerVoices != null ? playerVoices.get(playerName) : null;
        if (voice == null) {
            voice = Qwen3TTSFlashVoice.CHERRY;
        }

        // Create TTS model for this specific speech
        DashScopeRealtimeTTSModel ttsModel = null;
        try {
            ttsModel =
                    DashScopeRealtimeTTSModel.builder()
                            .apiKey(apiKey)
                            .modelName("qwen3-tts-flash-realtime")
                            .voice(voice.getVoiceId())
                            .sampleRate(24000)
                            .format("pcm")
                            .build();

            // Start session
            ttsModel.startSession();

            // Subscribe to audio stream and emit chunks
            ttsModel.getAudioStream()
                    .doOnNext(
                            audio -> {
                                if (audio.getSource() instanceof Base64Source src) {
                                    emitter.emitAudioChunk(playerName, src.getData());
                                }
                            })
                    .subscribe();

            // Push text to TTS
            ttsModel.push(text);

            // Finish and wait for all audio
            ttsModel.finish().blockLast();
        } catch (Exception e) {
            // Log error but don't fail the game
            System.err.println("TTS generation error for " + playerName + ": " + e.getMessage());
        } finally {
            // Clean up TTS resources
            if (ttsModel != null) {
                try {
                    ttsModel.close();
                } catch (Exception e) {
                    // Ignore cleanup errors
                }
            }
        }
    }
}
