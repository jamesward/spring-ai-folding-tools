package io.github.jamesward.foldingtools.aggregate;

import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.jamesward.foldingtools.SynthesizedToolCallback;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Shared synthesis helper for chained aggregates. Given a source tool, a
 * target tool, and a binding from a top-level field in the source's JSON
 * output to a parameter on the target's input, produces a
 * {@link SynthesizedToolCallback} whose {@code call()} runs source →
 * extract → target and returns the combined result.
 *
 * <p>Used by both {@code AutoAggregateStrategy} (static bindings from
 * hints and output-schema matching) and {@code ObserveAndCreateStrategy}
 * (bindings learned from observed tool-call sequences).</p>
 */
public final class ChainSynthesizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChainSynthesizer() {}

    /**
     * @return a synthesized chained tool, or {@code null} if the target has
     *         required parameters that aren't satisfied by the binding
     *         alone or the target's input schema can't be parsed.
     */
    public static ToolCallback synthesize(
            ToolCallback source,
            ToolCallback target,
            String sourceOutputField,
            String targetInputParam,
            String strategyName) {

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
                newDef, dispatch, List.of(sourceDef.name(), targetDef.name()), strategyName);
    }

    private static boolean hasUnsatisfiedRequiredParams(JsonNode targetSchema, String boundParam) {
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
                result.put("error", source.getToolDefinition().name() + " failed: " + messageOf(e));
                return result.toString();
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
                result.put("error", target.getToolDefinition().name() + " failed: " + messageOf(e));
                return result.toString();
            } catch (Exception e) {
                result.put("error", target.getToolDefinition().name() + " failed: " + e.getClass().getSimpleName());
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
