package io.agentscope.core.memory.autocontext;

/**
 * Generic pair record to hold two values of potentially different types.
 *
 * <p>This record is used throughout AutoContextMemory to represent pairs of related values,
 * such as start and end indices for message ranges, or other paired data structures.
 *
 * <p>Common usage examples:
 * <ul>
 *   <li>Message index ranges: {@code Pair<Integer, Integer>} for (startIndex, endIndex)</li>
 *   <li>User-assistant pairs: {@code Pair<Integer, Integer>} for (userIndex, assistantIndex)</li>
 * </ul>
 *
 * @param <T> the type of the first value
 * @param <U> the type of the second value
 */
public record Pair<T, U>(T first, U second) {}
