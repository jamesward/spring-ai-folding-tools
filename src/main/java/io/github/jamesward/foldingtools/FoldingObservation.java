package io.github.jamesward.foldingtools;

import java.time.Instant;

public record FoldingObservation(
        String sessionId,
        String toolName,
        String arguments,
        String result,
        Instant timestamp,
        int turnIndex) {
}
