package io.github.jamesward.foldingtools;

import java.util.List;

public interface SessionObservationStore {

    void record(FoldingObservation observation);

    List<FoldingObservation> observations(String sessionId);

    void clear(String sessionId);
}
