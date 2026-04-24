package io.github.jamesward.foldingtools;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;

public interface SessionIdResolver {

    Optional<String> resolve(ChatClientRequest request);

    static SessionIdResolver chatMemoryConversationId() {
        return request -> {
            Object id = request.context().get(ChatMemory.CONVERSATION_ID);
            return id instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
        };
    }
}
