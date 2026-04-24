package io.github.jamesward.foldingtools;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.tool.ToolCallback;

public record FoldingContext(
        List<ToolCallback> sourceTools,
        ChatClientRequest request,
        Optional<String> sessionId,
        List<FoldingObservation> observations,
        FoldingToolsProperties properties) {
}
