package io.agentscope.core.memory.autocontext;

/**
 * Generic Pair class to hold two integer values (startIndex and endIndex).
 */
public record Pair<T, U>(T first, U second) {}
