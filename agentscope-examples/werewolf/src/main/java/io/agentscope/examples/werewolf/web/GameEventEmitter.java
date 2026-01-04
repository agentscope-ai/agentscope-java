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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Emitter for game events using Reactor Sinks.
 *
 * <p>This class provides a reactive stream of game events that can be subscribed to by web
 * clients.
 */
public class GameEventEmitter {

    private final Sinks.Many<GameEvent> sink;

    public GameEventEmitter() {
        this.sink = Sinks.many().multicast().onBackpressureBuffer();
    }

    /**
     * Emit a game event.
     *
     * @param event the event to emit
     */
    public void emit(GameEvent event) {
        sink.tryEmitNext(event);
    }

    /**
     * Emit a game initialization event.
     *
     * @param players player information
     */
    public void emitGameInit(Object players) {
        emit(GameEvent.gameInit(players));
    }

    /**
     * Emit a phase change event.
     *
     * @param round current round number
     * @param phase phase name (night/day)
     */
    public void emitPhaseChange(int round, String phase) {
        emit(GameEvent.phaseChange(round, phase));
    }

    /**
     * Emit a player speak event.
     *
     * @param playerName player name
     * @param content speech content
     * @param context context (e.g., "werewolf_discussion", "day_discussion")
     */
    public void emitPlayerSpeak(String playerName, String content, String context) {
        emit(GameEvent.playerSpeak(playerName, content, context));
    }

    /**
     * Emit a player vote event.
     *
     * @param voterName voter name
     * @param targetName vote target
     * @param reason vote reason
     */
    public void emitPlayerVote(String voterName, String targetName, String reason) {
        emit(GameEvent.playerVote(voterName, targetName, reason));
    }

    /**
     * Emit a player action event (for special roles).
     *
     * @param playerName player name
     * @param role role name
     * @param action action description
     * @param target target player
     * @param result action result
     */
    public void emitPlayerAction(
            String playerName, String role, String action, String target, String result) {
        emit(GameEvent.playerAction(playerName, role, action, target, result));
    }

    /**
     * Emit a player eliminated event.
     *
     * @param playerName eliminated player name
     * @param role player's role
     * @param cause elimination cause (killed/voted/poisoned/shot)
     */
    public void emitPlayerEliminated(String playerName, String role, String cause) {
        emit(GameEvent.playerEliminated(playerName, role, cause));
    }

    /**
     * Emit a player resurrected event.
     *
     * @param playerName resurrected player name
     */
    public void emitPlayerResurrected(String playerName) {
        emit(GameEvent.playerResurrected(playerName));
    }

    /**
     * Emit a stats update event.
     *
     * @param alive total alive players
     * @param werewolves alive werewolves
     * @param villagers alive villagers (including special roles)
     */
    public void emitStatsUpdate(int alive, int werewolves, int villagers) {
        emit(GameEvent.statsUpdate(alive, werewolves, villagers));
    }

    /**
     * Emit a system message event.
     *
     * @param message system message
     */
    public void emitSystemMessage(String message) {
        emit(GameEvent.systemMessage(message));
    }

    /**
     * Emit a game end event.
     *
     * @param winner winning side (villagers/werewolves)
     * @param reason win reason
     */
    public void emitGameEnd(String winner, String reason) {
        emit(GameEvent.gameEnd(winner, reason));
    }

    /**
     * Emit an error event.
     *
     * @param message error message
     */
    public void emitError(String message) {
        emit(GameEvent.error(message));
    }

    /**
     * Get the event stream as a Flux.
     *
     * @return Flux of game events
     */
    public Flux<GameEvent> getEventStream() {
        return sink.asFlux();
    }

    /** Complete the event stream. */
    public void complete() {
        sink.tryEmitComplete();
    }
}
