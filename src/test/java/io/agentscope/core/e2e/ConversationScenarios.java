package io.agentscope.core.e2e;

/**
 * Documentation of conversation scenarios for E2E testing.
 *
 * <p>This class documents common conversation patterns and scenarios that can be used for testing
 * ReAct agents. Each scenario describes:
 *
 * <ul>
 *   <li>The user input
 *   <li>Expected agent behavior
 *   <li>Expected outcomes or responses
 * </ul>
 *
 * <p>These scenarios serve as reference for writing E2E tests and can help ensure comprehensive
 * coverage of agent capabilities.
 */
public class ConversationScenarios {

    private ConversationScenarios() {
        // Documentation class
    }

    /**
     * Scenario: Simple Math Calculation
     *
     * <p><b>Input:</b> "What is 15 + 27?"
     *
     * <p><b>Expected Behavior:</b> Agent should use the 'add' tool to calculate the sum
     *
     * <p><b>Expected Outcome:</b> Response contains "42"
     */
    public static final String SIMPLE_MATH = "Simple Math Calculation";

    /**
     * Scenario: Multi-Step Calculation
     *
     * <p><b>Input:</b> "Calculate (5 + 3) * 2"
     *
     * <p><b>Expected Behavior:</b> Agent should:
     *
     * <ol>
     *   <li>Use 'add' tool for 5 + 3
     *   <li>Use 'multiply' tool for result * 2
     * </ol>
     *
     * <p><b>Expected Outcome:</b> Response contains "16"
     */
    public static final String MULTI_STEP_CALCULATION = "Multi-Step Calculation";

    /**
     * Scenario: String Manipulation
     *
     * <p><b>Input:</b> "Convert 'hello world' to uppercase"
     *
     * <p><b>Expected Behavior:</b> Agent should use 'toUpperCase' tool
     *
     * <p><b>Expected Outcome:</b> Response contains "HELLO WORLD"
     */
    public static final String STRING_MANIPULATION = "String Manipulation";

    /**
     * Scenario: Multi-Round Conversation
     *
     * <p><b>Round 1:</b> "What is 10 + 5?"
     *
     * <p><b>Round 2:</b> "Now multiply that result by 2"
     *
     * <p><b>Round 3:</b> "What was the first number I asked about?"
     *
     * <p><b>Expected Behavior:</b> Agent should:
     *
     * <ol>
     *   <li>Calculate 10 + 5 = 15
     *   <li>Remember previous result and calculate 15 * 2 = 30
     *   <li>Recall from memory that the first number was 10
     * </ol>
     *
     * <p><b>Expected Outcomes:</b> Responses contain "15", "30", and "10" respectively
     */
    public static final String MULTI_ROUND_CONVERSATION = "Multi-Round Conversation";

    /**
     * Scenario: Complex Tool Chain
     *
     * <p><b>Input:</b> "First add 10 and 20, then multiply the result by 3, then calculate the
     * factorial of 5"
     *
     * <p><b>Expected Behavior:</b> Agent should:
     *
     * <ol>
     *   <li>Use 'add' tool: 10 + 20 = 30
     *   <li>Use 'multiply' tool: 30 * 3 = 90
     *   <li>Use 'factorial' tool: 5! = 120
     * </ol>
     *
     * <p><b>Expected Outcomes:</b> Response mentions 30, 90, and 120
     */
    public static final String COMPLEX_TOOL_CHAIN = "Complex Tool Chain";

    /**
     * Scenario: Parallel Tool Calls
     *
     * <p><b>Input:</b> "Calculate these independently: 10 + 5, 8 * 3, and the factorial of 4"
     *
     * <p><b>Expected Behavior:</b> Agent should independently calculate:
     *
     * <ol>
     *   <li>10 + 5 = 15
     *   <li>8 * 3 = 24
     *   <li>4! = 24
     * </ol>
     *
     * <p><b>Expected Outcomes:</b> Response contains "15", "24", and "24"
     */
    public static final String PARALLEL_TOOL_CALLS = "Parallel Tool Calls";

    /**
     * Scenario: Tool Call with Error
     *
     * <p><b>Input:</b> "Calculate the factorial of -5"
     *
     * <p><b>Expected Behavior:</b> Agent should attempt to use 'factorial' tool, receive error,
     * and handle it gracefully
     *
     * <p><b>Expected Outcome:</b> Response explains that factorial of negative numbers is
     * undefined/invalid
     */
    public static final String TOOL_ERROR_HANDLING = "Tool Call with Error";

    /**
     * Scenario: Context-Aware Conversation
     *
     * <p><b>Round 1:</b> "My name is Alice and I like math"
     *
     * <p><b>Round 2:</b> "What is my name?"
     *
     * <p><b>Round 3:</b> "What do I like?"
     *
     * <p><b>Expected Behavior:</b> Agent should remember information from previous messages
     *
     * <p><b>Expected Outcomes:</b> Responses mention "Alice" and "math"
     */
    public static final String CONTEXT_AWARE = "Context-Aware Conversation";

    /**
     * Scenario: Mixed Conversation (Tool and Non-Tool)
     *
     * <p><b>Round 1:</b> "Hello! How are you?" (no tools needed)
     *
     * <p><b>Round 2:</b> "Can you calculate 25 + 17 for me?" (requires tools)
     *
     * <p><b>Round 3:</b> "Thanks! That's helpful." (no tools needed)
     *
     * <p><b>Expected Behavior:</b> Agent should:
     *
     * <ol>
     *   <li>Respond conversationally to greeting
     *   <li>Use 'add' tool for calculation
     *   <li>Acknowledge thanks conversationally
     * </ol>
     *
     * <p><b>Expected Outcomes:</b> All responses are appropriate to their input type
     */
    public static final String MIXED_CONVERSATION = "Mixed Conversation";
}
