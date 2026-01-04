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

import io.agentscope.core.agent.user.UserInputBase;
import io.agentscope.core.agent.user.UserInputData;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Web-based user input implementation for the Werewolf game.
 *
 * <p>This class bridges the gap between the UserAgent and the web frontend, allowing human players
 * to provide input through the browser interface. It works in conjunction with GameEventEmitter
 * to:
 *
 * <ol>
 *   <li>Emit WAIT_USER_INPUT events to prompt the human player
 *   <li>Wait for input submitted via the REST API
 *   <li>Convert the input to UserInputData for the UserAgent
 * </ol>
 */
public class WebUserInput implements UserInputBase {

    private final GameEventEmitter emitter;
    private final ConcurrentHashMap<String, Sinks.One<String>> pendingInputs;

    // Input type constants
    public static final String INPUT_SPEAK = "SPEAK";
    public static final String INPUT_VOTE = "VOTE";
    public static final String INPUT_WITCH_HEAL = "WITCH_HEAL";
    public static final String INPUT_WITCH_POISON = "WITCH_POISON";
    public static final String INPUT_SEER_CHECK = "SEER_CHECK";
    public static final String INPUT_HUNTER_SHOOT = "HUNTER_SHOOT";

    /**
     * Create a new WebUserInput.
     *
     * @param emitter The game event emitter for sending events to the frontend
     */
    public WebUserInput(GameEventEmitter emitter) {
        this.emitter = emitter;
        this.pendingInputs = new ConcurrentHashMap<>();
    }

    @Override
    public Mono<UserInputData> handleInput(
            String agentId, String agentName, List<Msg> contextMessages, Class<?> structuredModel) {
        // This is the generic interface method. For more control, use the specific methods below.
        return waitForInput(INPUT_SPEAK, "请输入你的发言", null)
                .map(
                        input ->
                                new UserInputData(
                                        List.of(TextBlock.builder().text(input).build()), null));
    }

    /**
     * Wait for user input with a specific type and prompt. This method blocks until the user
     * provides input (no timeout for single-player game).
     *
     * @param inputType The type of input required
     * @param prompt The prompt message to display
     * @param options Available options (can be null for free text)
     * @return Mono containing the user's input
     */
    public Mono<String> waitForInput(String inputType, String prompt, List<String> options) {
        // Cancel any previous pending input of the same type to avoid confusion
        cancelPendingInputsOfType(inputType);

        String inputId = inputType + "_" + System.currentTimeMillis();
        Sinks.One<String> inputSink = Sinks.one();
        pendingInputs.put(inputId, inputSink);

        // Emit event to prompt user (0 means no timeout)
        emitter.emitWaitUserInput(inputType, prompt, options, 0);

        return inputSink.asMono().doOnSuccess(v -> pendingInputs.remove(inputId));
    }

    /**
     * Cancel all pending inputs of a specific type.
     *
     * @param inputType The type of inputs to cancel
     */
    private void cancelPendingInputsOfType(String inputType) {
        String prefix = inputType + "_";
        pendingInputs
                .entrySet()
                .removeIf(
                        entry -> {
                            if (entry.getKey().startsWith(prefix)) {
                                entry.getValue().tryEmitValue("");
                                return true;
                            }
                            return false;
                        });
    }

    /**
     * Submit user input from the REST API.
     *
     * @param inputType The type of input being submitted
     * @param content The user's input content
     * @return true if input was accepted, false if no pending input of that type
     */
    public boolean submitInput(String inputType, String content) {
        // Find the pending input for this type
        String matchingKey = null;
        for (String key : pendingInputs.keySet()) {
            if (key.startsWith(inputType + "_")) {
                matchingKey = key;
                break;
            }
        }

        if (matchingKey != null) {
            Sinks.One<String> sink = pendingInputs.remove(matchingKey);
            if (sink != null) {
                // Emit confirmation BEFORE unblocking the game thread
                // This ensures the USER_INPUT_RECEIVED event is sent before
                // any new WAIT_USER_INPUT events from the next action
                emitter.emitUserInputReceived(inputType, content);
                sink.tryEmitValue(content);
                return true;
            }
        }
        return false;
    }

    /**
     * Check if there is a pending input request.
     *
     * @return true if there is a pending input
     */
    public boolean hasPendingInput() {
        return !pendingInputs.isEmpty();
    }

    /**
     * Get the current pending input type, if any.
     *
     * @return The input type or null if no pending input
     */
    public String getPendingInputType() {
        if (pendingInputs.isEmpty()) {
            return null;
        }
        String key = pendingInputs.keySet().iterator().next();
        return key.split("_")[0];
    }

    /**
     * Cancel all pending inputs.
     */
    public void cancelAllPending() {
        pendingInputs.values().forEach(sink -> sink.tryEmitValue(""));
        pendingInputs.clear();
    }
}
