package io.agentscope.core.memory.autocontext;

/**
 * Simple Pair class to hold two integer values (startIndex and endIndex).
 */
class Pair<T, U> {
    private final T first;
    private final U second;

    public Pair(T first, U second) {
        this.first = first;
        this.second = second;
    }

    public T getFirst() {
        return first;
    }

    public U getSecond() {
        return second;
    }
}
