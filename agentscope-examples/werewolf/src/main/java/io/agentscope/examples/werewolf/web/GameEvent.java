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

import java.time.Instant;
import java.util.Map;

/**
 * Represents a game event to be sent to the web frontend.
 */
public class GameEvent {

    private final GameEventType type;
    private final Map<String, Object> data;
    private final long timestamp;

    public GameEvent(GameEventType type, Map<String, Object> data) {
        this.type = type;
        this.data = data;
        this.timestamp = Instant.now().toEpochMilli();
    }

    public GameEventType getType() {
        return type;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    // Static factory methods for common events

    public static GameEvent gameInit(Object players) {
        return new GameEvent(GameEventType.GAME_INIT, Map.of("players", players));
    }

    public static GameEvent phaseChange(int round, String phase) {
        return new GameEvent(GameEventType.PHASE_CHANGE, Map.of("round", round, "phase", phase));
    }

    public static GameEvent playerSpeak(String playerName, String content, String context) {
        return new GameEvent(
                GameEventType.PLAYER_SPEAK,
                Map.of("player", playerName, "content", content, "context", context));
    }

    public static GameEvent playerVote(String voterName, String targetName, String reason) {
        return new GameEvent(
                GameEventType.PLAYER_VOTE,
                Map.of("voter", voterName, "target", targetName, "reason", reason));
    }

    public static GameEvent playerAction(
            String playerName, String role, String action, String target, String result) {
        return new GameEvent(
                GameEventType.PLAYER_ACTION,
                Map.of(
                        "player", playerName,
                        "role", role,
                        "action", action,
                        "target", target != null ? target : "",
                        "result", result != null ? result : ""));
    }

    public static GameEvent playerEliminated(String playerName, String role, String cause) {
        return new GameEvent(
                GameEventType.PLAYER_ELIMINATED,
                Map.of("player", playerName, "role", role, "cause", cause));
    }

    public static GameEvent playerResurrected(String playerName) {
        return new GameEvent(GameEventType.PLAYER_RESURRECTED, Map.of("player", playerName));
    }

    public static GameEvent statsUpdate(int alive, int werewolves, int villagers) {
        return new GameEvent(
                GameEventType.STATS_UPDATE,
                Map.of("alive", alive, "werewolves", werewolves, "villagers", villagers));
    }

    public static GameEvent systemMessage(String message) {
        return new GameEvent(GameEventType.SYSTEM_MESSAGE, Map.of("message", message));
    }

    public static GameEvent gameEnd(String winner, String reason) {
        return new GameEvent(GameEventType.GAME_END, Map.of("winner", winner, "reason", reason));
    }

    public static GameEvent error(String message) {
        return new GameEvent(GameEventType.ERROR, Map.of("message", message));
    }
}
