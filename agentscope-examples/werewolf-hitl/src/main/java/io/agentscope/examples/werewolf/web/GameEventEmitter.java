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

import io.agentscope.examples.werewolf.entity.Role;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Emitter for game events using Reactor Sinks.
 *
 * <p>This class provides a reactive stream of game events that can be subscribed to by web
 * clients. It supports role-based visibility filtering:
 * <ul>
 *   <li>Player stream: Events visible to the human player based on their role</li>
 *   <li>God view history: Complete event history for replay after game ends</li>
 * </ul>
 */
public class GameEventEmitter {

    private final Sinks.Many<GameEvent> playerSink;
    private final List<GameEvent> godViewHistory;
    private Role humanPlayerRole;
    private String humanPlayerName;

    public GameEventEmitter() {
        this.playerSink = Sinks.many().multicast().onBackpressureBuffer();
        this.godViewHistory = new ArrayList<>();
    }

    /**
     * Set the human player's role for visibility filtering.
     *
     * @param role The human player's role
     * @param playerName The human player's name
     */
    public void setHumanPlayer(Role role, String playerName) {
        this.humanPlayerRole = role;
        this.humanPlayerName = playerName;
    }

    /**
     * Get the human player's name.
     *
     * @return The human player's name
     */
    public String getHumanPlayerName() {
        return humanPlayerName;
    }

    /**
     * Get the human player's role.
     *
     * @return The human player's role
     */
    public Role getHumanPlayerRole() {
        return humanPlayerRole;
    }

    /**
     * Emit a game event with role-based visibility control.
     *
     * @param event the event to emit
     * @param visibility the visibility level of this event
     */
    private void emit(GameEvent event, EventVisibility visibility) {
        godViewHistory.add(event);
        printEventToConsole(event);
        if (visibility.isVisibleTo(humanPlayerRole)) {
            playerSink.tryEmitNext(event);
        }
    }

    /**
     * Print event to console for god view logging.
     *
     * @param event the event to print
     */
    private void printEventToConsole(GameEvent event) {
        var data = event.getData();
        switch (event.getType()) {
            case PHASE_CHANGE ->
                    System.out.println(
                            "\n═══ 回合 " + data.get("round") + " - " + data.get("phase") + " ═══");
            case PLAYER_SPEAK -> {
                String context = (String) data.get("context");
                String prefix = "werewolf_discussion".equals(context) ? "[🐺狼人密谋] " : "[发言] ";
                System.out.println(prefix + data.get("player") + ": " + data.get("content"));
            }
            case PLAYER_VOTE -> {
                String reason = (String) data.get("reason");
                String reasonText =
                        (reason != null && !reason.isEmpty()) ? " (" + reason + ")" : "";
                System.out.println(
                        "[投票] " + data.get("voter") + " → " + data.get("target") + reasonText);
            }
            case PLAYER_ACTION -> {
                String target =
                        data.get("target") != null && !data.get("target").toString().isEmpty()
                                ? " → " + data.get("target")
                                : "";
                String result =
                        data.get("result") != null && !data.get("result").toString().isEmpty()
                                ? ": " + data.get("result")
                                : "";
                System.out.println(
                        "[行动] "
                                + data.get("player")
                                + "("
                                + data.get("role")
                                + ") "
                                + data.get("action")
                                + target
                                + result);
            }
            case PLAYER_ELIMINATED -> {
                String role = data.get("role") != null ? " (" + data.get("role") + ")" : "";
                String cause = data.get("cause") != null ? " - " + data.get("cause") : "";
                System.out.println("💀 " + data.get("player") + role + cause);
            }
            case PLAYER_RESURRECTED -> System.out.println("✨ " + data.get("player") + " 被救活！");
            case SYSTEM_MESSAGE -> System.out.println("[系统] " + data.get("message"));
            case GAME_END -> {
                System.out.println("\n════════════════════════════════");
                System.out.println("游戏结束！" + data.get("winner") + " 获胜");
                System.out.println("原因: " + data.get("reason"));
                System.out.println("════════════════════════════════\n");
            }
            case ERROR -> System.err.println("[错误] " + data.get("message"));
            default -> {
                // Other events like STATS_UPDATE, WAIT_USER_INPUT etc. - skip console output
            }
        }
    }

    /**
     * Emit a game initialization event.
     * Shows the human player their own role and teammates (if werewolf).
     *
     * @param allPlayers player information (with roles for god view)
     * @param visiblePlayers player information visible to the human player
     */
    public void emitGameInit(Object allPlayers, Object visiblePlayers) {
        GameEvent godEvent = GameEvent.gameInit(allPlayers);
        godViewHistory.add(godEvent);
        System.out.println("\n════════════════════════════════");
        System.out.println("游戏初始化 - 玩家分配:");
        if (allPlayers instanceof java.util.List<?> list) {
            for (Object player : list) {
                if (player instanceof java.util.Map<?, ?> map) {
                    System.out.println("  " + map.get("name") + " - " + map.get("roleDisplay"));
                }
            }
        }
        System.out.println("════════════════════════════════\n");
        playerSink.tryEmitNext(GameEvent.gameInit(visiblePlayers));
    }

    /**
     * Emit the human player's role assignment.
     *
     * @param playerName The human player's name
     * @param role The role enum
     * @param roleDisplay The display name of the role
     * @param teammates List of teammate names (for werewolves)
     */
    public void emitPlayerRoleAssignment(
            String playerName, String role, String roleDisplay, Object teammates) {
        GameEvent event = GameEvent.playerRoleAssignment(playerName, role, roleDisplay, teammates);
        godViewHistory.add(event);
        playerSink.tryEmitNext(event);
    }

    /**
     * Emit a phase change event.
     * This is always public.
     *
     * @param round current round number
     * @param phase phase name (night/day)
     */
    public void emitPhaseChange(int round, String phase) {
        emit(GameEvent.phaseChange(round, phase), EventVisibility.PUBLIC);
    }

    /**
     * Emit a player speak event.
     * Day discussions are public, werewolf discussions are visible only to werewolves.
     *
     * @param playerName player name
     * @param content speech content
     * @param context context (e.g., "werewolf_discussion", "day_discussion")
     */
    public void emitPlayerSpeak(String playerName, String content, String context) {
        EventVisibility visibility =
                "werewolf_discussion".equals(context)
                        ? EventVisibility.WEREWOLF_ONLY
                        : EventVisibility.PUBLIC;
        emit(GameEvent.playerSpeak(playerName, content, context), visibility);
    }

    /**
     * Emit a player vote event.
     * God view shows full details including reason, player view hides reason.
     *
     * @param voterName voter name
     * @param targetName vote target
     * @param reason vote reason (shown in god view only)
     * @param visibility the visibility of this vote
     */
    public void emitPlayerVote(
            String voterName, String targetName, String reason, EventVisibility visibility) {
        // God view: shows full reason
        GameEvent godEvent = GameEvent.playerVote(voterName, targetName, reason);
        godViewHistory.add(godEvent);
        printEventToConsole(godEvent);
        // Player view: hides reason
        if (visibility.isVisibleTo(humanPlayerRole)) {
            playerSink.tryEmitNext(GameEvent.playerVote(voterName, targetName, ""));
        }
    }

    /**
     * Emit a player action event (for special roles).
     * Visibility is determined by the role performing the action.
     *
     * @param playerName player name
     * @param role role name
     * @param action action description
     * @param target target player
     * @param result action result
     * @param visibility the visibility of this action
     */
    public void emitPlayerAction(
            String playerName,
            String role,
            String action,
            String target,
            String result,
            EventVisibility visibility) {
        emit(GameEvent.playerAction(playerName, role, action, target, result), visibility);
    }

    /**
     * Emit a player eliminated event.
     * Eliminations are public but role and cause are hidden in player view.
     *
     * @param playerName eliminated player name
     * @param role player's role (shown in god view only)
     * @param cause elimination cause (shown in god view only)
     */
    public void emitPlayerEliminated(String playerName, String role, String cause) {
        // God view: shows role and cause
        GameEvent godEvent = GameEvent.playerEliminated(playerName, role, cause);
        godViewHistory.add(godEvent);
        printEventToConsole(godEvent);
        // Player view: hides role and cause (only shows name)
        playerSink.tryEmitNext(GameEvent.playerEliminated(playerName, null, null));
    }

    /**
     * Emit a player resurrected event.
     * Only visible to the witch.
     *
     * @param playerName resurrected player name
     */
    public void emitPlayerResurrected(String playerName) {
        emit(GameEvent.playerResurrected(playerName), EventVisibility.WITCH_ONLY);
    }

    /**
     * Emit a stats update event.
     * Stats are always public.
     *
     * @param alive total alive players
     * @param werewolves alive werewolves
     * @param villagers alive villagers (including special roles)
     */
    public void emitStatsUpdate(int alive, int werewolves, int villagers) {
        emit(GameEvent.statsUpdate(alive, werewolves, villagers), EventVisibility.PUBLIC);
    }

    /**
     * Emit a system message event.
     * Default is public.
     *
     * @param message system message
     */
    public void emitSystemMessage(String message) {
        emit(GameEvent.systemMessage(message), EventVisibility.PUBLIC);
    }

    /**
     * Emit a system message event with visibility control.
     *
     * @param message system message
     * @param visibility the visibility of this message
     */
    public void emitSystemMessage(String message, EventVisibility visibility) {
        emit(GameEvent.systemMessage(message), visibility);
    }

    /**
     * Emit a game end event.
     * Game end is always public.
     *
     * @param winner winning side (villagers/werewolves)
     * @param reason win reason
     */
    public void emitGameEnd(String winner, String reason) {
        emit(GameEvent.gameEnd(winner, reason), EventVisibility.PUBLIC);
    }

    /**
     * Emit an error event.
     * Errors are always public.
     *
     * @param message error message
     */
    public void emitError(String message) {
        emit(GameEvent.error(message), EventVisibility.PUBLIC);
    }

    /**
     * Emit a wait for user input event.
     *
     * @param inputType The type of input required
     * @param prompt The prompt message
     * @param options Available options (can be null)
     * @param timeoutSeconds Timeout in seconds
     */
    public void emitWaitUserInput(
            String inputType, String prompt, Object options, int timeoutSeconds) {
        GameEvent event = GameEvent.waitUserInput(inputType, prompt, options, timeoutSeconds);
        godViewHistory.add(event);
        playerSink.tryEmitNext(event);
    }

    /**
     * Emit a user input received confirmation.
     *
     * @param inputType The type of input received
     * @param content Brief description of the input
     */
    public void emitUserInputReceived(String inputType, String content) {
        GameEvent event = GameEvent.userInputReceived(inputType, content);
        godViewHistory.add(event);
        playerSink.tryEmitNext(event);
    }

    /**
     * Get the player event stream as a Flux.
     * This stream contains events visible to the human player based on their role.
     *
     * @return Flux of game events for the player
     */
    public Flux<GameEvent> getPlayerStream() {
        return playerSink.asFlux();
    }

    /**
     * Get the complete event history (god view).
     * This includes all events for replay after game ends.
     *
     * @return unmodifiable list of all game events
     */
    public List<GameEvent> getGodViewHistory() {
        return Collections.unmodifiableList(godViewHistory);
    }

    /**
     * Complete the event stream.
     */
    public void complete() {
        playerSink.tryEmitComplete();
    }
}
