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

/** Event types for the Werewolf game web interface. */
public enum GameEventType {
    /** Game initialization with player info. */
    GAME_INIT,

    /** Phase change (night/day). */
    PHASE_CHANGE,

    /** Player speaks during discussion. */
    PLAYER_SPEAK,

    /** Player casts a vote. */
    PLAYER_VOTE,

    /** Special role action (witch/seer/hunter). */
    PLAYER_ACTION,

    /** Player is eliminated. */
    PLAYER_ELIMINATED,

    /** Player is resurrected (by witch). */
    PLAYER_RESURRECTED,

    /** Game statistics update. */
    STATS_UPDATE,

    /** System message/announcement. */
    SYSTEM_MESSAGE,

    /** Game ends with winner. */
    GAME_END,

    /** Error occurred. */
    ERROR,

    /** Human player's role assignment (tells the player their role). */
    PLAYER_ROLE_ASSIGNMENT,

    /** Waiting for user input (prompts the human player to act). */
    WAIT_USER_INPUT,

    /** User input received confirmation. */
    USER_INPUT_RECEIVED,

    /** Sheriff election: player registers for sheriff campaign. */
    SHERIFF_REGISTRATION,

    /** Sheriff election: candidates announcement with raise hand icon. */
    SHERIFF_CANDIDATES_ANNOUNCED,

    /** Sheriff election: candidate campaign speech. */
    SHERIFF_CAMPAIGN,

    /** Sheriff election: voting for sheriff. */
    SHERIFF_VOTE,

    /** Sheriff elected announcement. */
    SHERIFF_ELECTED,

    /** Sheriff transfers badge. */
    SHERIFF_TRANSFER,

    /** Night action result popup for werewolf kill. */
    NIGHT_ACTION_WEREWOLF_KILL,

    /** Night action result popup for witch heal. */
    NIGHT_ACTION_WITCH_HEAL,

    /** Night action result popup for witch poison. */
    NIGHT_ACTION_WITCH_POISON,

    /** Night action result popup for seer check. */
    NIGHT_ACTION_SEER_CHECK,

    /** Audio chunk for TTS (text-to-speech). */
    AUDIO_CHUNK
}
