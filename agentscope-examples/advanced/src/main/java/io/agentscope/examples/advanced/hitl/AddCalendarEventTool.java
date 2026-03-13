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
package io.agentscope.examples.advanced.hitl;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * Mock tool that simulates adding an event to the user's calendar.
 *
 * <p>This tool is configured as a "dangerous" tool that requires user approval
 * before execution via the {@link ToolConfirmationHook}. When the agent decides
 * to call this tool, the hook intercepts it, stops the agent, and the frontend
 * renders approve/reject buttons for the user.
 */
public class AddCalendarEventTool {

    public static final String TOOL_NAME = "add_calendar_event";

    @Tool(
            name = TOOL_NAME,
            description =
                    "Add a workout event to the user's calendar. Call this tool for EACH day's"
                        + " workout separately. For example, if the plan has workouts on Monday,"
                        + " Tuesday, and Wednesday, call this tool 3 times with each day's"
                        + " details.")
    public String addCalendarEvent(
            @ToolParam(name = "title", description = "Event title, e.g. 'Chest + Triceps Workout'")
                    String title,
            @ToolParam(
                            name = "date",
                            description = "Event date in YYYY-MM-DD format, e.g. '2026-03-02'")
                    String date,
            @ToolParam(
                            name = "time",
                            description =
                                    "Start time in HH:mm format, e.g. '08:00'. Defaults to"
                                            + " '09:00'.",
                            required = false)
                    String time,
            @ToolParam(
                            name = "duration_minutes",
                            description = "Duration in minutes, e.g. 60",
                            required = false)
                    Integer durationMinutes,
            @ToolParam(
                            name = "description",
                            description = "Detailed workout content for this session",
                            required = false)
                    String description) {
        String startTime = (time != null && !time.isEmpty()) ? time : "09:00";
        int duration = (durationMinutes != null && durationMinutes > 0) ? durationMinutes : 60;

        return String.format(
                "Successfully added calendar event: '%s' on %s at %s (%d min)",
                title, date, startTime, duration);
    }
}
