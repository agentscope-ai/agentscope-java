package io.agentscope.core.session;

import java.util.List;

/** Narrow storage capability. CAS must be atomic across all writers sharing this namespace.
 * Missing is null; read/write failures throw. Implementations must durably store bytes before ACK.
 * The runtime journal storage must not be writable through model filesystem tools.
 */
public interface AtomicSessionStorage {
    record Value(long version, byte[] bytes) {
        public Value {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    Value read(String path);

    /** Optional read-only discovery of object paths under a prefix. Recovery never uses listing. */
    default List<String> listPaths(String prefix) {
        throw new UnsupportedOperationException("This session storage does not support discovery");
    }

    /** expectedVersion=0 means create if absent. Unsupported backends must throw. */
    boolean compareAndSet(String path, long expectedVersion, byte[] bytes);
}
