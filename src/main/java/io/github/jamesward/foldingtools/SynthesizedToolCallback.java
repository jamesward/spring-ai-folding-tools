package io.github.jamesward.foldingtools;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

public final class SynthesizedToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final Function<String, String> dispatch;
    private final List<String> underlyingToolNames;
    private final String strategyName;

    public SynthesizedToolCallback(
            ToolDefinition definition,
            Function<String, String> dispatch,
            List<String> underlyingToolNames,
            String strategyName) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.underlyingToolNames = List.copyOf(underlyingToolNames);
        this.strategyName = Objects.requireNonNull(strategyName, "strategyName");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return dispatch.apply(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    public List<String> underlyingToolNames() {
        return underlyingToolNames;
    }

    public String strategyName() {
        return strategyName;
    }

    public static boolean isSynthesized(ToolCallback callback) {
        return callback instanceof SynthesizedToolCallback;
    }
}
