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

import io.agentscope.examples.werewolf.GameConfiguration;
import io.agentscope.examples.werewolf.entity.Role;
import io.agentscope.examples.werewolf.localization.LocalizationBundle;
import io.agentscope.examples.werewolf.localization.LocalizationFactory;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * REST controller for the Werewolf game web interface.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>POST /api/game/start - Start a new game with human player and receive events via SSE</li>
 *   <li>POST /api/game/input - Submit human player input</li>
 *   <li>GET /api/game/replay - Get the complete event history (god view) after game ends</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/game")
@CrossOrigin(origins = "*")
public class WerewolfWebController {

    private final LocalizationFactory localizationFactory;

    /** Stores the emitter of the last game for replay functionality. */
    private volatile GameEventEmitter lastGameEmitter;

    /** Stores the user input handler for the current game. */
    private volatile WebUserInput currentUserInput;

    public WerewolfWebController(LocalizationFactory localizationFactory) {
        this.localizationFactory = localizationFactory;
    }

    /**
     * Start a new game with one human player and return real-time events via Server-Sent Events.
     *
     * <p>The returned stream contains events visible to the human player based on their role.
     * For example, if the human player is a werewolf, they will see werewolf discussions.
     *
     * @param lang language code (zh-CN or en-US)
     * @param roleChoice the role selected by the human player (WEREWOLF, VILLAGER, SEER, WITCH,
     *     HUNTER, or RANDOM)
     * @return SSE stream of game events for the player
     */
    @PostMapping(value = "/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<GameEvent>> startGame(
            @RequestParam(name = "lang", defaultValue = "zh-CN") String lang,
            @RequestParam(name = "role", defaultValue = "RANDOM") String roleChoice,
            @RequestParam(name = "villagerCount", required = false) Integer villagerCount,
            @RequestParam(name = "werewolfCount", required = false) Integer werewolfCount,
            @RequestParam(name = "seerCount", required = false) Integer seerCount,
            @RequestParam(name = "witchCount", required = false) Integer witchCount,
            @RequestParam(name = "hunterCount", required = false) Integer hunterCount) {

        LocalizationBundle bundle = localizationFactory.createBundle(lang);
        GameEventEmitter emitter = new GameEventEmitter();

        // Create game configuration
        GameConfiguration gameConfig = new GameConfiguration();
        if (villagerCount != null) {
            gameConfig.setVillagerCount(villagerCount);
        }
        if (werewolfCount != null) {
            gameConfig.setWerewolfCount(werewolfCount);
        }
        if (seerCount != null) {
            gameConfig.setSeerCount(seerCount);
        }
        if (witchCount != null) {
            gameConfig.setWitchCount(witchCount);
        }
        if (hunterCount != null) {
            gameConfig.setHunterCount(hunterCount);
        }

        // Validate configuration
        if (!gameConfig.isValid()) {
            emitter.emitError(
                    "Invalid game configuration. Total player count must be at least 4, "
                            + "Werewolf count must be at least 1, and all role counts must be "
                            + "non-negative integers. Please adjust the player and role counts.");
            emitter.complete();
            return emitter.getPlayerStream()
                    .map(
                            event ->
                                    ServerSentEvent.<GameEvent>builder()
                                            .event(event.getType().name().toLowerCase())
                                            .data(event)
                                            .build());
        }

        // Check for spectator mode (all AI players)
        boolean isSpectatorMode = "SPECTATOR".equalsIgnoreCase(roleChoice);

        WebUserInput userInput = isSpectatorMode ? null : new WebUserInput(emitter);
        Role selectedRole = null;

        // Parse role choice (only if not spectator mode)
        if (!isSpectatorMode && !"RANDOM".equalsIgnoreCase(roleChoice)) {
            try {
                selectedRole = Role.valueOf(roleChoice.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Invalid role, default to random
                selectedRole = null;
            }
        }

        WerewolfWebGame game =
                new WerewolfWebGame(emitter, bundle, userInput, selectedRole, gameConfig);

        // Save references for input and replay
        this.lastGameEmitter = emitter;
        this.currentUserInput = userInput;

        Mono.fromRunnable(
                        () -> {
                            try {
                                game.start();
                            } catch (Exception e) {
                                emitter.emitError("Game error: " + e.getMessage());
                                e.printStackTrace();
                            } finally {
                                emitter.complete();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        // Return player stream (filtered by role)
        return emitter.getPlayerStream()
                .map(
                        event ->
                                ServerSentEvent.<GameEvent>builder()
                                        .event(event.getType().name().toLowerCase())
                                        .data(event)
                                        .build());
    }

    /**
     * Submit human player input.
     *
     * <p>The request body should contain:
     * <ul>
     *   <li>inputType: The type of input (SPEAK, VOTE, WITCH_HEAL, etc.)</li>
     *   <li>content: The input content (text for speak, player name for vote, etc.)</li>
     * </ul>
     *
     * @param body Request body containing inputType and content
     * @return 200 OK if input accepted, 400 if no pending input or invalid type
     */
    @PostMapping("/input")
    public ResponseEntity<Map<String, Object>> submitInput(@RequestBody Map<String, String> body) {
        if (currentUserInput == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "No active game"));
        }

        String inputType = body.get("inputType");
        String content = body.get("content");

        if (inputType == null || content == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "Missing inputType or content"));
        }

        boolean accepted = currentUserInput.submitInput(inputType, content);
        if (accepted) {
            return ResponseEntity.ok(Map.of("success", true));
        } else {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "error",
                                    "No pending input of type: " + inputType));
        }
    }

    /**
     * Get the complete event history (god view) of the last game.
     *
     * <p>This endpoint returns all events including private ones like:
     * <ul>
     *   <li>Werewolf discussions and voting</li>
     *   <li>Witch's heal/poison decisions</li>
     *   <li>Seer's identity checks</li>
     *   <li>Hunter's shooting decisions</li>
     * </ul>
     *
     * <p>Should be called after the game ends for replay/review purposes.
     *
     * @return list of all game events, or 404 if no game has been played
     */
    @GetMapping("/replay")
    public ResponseEntity<List<GameEvent>> getReplay() {
        if (lastGameEmitter == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(lastGameEmitter.getGodViewHistory());
    }
}
