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

import static io.agentscope.examples.werewolf.WerewolfGameConfig.MAX_DISCUSSION_ROUNDS;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.MAX_ROUNDS;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.user.UserAgent;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.StructuredOutputReminder;
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
 * <ul>
 *   <li>Emits events instead of printing to console for web interface display</li>
 *   <li>Supports one human player with role-based event visibility</li>
 *   <li>Allows human interaction via WebUserInput</li>
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
                        .enableThinking(true)
                        .defaultOptions(GenerateOptions.builder().thinkingBudget(512).build())
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

        List<Player> players = new ArrayList<>();
        List<String> playerNames = new ArrayList<>(langConfig.getPlayerNames());
        int totalPlayers = gameConfig.getTotalPlayerCount();
        // Generate player names if needed
        for (int i = playerNames.size(); i < totalPlayers; i++) {
            playerNames.add(String.valueOf(i + 1));
        }
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
                agent =
                        ReActAgent.builder()
                                .name(name)
                                .sysPrompt(prompts.getSystemPrompt(role, name))
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

        return new GameState(players);
    }

    private void nightPhase() {
        emitter.emitSystemMessage(messages.getNightPhaseTitle());
        gameState.clearNightResults();

        Player victim = werewolvesKill();
        if (victim != null) {
            gameState.setLastNightVictim(victim);
            victim.kill();
            // Werewolf kill decision is private (god view only for non-werewolves)
            emitter.emitSystemMessage(
                    messages.getWerewolvesChose(victim.getName()), EventVisibility.WEREWOLF_ONLY);
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

        // Emit player_eliminated events for night deaths at end of night phase
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

        // Werewolf discussion is visible only to werewolves
        emitter.emitSystemMessage(
                messages.getSystemWerewolfDiscussing(), EventVisibility.WEREWOLF_ONLY);

        boolean hasHumanWerewolf = werewolves.stream().anyMatch(Player::isHuman);

        try (MsgHub werewolfHub =
                MsgHub.builder()
                        .name("WerewolfDiscussion")
                        .participants(
                                werewolves.stream().map(Player::getAgent).toArray(AgentBase[]::new))
                        .announcement(prompts.createWerewolfDiscussionPrompt(gameState))
                        .enableAutoBroadcast(true)
                        .build()) {

            werewolfHub.enter().block();

            // Werewolves discuss in rounds, human participates in each round
            for (int round = 0; round < 1; round++) {
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

            werewolfHub.setAutoBroadcast(false);
            Msg votingPrompt = prompts.createWerewolfVotingPrompt(gameState);

            // Collect votes - AI werewolves vote via model, human votes via input
            List<Msg> votes = new ArrayList<>();
            List<String> voteTargetOptions =
                    gameState.getAlivePlayers().stream()
                            .filter(p -> p.getRole() != Role.WEREWOLF)
                            .map(Player::getName)
                            .collect(Collectors.toList());

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
                    // AI werewolf votes
                    Msg vote = werewolf.getAgent().call(votingPrompt, VoteModel.class).block();
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

            List<Msg> broadcastMsgs = new ArrayList<>(votes);
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

            return killedPlayer;
        }
    }

    private void witchActions(Player witch) {
        Player victim = gameState.getLastNightVictim();
        boolean isHumanWitch = witch.isHuman();

        // Witch actions visibility based on role
        emitter.emitSystemMessage(messages.getSystemWitchActing(), EventVisibility.WITCH_ONLY);

        // Inform witch about last night's victim (regardless of heal potion availability)
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

        if (witch.isWitchHasHealPotion() && victim != null) {
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
                                            prompts.createWitchHealPrompt(victim),
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

        if (witch.isWitchHasPoisonPotion()) {
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
                        targetPlayer.kill();
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
                            targetPlayer.kill();
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
                    String identity =
                            target.getRole() == Role.WEREWOLF
                                    ? messages.getIsWerewolf()
                                    : messages.getNotWerewolf();
                    emitter.emitPlayerAction(
                            seer.getName(),
                            messages.getRoleDisplayName(Role.SEER),
                            messages.getActionSeerCheck(),
                            target.getName(),
                            target.getName() + " " + identity,
                            EventVisibility.SEER_ONLY);
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
                        String identity =
                                target.getRole() == Role.WEREWOLF
                                        ? messages.getIsWerewolf()
                                        : messages.getNotWerewolf();
                        emitter.emitPlayerAction(
                                seer.getName(),
                                messages.getRoleDisplayName(Role.SEER),
                                messages.getActionSeerCheck(),
                                target.getName(),
                                target.getName() + " " + identity,
                                EventVisibility.SEER_ONLY);
                        seer.getAgent().call(prompts.createSeerResultPrompt(target)).block();
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

        // Handle all hunters who were eliminated
        for (Player hunter : gameState.getHunters()) {
            if (!hunter.isAlive()
                    && (hunter.equals(gameState.getLastNightVictim())
                            || hunter.equals(gameState.getLastPoisonedVictim()))) {
                // Night death: no need to broadcast, death will be announced in night result
                hunterShoot(hunter, false);
                if (checkGameEnd()) {
                    return;
                }
            }
        }

        discussionPhase();

        Player votedOut = votingPhase();

        if (votedOut != null) {
            votedOut.kill();
            String roleName = messages.getRoleDisplayName(votedOut.getRole());
            emitter.emitPlayerEliminated(votedOut.getName(), roleName, "voted");

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

        emitter.emitSystemMessage(messages.getSystemDayDiscussionStart());

        try (MsgHub discussionHub =
                MsgHub.builder()
                        .name("DayDiscussion")
                        .participants(
                                alivePlayers.stream()
                                        .map(Player::getAgent)
                                        .toArray(AgentBase[]::new))
                        .announcement(
                                Msg.builder()
                                        .name("Moderator Message")
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
                        if (!player.isHuman() && player.getAgent() instanceof ReActAgent) {
                            ((ReActAgent) player.getAgent()).getMemory().addMessage(roundPrompt);
                        }
                    }
                }

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
                    }
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
                    // AI player votes
                    Msg vote = player.getAgent().call(votingPrompt, VoteModel.class).block();
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

            List<Msg> broadcastMsgs = new ArrayList<>(votes);
            broadcastMsgs.add(
                    Msg.builder()
                            .name("Moderator Message")
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

    /**
     * Hunter shoots after being eliminated.
     *
     * @param hunter the hunter player
     * @param revealIdentity true if hunter identity should be revealed (day vote out),
     *                       false if only target death should be announced (night death)
     */
    private void hunterShoot(Player hunter, boolean revealIdentity) {
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
    }

    /**
     * Broadcast hunter shoot information to all alive agents' memory.
     * This ensures all AI agents know about the hunter's action for future decision making.
     * Only broadcasts when hunter identity should be revealed (day vote out scenario).
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
        Msg announcementMsg =
                Msg.builder()
                        .name("Moderator Message")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(announcement).build())
                        .build();

        // Add to all alive players' agent memory (only ReActAgent has memory)
        for (Player player : gameState.getAlivePlayers()) {
            if (player.getAgent() instanceof ReActAgent) {
                ReActAgent reactAgent = (ReActAgent) player.getAgent();
                if (reactAgent.getMemory() != null) {
                    reactAgent.getMemory().addMessage(announcementMsg);
                }
            }
        }
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
