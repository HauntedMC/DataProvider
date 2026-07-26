package nl.hauntedmc.dataprovider.database.document.model;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Defensive snapshots for the vendor-neutral document value tree. */
final class DocumentValueSnapshot {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 10_000;

    private DocumentValueSnapshot() {
    }

    static Map<String, Object> map(Map<String, Object> source) {
        SnapshotState state = new SnapshotState();
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) copy(source, state, 0);
        return snapshot;
    }

    static Object value(Object source) {
        return copy(source, new SnapshotState(), 0);
    }

    private static Object copy(Object source, SnapshotState state, int depth) {
        if (source == null || isKnownImmutable(source)) {
            return source;
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Document values cannot exceed " + MAX_DEPTH + " nested levels.");
        }
        if (++state.nodes > MAX_NODES) {
            throw new IllegalArgumentException("Document values cannot exceed " + MAX_NODES + " nested values.");
        }
        if (state.active.put(source, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Document values cannot contain reference cycles.");
        }
        try {
            if (source instanceof Map<?, ?> sourceMap) {
                Map<String, Object> target = new LinkedHashMap<>();
                sourceMap.forEach((key, value) -> {
                    if (!(key instanceof String stringKey)) {
                        throw new IllegalArgumentException("Document map keys must be strings.");
                    }
                    target.put(stringKey, copy(value, state, depth + 1));
                });
                return Collections.unmodifiableMap(target);
            }
            if (source instanceof Iterable<?> iterable) {
                List<Object> target = new ArrayList<>();
                iterable.forEach(value -> target.add(copy(value, state, depth + 1)));
                return Collections.unmodifiableList(target);
            }
            Class<?> sourceType = source.getClass();
            if (sourceType.isArray()) {
                int length = Array.getLength(source);
                Object target = Array.newInstance(sourceType.getComponentType(), length);
                for (int index = 0; index < length; index++) {
                    Array.set(target, index, copy(Array.get(source, index), state, depth + 1));
                }
                return target;
            }
            if (source instanceof java.util.Date date) {
                return new java.util.Date(date.getTime());
            }
            return source;
        } finally {
            state.active.remove(source);
        }
    }

    private static boolean isKnownImmutable(Object value) {
        return value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof java.time.temporal.Temporal
                || value instanceof java.util.UUID;
    }

    private static final class SnapshotState {
        private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        private int nodes;
    }
}
