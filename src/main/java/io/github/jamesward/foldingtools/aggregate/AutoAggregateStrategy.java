package io.github.jamesward.foldingtools.aggregate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.jamesward.foldingtools.FoldingContext;
import io.github.jamesward.foldingtools.FoldingStrategy;
import io.github.jamesward.foldingtools.FoldingToolsProperties;
import io.github.jamesward.foldingtools.SynthesizedToolCallback;

import org.springframework.ai.tool.ToolCallback;

/**
 * Strategy 2: synthesize aggregate tools that chain one tool's output into
 * another tool's input. Two binding sources, in precedence order:
 *
 * <ol>
 *   <li>Explicit {@link AggregationHint}s supplied at construction time.
 *       Work for any {@link ToolCallback} regardless of type.</li>
 *   <li>Auto-match between tools that implement
 *       {@link AggregatableToolCallback}. The source tool's output schema
 *       must contain a top-level property whose name and JSON type match
 *       a required scalar parameter on exactly one target tool. Ambiguity
 *       disqualifies the match.</li>
 * </ol>
 *
 * <p>v0 synthesizes only two-step chains (A → B). Actual synthesis of the
 * chained {@link ToolCallback} is delegated to {@link ChainSynthesizer}.</p>
 */
public final class AutoAggregateStrategy implements FoldingStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SCALAR_TYPES = Set.of("string", "integer", "number", "boolean");

    private final List<AggregationHint> hints;

    public AutoAggregateStrategy() {
        this(List.of());
    }

    public AutoAggregateStrategy(List<AggregationHint> hints) {
        this.hints = List.copyOf(Objects.requireNonNull(hints, "hints"));
    }

    @Override
    public String name() {
        return "auto-aggregate";
    }

    @Override
    public List<ToolCallback> fold(FoldingContext context) {
        FoldingToolsProperties.Aggregate cfg = context.properties().getAggregate();
        if (!cfg.isEnabled()) {
            return List.of();
        }

        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback t : context.sourceTools()) {
            if (SynthesizedToolCallback.isSynthesized(t)) continue;
            byName.put(t.getToolDefinition().name(), t);
        }

        List<ToolCallback> produced = new ArrayList<>();
        Set<String> newlySynthesizedNames = new HashSet<>();

        for (AggregationHint hint : hints) {
            ToolCallback source = byName.get(hint.sourceToolName());
            ToolCallback target = byName.get(hint.targetToolName());
            if (source == null || target == null) continue;

            ToolCallback aggregate = ChainSynthesizer.synthesize(
                    source, target, hint.sourceOutputField(), hint.targetInputParam(), name());
            if (aggregate != null && newlySynthesizedNames.add(aggregate.getToolDefinition().name())) {
                produced.add(aggregate);
            }
        }

        for (ToolCallback source : byName.values()) {
            if (!(source instanceof AggregatableToolCallback aggSource)) continue;
            JsonNode outputSchema;
            try {
                outputSchema = MAPPER.readTree(aggSource.outputSchema());
            } catch (Exception e) {
                continue;
            }
            Map<String, String> outputScalars = collectScalarFields(outputSchema);
            if (outputScalars.isEmpty()) continue;

            for (ToolCallback target : byName.values()) {
                if (target == source) continue;
                if (isExplicitlyHinted(source.getToolDefinition().name(), target.getToolDefinition().name())) {
                    continue;
                }
                AutoMatch match = findUniqueMatch(target, outputScalars);
                if (match == null) continue;

                ToolCallback aggregate = ChainSynthesizer.synthesize(
                        source, target, match.field(), match.param(), name());
                if (aggregate != null && newlySynthesizedNames.add(aggregate.getToolDefinition().name())) {
                    produced.add(aggregate);
                }
            }
        }

        return produced;
    }

    private boolean isExplicitlyHinted(String sourceName, String targetName) {
        for (AggregationHint h : hints) {
            if (h.sourceToolName().equals(sourceName) && h.targetToolName().equals(targetName)) {
                return true;
            }
        }
        return false;
    }

    private AutoMatch findUniqueMatch(ToolCallback target, Map<String, String> outputScalars) {
        JsonNode targetSchema;
        try {
            targetSchema = MAPPER.readTree(target.getToolDefinition().inputSchema());
        } catch (Exception e) {
            return null;
        }
        if (!targetSchema.isObject()) return null;
        JsonNode properties = targetSchema.path("properties");
        JsonNode required = targetSchema.path("required");
        if (!properties.isObject() || !required.isArray()) return null;

        List<String> requiredNames = new ArrayList<>();
        required.forEach(n -> requiredNames.add(n.asText()));
        if (requiredNames.size() != 1) return null;
        String requiredName = requiredNames.get(0);
        JsonNode requiredSchema = properties.path(requiredName);
        if (!requiredSchema.isObject()) return null;
        String requiredType = requiredSchema.path("type").asText();
        if (!SCALAR_TYPES.contains(requiredType)) return null;

        String matchingFieldType = outputScalars.get(requiredName);
        if (matchingFieldType == null || !matchingFieldType.equals(requiredType)) return null;

        return new AutoMatch(requiredName, requiredName);
    }

    private Map<String, String> collectScalarFields(JsonNode schema) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!schema.isObject()) return result;
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) return result;
        properties.fields().forEachRemaining(entry -> {
            JsonNode propSchema = entry.getValue();
            if (!propSchema.isObject()) return;
            String type = propSchema.path("type").asText();
            if (SCALAR_TYPES.contains(type)) {
                result.put(entry.getKey(), type);
            }
        });
        return result;
    }

    private record AutoMatch(String field, String param) {
    }
}
