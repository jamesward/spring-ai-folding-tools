package io.github.jamesward.foldingtools;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySessionObservationStore implements SessionObservationStore {

    private final int bufferSizePerSession;
    private final Map<String, Deque<FoldingObservation>> buffers = new ConcurrentHashMap<>();

    public InMemorySessionObservationStore(int bufferSizePerSession) {
        this.bufferSizePerSession = bufferSizePerSession;
    }

    @Override
    public void record(FoldingObservation observation) {
        Deque<FoldingObservation> buffer = buffers.computeIfAbsent(
                observation.sessionId(), k -> new ArrayDeque<>());
        synchronized (buffer) {
            buffer.addLast(observation);
            while (buffer.size() > bufferSizePerSession) {
                buffer.removeFirst();
            }
        }
    }

    @Override
    public List<FoldingObservation> observations(String sessionId) {
        Deque<FoldingObservation> buffer = buffers.get(sessionId);
        if (buffer == null) {
            return List.of();
        }
        synchronized (buffer) {
            return List.copyOf(buffer);
        }
    }

    @Override
    public void clear(String sessionId) {
        buffers.remove(sessionId);
    }
}
