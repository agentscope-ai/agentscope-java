/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.training.runner;

import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Run Execution Context
 *
 * <p>Encapsulates the complete execution information for <b>a single Run</b>.
 *
 * <h2>Core Concepts:</h2>
 * <ul>
 *   <li><b>Task</b>: Represents a user's original request (business-level unique identifier)</li>
 *   <li><b>Run</b>: A specific execution of the same Task (experiment/training level)</li>
 *   <li><b>RunExecutionContext</b>: Encapsulates the execution context for one Run</li>
 * </ul>
 *
 * <h2>Relationship Example:</h2>
 * <pre>
 * Task "task-001" (User asks: "What's the weather today?")
 *   ├─ Run "0" → RunExecutionContext (msgIds=[msg-1, msg-2], msgs=[...], reward=0.8)
 *   ├─ Run "1" → RunExecutionContext (msgIds=[msg-3, msg-4], msgs=[...], reward=0.9)
 *   └─ Run "2" → RunExecutionContext (msgIds=[msg-5, msg-6], msgs=[...], reward=0.85)
 * </pre>
 *
 * <h2>Data Contents:</h2>
 * <ul>
 *   <li><b>Task ID</b>: Unique identifier for user request</li>
 *   <li><b>Run ID</b>: The Nth execution of this Task (incrementing from "0")</li>
 *   <li><b>msg_ids</b>: All message IDs from LLM interactions in this Run (returned from Trinity API)</li>
 *   <li><b>msgs</b>: All messages from Agent memory in this Run (raw data for reward calculation)</li>
 *   <li><b>Timing info</b>: Start time and duration of this Run</li>
 * </ul>
 *
 * <h2>Lifecycle:</h2>
 * <pre>
 * 1. TrainingRouter creates RunExecutionContext (corresponds to one Run)
 * 2. Passed to TrinityModelAdapter (automatically collects msg_ids)
 * 3. Shadow Agent execution completes, all data collected
 * 4. TrainingRouter calculates reward
 * 5. TrainingRouter submits Feedback using (taskId, runId, msg_ids, reward)
 * 6. Registered to TaskExecutionRegistry (optional, for later queries)
 * </pre>
 *
 * <h2>Thread Safety:</h2>
 * <ul>
 *   <li>Uses {@link CopyOnWriteArrayList} to store msg_ids</li>
 *   <li>Supports concurrent additions (although currently executed synchronously)</li>
 * </ul>
 *
 * @see TaskIdGenerator Automatically generates Task ID
 * @see RunRegistry Manages multiple Runs for the same Task
 * @see TaskExecutionRegistry Stores execution contexts for all Runs
 */
public class RunExecutionContext {

    /** Task ID - Unique identifier for user request (can be specified by user or auto-generated) */
    private final String taskId;

    /** Run ID - The Nth execution of the same Task (incrementing from "0") */
    private final String runId;

    /** Message IDs - All message IDs from LLM interactions in this Run */
    private final List<String> msgIds;

    /** Messages - All messages from agent memory in this Run (for reward calculation) */
    private final List<Msg> msgs;

    /** Start time of this Run (millisecond timestamp) */
    private final long startTime;

    /**
     * Private constructor
     *
     * @param taskId Task ID
     * @param runId Run ID
     */
    private RunExecutionContext(String taskId, String runId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("Task ID cannot be null or empty");
        }
        if (runId == null || runId.isEmpty()) {
            throw new IllegalArgumentException("Run ID cannot be null or empty");
        }

        this.taskId = taskId;
        this.runId = runId;
        this.msgIds = new CopyOnWriteArrayList<>();
        this.msgs = new CopyOnWriteArrayList<>();
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Create new Run execution context
     *
     * <p><b>Usage scenario:</b> Created by TrainingRouter after intercepting Agent call.
     *
     * @param taskId Task ID
     * @param runId Run ID
     * @return Run execution context instance
     * @throws IllegalArgumentException if taskId or runId is null/empty
     */
    public static RunExecutionContext create(String taskId, String runId) {
        return new RunExecutionContext(taskId, runId);
    }

    /**
     * Add Message ID
     *
     * <p>Message ID is extracted from Trinity Chat API response ({@code response.getId()}).
     *
     * <p><b>Thread-safe:</b> This method can be called concurrently.
     *
     * @param msgId Message ID (ignored if null or empty)
     */
    public void addMsgId(String msgId) {
        if (msgId != null && !msgId.isEmpty()) {
            msgIds.add(msgId);
        }
    }

    /**
     * Get all Message IDs
     *
     * <p><b>Return value:</b> Returns a new List copy to prevent external modification.
     *
     * @return Message IDs list (unmodifiable)
     */
    public List<String> getMsgIds() {
        return new ArrayList<>(msgIds);
    }

    /**
     * Get Message IDs count
     *
     * @return Number of msg_ids
     */
    public int getMsgIdCount() {
        return msgIds.size();
    }

    /**
     * Get Task ID
     *
     * @return Task ID
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Get Run ID
     *
     * @return Run ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Get Run start time
     *
     * @return Start time (millisecond timestamp)
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * Get Run execution duration
     *
     * @return Execution duration (milliseconds)
     */
    public long getDuration() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Check if msg_ids have been collected
     *
     * @return true if at least one msg_id exists
     */
    public boolean hasMsgIds() {
        return !msgIds.isEmpty();
    }

    /**
     * Add Message
     *
     * <p><b>Thread-safe:</b> This method can be called concurrently.
     *
     * @param msg Message (ignored if null)
     */
    public void addMsg(Msg msg) {
        if (msg != null) {
            msgs.add(msg);
        }
    }

    /**
     * Batch set Messages (usually obtained from agent memory)
     *
     * <p>Clears existing messages and adds new message list.
     *
     * <p><b>Thread-safe:</b> This method can be called concurrently.
     *
     * @param messages Message list (clears existing messages if null or empty)
     */
    public void setMessages(List<Msg> messages) {
        msgs.clear();
        if (messages != null && !messages.isEmpty()) {
            msgs.addAll(messages);
        }
    }

    /**
     * Get all Messages
     *
     * <p><b>Return value:</b> Returns a new List copy to prevent external modification.
     *
     * @return Messages list (unmodifiable)
     */
    public List<Msg> getMessages() {
        return new ArrayList<>(msgs);
    }

    /**
     * Get Messages count
     *
     * @return Number of msgs
     */
    public int getMessageCount() {
        return msgs.size();
    }

    /**
     * Check if messages have been collected
     *
     * @return true if at least one message exists
     */
    public boolean hasMessages() {
        return !msgs.isEmpty();
    }

    @Override
    public String toString() {
        return String.format(
                "RunExecution{task=%s, run=%s, msgIds=%d, msgs=%d, duration=%dms}",
                taskId, runId, msgIds.size(), msgs.size(), getDuration());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RunExecutionContext that = (RunExecutionContext) o;
        return taskId.equals(that.taskId) && runId.equals(that.runId);
    }

    @Override
    public int hashCode() {
        int result = taskId.hashCode();
        result = 31 * result + runId.hashCode();
        return result;
    }
}
