package io.github.jamesward.foldingtools;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Decorates a {@link ToolCallingManager} and records each real tool call
 * (name, arguments, result) into a {@link SessionObservationStore}, keyed by
 * the session id carried in {@link ToolCallingChatOptions#getToolContext()}
 * under {@link #SESSION_ID_KEY}. Calls whose tool name is in
 * {@code synthesizedToolNames} are skipped — we don't observe our own folds.
 */
public final class ObservingToolCallingManager implements ToolCallingManager {

    public static final String SESSION_ID_KEY = "foldingtools.sessionId";

    private final ToolCallingManager delegate;
    private final SessionObservationStore store;
    private final AtomicInteger turnCounter = new AtomicInteger();

    public ObservingToolCallingManager(ToolCallingManager delegate, SessionObservationStore store) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);

        String sessionId = extractSessionId(prompt.getOptions());
        if (sessionId == null) {
            return result;
        }
        Map<String, String> calls = indexToolCalls(chatResponse);
        int turn = turnCounter.incrementAndGet();
        Instant now = Instant.now();

        for (Message msg : result.conversationHistory()) {
            if (msg.getMessageType() != MessageType.TOOL) {
                continue;
            }
            if (!(msg instanceof ToolResponseMessage trm)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                String args = calls.get(tr.id());
                if (args == null) {
                    continue;
                }
                store.record(new FoldingObservation(
                        sessionId, tr.name(), args, tr.responseData(), now, turn));
            }
        }
        return result;
    }

    private static String extractSessionId(ChatOptions options) {
        if (options instanceof ToolCallingChatOptions tc) {
            Map<String, Object> ctx = tc.getToolContext();
            if (ctx != null) {
                Object v = ctx.get(SESSION_ID_KEY);
                if (v instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }

    private static Map<String, String> indexToolCalls(ChatResponse chatResponse) {
        return chatResponse.getResults().stream()
                .map(g -> g.getOutput())
                .filter(AssistantMessage::hasToolCalls)
                .flatMap(am -> am.getToolCalls().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        AssistantMessage.ToolCall::id,
                        AssistantMessage.ToolCall::arguments,
                        (a, b) -> a));
    }
}
