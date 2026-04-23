package io.github.jamesward.foldingtools.aggregate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.jamesward.foldingtools.FoldingContext;
import io.github.jamesward.foldingtools.FoldingStrategy;
import io.github.jamesward.foldingtools.FoldingToolsProperties;
import io.github.jamesward.foldingtools.SynthesizedToolCallback;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

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
 * <p>v0 synthesizes only two-step chains (A → B). The aggregate tool's
 * input schema equals A's input schema; its output is a JSON object with
 * keys {@code source} and {@code target} containing each step's result,
 * or an {@code error} key if a step fails.</p>
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
        Set<String> newlySynthesizedNames = new java.util.HashSet<>();

        for (AggregationHint hint : hints) {
            ToolCallback source = byName.get(hint.sourceToolName());
            ToolCallback target = byName.get(hint.targetToolName());
            if (source == null || target == null) continue;

            ToolCallback aggregate = synthesize(source, target, hint.sourceOutputField(), hint.targetInputParam());
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
                AutoMatch match = findUniqueMatch(source, target, outputScalars);
                if (match == null) continue;
                if (isExplicitlyHinted(source.getToolDefinition().name(), target.getToolDefinition().name())) {
                    continue;
                }
                ToolCallback aggregate = synthesize(source, target, match.field, match.param);
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

    private AutoMatch findUniqueMatch(ToolCallback source, ToolCallback target, Map<String, String> outputScalars) {
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

        String matchingField = outputScalars.get(requiredName);
        if (matchingField == null || !matchingField.equals(requiredType)) return null;

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

    private ToolCallback synthesize(
            ToolCallback source, ToolCallback target, String sourceOutputField, String targetInputParam) {

        ToolDefinition sourceDef = source.getToolDefinition();
        ToolDefinition targetDef = target.getToolDefinition();

        JsonNode targetSchema;
        try {
            targetSchema = MAPPER.readTree(targetDef.inputSchema());
        } catch (Exception e) {
            return null;
        }
        if (hasUnsatisfiedRequiredParams(targetSchema, targetInputParam)) {
            return null;
        }

        String newName = sourceDef.name() + "Then" + capitalize(targetDef.name());
        String newDescription = "Chains %s into %s: calls %s first, extracts '%s' from its result, passes it as '%s' to %s, and returns both results. Source: %s Target: %s"
                .formatted(
                        sourceDef.name(), targetDef.name(),
                        sourceDef.name(), sourceOutputField,
                        targetInputParam, targetDef.name(),
                        nullToEmpty(sourceDef.description()),
                        nullToEmpty(targetDef.description()));

        ToolDefinition newDef = ToolDefinition.builder()
                .name(newName)
                .description(newDescription)
                .inputSchema(sourceDef.inputSchema())
                .build();

        Function<String, String> dispatch = new ChainedDispatch(
                source, target, sourceOutputField, targetInputParam);

        return new SynthesizedToolCallback(
                newDef, dispatch, List.of(sourceDef.name(), targetDef.name()), name());
    }

    private boolean hasUnsatisfiedRequiredParams(JsonNode targetSchema, String boundParam) {
        JsonNode required = targetSchema.path("required");
        if (!required.isArray()) return false;
        for (JsonNode n : required) {
            if (!boundParam.equals(n.asText())) return true;
        }
        return false;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private record AutoMatch(String field, String param) {
    }

    private static final class ChainedDispatch implements Function<String, String> {
        private final ToolCallback source;
        private final ToolCallback target;
        private final String sourceOutputField;
        private final String targetInputParam;

        ChainedDispatch(ToolCallback source, ToolCallback target,
                        String sourceOutputField, String targetInputParam) {
            this.source = source;
            this.target = target;
            this.sourceOutputField = sourceOutputField;
            this.targetInputParam = targetInputParam;
        }

        @Override
        public String apply(String toolInput) {
            ObjectNode result = MAPPER.createObjectNode();

            String sourceRaw;
            try {
                sourceRaw = source.call(toolInput);
            } catch (RuntimeException e) {
                return errorJson(result, "source",
                        source.getToolDefinition().name() + " failed: " + messageOf(e));
            }
            JsonNode sourceParsed = tryParseJson(sourceRaw);
            if (sourceParsed != null) {
                result.set("source", sourceParsed);
            } else {
                result.put("source", sourceRaw);
            }

            if (sourceParsed == null || !sourceParsed.isObject()) {
                result.put("error",
                        "cannot extract '%s' from %s output: not a JSON object"
                                .formatted(sourceOutputField, source.getToolDefinition().name()));
                return result.toString();
            }
            JsonNode bound = sourceParsed.get(sourceOutputField);
            if (bound == null || bound.isNull()) {
                result.put("error",
                        "field '%s' missing in %s output"
                                .formatted(sourceOutputField, source.getToolDefinition().name()));
                return result.toString();
            }

            ObjectNode targetInput = MAPPER.createObjectNode();
            targetInput.set(targetInputParam, bound);

            String targetRaw;
            try {
                targetRaw = target.call(MAPPER.writeValueAsString(targetInput));
            } catch (RuntimeException e) {
                result.put("error",
                        target.getToolDefinition().name() + " failed: " + messageOf(e));
                return result.toString();
            } catch (Exception e) {
                result.put("error",
                        target.getToolDefinition().name() + " failed: " + e.getClass().getSimpleName());
                return result.toString();
            }
            JsonNode targetParsed = tryParseJson(targetRaw);
            if (targetParsed != null) {
                result.set("target", targetParsed);
            } else {
                result.put("target", targetRaw);
            }
            return result.toString();
        }

        private static String errorJson(ObjectNode result, String field, String message) {
            result.put(field + "_error", message);
            result.put("error", message);
            return result.toString();
        }

        private static String messageOf(Throwable t) {
            return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }

        private static JsonNode tryParseJson(String raw) {
            if (raw == null || raw.isEmpty()) return null;
            try {
                return MAPPER.readTree(raw);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
