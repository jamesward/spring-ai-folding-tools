package io.github.jamesward.foldingtools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.Ordered;

/**
 * Outbound tool-list rewriter. Runs once per turn, asks each enabled
 * {@link FoldingStrategy} to synthesize additional {@link ToolCallback}s,
 * and merges them into the request's {@link ToolCallingChatOptions}.
 *
 * <p>Must be ordered <em>before</em> {@code ToolCallAdvisor} and before any
 * dynamic-tool-search advisor, so synthesized tools are visible to both
 * the model and any downstream selection logic.</p>
 */
public final class FoldingToolsAdvisor implements CallAdvisor, StreamAdvisor {

    public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;

    private final List<FoldingStrategy> strategies;
    private final SessionIdResolver sessionIdResolver;
    private final SessionObservationStore observationStore;
    private final FoldingToolsProperties properties;
    private final int order;

    public FoldingToolsAdvisor(
            List<FoldingStrategy> strategies,
            SessionIdResolver sessionIdResolver,
            SessionObservationStore observationStore,
            FoldingToolsProperties properties) {
        this(strategies, sessionIdResolver, observationStore, properties, DEFAULT_ORDER);
    }

    public FoldingToolsAdvisor(
            List<FoldingStrategy> strategies,
            SessionIdResolver sessionIdResolver,
            SessionObservationStore observationStore,
            FoldingToolsProperties properties,
            int order) {
        this.strategies = List.copyOf(Objects.requireNonNull(strategies, "strategies"));
        this.sessionIdResolver = Objects.requireNonNull(sessionIdResolver, "sessionIdResolver");
        this.observationStore = Objects.requireNonNull(observationStore, "observationStore");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.order = order;
    }

    @Override
    public String getName() {
        return "FoldingToolsAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(maybeFold(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(maybeFold(request));
    }

    private ChatClientRequest maybeFold(ChatClientRequest request) {
        if (!properties.isEnabled()) {
            return request;
        }
        Prompt prompt = request.prompt();
        ChatOptions options = prompt.getOptions();
        if (!(options instanceof ToolCallingChatOptions tcOptions)) {
            return request;
        }

        Optional<String> sessionId = sessionIdResolver.resolve(request);
        List<ToolCallback> sourceTools = new ArrayList<>(
                Objects.requireNonNullElse(tcOptions.getToolCallbacks(), List.<ToolCallback>of()));
        List<FoldingObservation> obs = sessionId.map(observationStore::observations).orElse(List.of());

        FoldingContext ctx = new FoldingContext(
                List.copyOf(sourceTools), request, sessionId, obs, properties);

        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback existing : sourceTools) {
            byName.put(existing.getToolDefinition().name(), existing);
        }
        Set<String> originalNames = new HashSet<>(byName.keySet());

        int budget = properties.getMaxSynthesizedTools();
        for (FoldingStrategy strategy : strategies) {
            if (budget <= 0) break;
            List<ToolCallback> produced;
            try {
                produced = strategy.fold(ctx);
            } catch (RuntimeException ex) {
                continue;
            }
            for (ToolCallback cb : produced) {
                if (budget <= 0) break;
                String name = cb.getToolDefinition().name();
                if (originalNames.contains(name) || byName.containsKey(name)) {
                    continue;
                }
                if (properties.isDryRun()) {
                    continue;
                }
                byName.put(name, cb);
                budget--;
            }
        }

        if (byName.size() == sourceTools.size()) {
            return writeSessionId(request, tcOptions, sessionId);
        }
        tcOptions.setToolCallbacks(new ArrayList<>(byName.values()));
        return writeSessionId(request, tcOptions, sessionId);
    }

    private ChatClientRequest writeSessionId(
            ChatClientRequest request, ToolCallingChatOptions tcOptions, Optional<String> sessionId) {
        if (sessionId.isEmpty()) {
            return request;
        }
        Map<String, Object> toolContext = tcOptions.getToolContext();
        Map<String, Object> merged = toolContext == null ? new HashMap<>() : new HashMap<>(toolContext);
        merged.put(ObservingToolCallingManager.SESSION_ID_KEY, sessionId.get());
        tcOptions.setToolContext(merged);
        return request;
    }
}
