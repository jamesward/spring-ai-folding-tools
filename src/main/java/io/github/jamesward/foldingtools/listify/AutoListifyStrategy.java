package io.github.jamesward.foldingtools.listify;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.jamesward.foldingtools.FoldingContext;
import io.github.jamesward.foldingtools.FoldingStrategy;
import io.github.jamesward.foldingtools.FoldingToolsProperties;
import io.github.jamesward.foldingtools.SynthesizedToolCallback;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Strategy 1: for each source tool whose name looks like a lookup
 * ({@code get/find/fetch/lookup/read/resolve}*) and which has a scalar
 * parameter matching {@link FoldingToolsProperties.Listify#getParameterNameRegex()}
 * ({@code .*(Id|Uuid|Key|Code)$} by default), synthesize a sibling tool
 * that accepts a list of values for that parameter.
 *
 * <p>v0 listifies at most one parameter per tool to avoid cartesian-
 * product semantics. Inputs are de-duplicated and capped at
 * {@link FoldingToolsProperties.Listify#getMaxListSize()}. Per-item
 * failures surface as {@code {"input":..., "error":...}} entries so the
 * model can reason about partial success.</p>
 */
public final class AutoListifyStrategy implements FoldingStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SCALAR_TYPES = Set.of("string", "integer", "number", "boolean");

    @Override
    public String name() {
        return "auto-listify";
    }

    @Override
    public List<ToolCallback> fold(FoldingContext context) {
        FoldingToolsProperties.Listify cfg = context.properties().getListify();
        if (!cfg.isEnabled()) {
            return List.of();
        }
        Pattern toolNameRegex = Pattern.compile(cfg.getToolNameRegex());
        Pattern paramNameRegex = Pattern.compile(cfg.getParameterNameRegex(), Pattern.CASE_INSENSITIVE);

        List<ToolCallback> produced = new ArrayList<>();
        for (ToolCallback source : context.sourceTools()) {
            if (SynthesizedToolCallback.isSynthesized(source)) {
                continue;
            }
            ToolDefinition def = source.getToolDefinition();
            if (!toolNameRegex.matcher(def.name()).matches()) {
                continue;
            }
            JsonNode schema;
            try {
                schema = MAPPER.readTree(def.inputSchema());
            } catch (Exception e) {
                continue;
            }
            Candidate candidate = pickCandidate(schema, paramNameRegex);
            if (candidate == null) {
                continue;
            }
            ToolCallback listified = synthesize(source, def, schema, candidate, cfg);
            if (listified != null) {
                produced.add(listified);
            }
        }
        return produced;
    }

    private Candidate pickCandidate(JsonNode schema, Pattern paramNameRegex) {
        if (!schema.isObject() || !schema.has("properties")) {
            return null;
        }
        JsonNode properties = schema.get("properties");
        if (!properties.isObject()) {
            return null;
        }
        var fields = properties.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String paramName = entry.getKey();
            JsonNode propSchema = entry.getValue();
            if (!paramNameRegex.matcher(paramName).matches()) {
                continue;
            }
            if (!isScalarSchema(propSchema)) {
                continue;
            }
            return new Candidate(paramName, (ObjectNode) propSchema);
        }
        return null;
    }

    private boolean isScalarSchema(JsonNode propSchema) {
        if (!propSchema.isObject()) {
            return false;
        }
        JsonNode type = propSchema.get("type");
        if (type == null || !type.isTextual()) {
            return false;
        }
        return SCALAR_TYPES.contains(type.asText());
    }

    private ToolCallback synthesize(
            ToolCallback source,
            ToolDefinition def,
            JsonNode originalSchema,
            Candidate candidate,
            FoldingToolsProperties.Listify cfg) {

        String listifiedParamName = listifiedName(candidate.paramName);
        ObjectNode newSchema = originalSchema.deepCopy();
        ObjectNode newProperties = (ObjectNode) newSchema.get("properties");

        ObjectNode arraySchema = MAPPER.createObjectNode();
        arraySchema.put("type", "array");
        arraySchema.set("items", candidate.scalarSchema.deepCopy());
        arraySchema.put("maxItems", cfg.getMaxListSize());
        arraySchema.put("description",
                "List of %s values to look up in one call (max %d)."
                        .formatted(candidate.paramName, cfg.getMaxListSize()));

        newProperties.remove(candidate.paramName);
        newProperties.set(listifiedParamName, arraySchema);

        if (newSchema.has("required") && newSchema.get("required").isArray()) {
            ArrayNode required = (ArrayNode) newSchema.get("required");
            ArrayNode rewritten = MAPPER.createArrayNode();
            for (JsonNode r : required) {
                rewritten.add(candidate.paramName.equals(r.asText()) ? listifiedParamName : r.asText());
            }
            newSchema.set("required", rewritten);
        }

        String newInputSchema;
        try {
            newInputSchema = MAPPER.writeValueAsString(newSchema);
        } catch (Exception e) {
            return null;
        }

        String newName = def.name() + "Batch";
        String newDescription = (def.description() == null ? "" : def.description() + " ")
                + "Accepts a list of %s values; returns one entry per input (same order, de-duplicated). "
                .formatted(candidate.paramName)
                + "Results are a JSON array of {input, output} or {input, error} objects.";

        ToolDefinition newDef = ToolDefinition.builder()
                .name(newName)
                .description(newDescription)
                .inputSchema(newInputSchema)
                .build();

        var dispatch = new ListifiedDispatch(
                source, candidate.paramName, listifiedParamName, cfg.getMaxListSize());

        return new SynthesizedToolCallback(
                newDef, dispatch, List.of(def.name()), name());
    }

    private static String listifiedName(String paramName) {
        if (paramName.endsWith("s") || paramName.endsWith("S")) {
            return paramName + "List";
        }
        return paramName + "s";
    }

    private record Candidate(String paramName, ObjectNode scalarSchema) {
    }

    private static final class ListifiedDispatch implements java.util.function.Function<String, String> {
        private final ToolCallback underlying;
        private final String originalParamName;
        private final String listParamName;
        private final int maxListSize;

        ListifiedDispatch(ToolCallback underlying, String originalParamName, String listParamName, int maxListSize) {
            this.underlying = Objects.requireNonNull(underlying);
            this.originalParamName = originalParamName;
            this.listParamName = listParamName;
            this.maxListSize = maxListSize;
        }

        @Override
        public String apply(String toolInput) {
            JsonNode input;
            try {
                input = MAPPER.readTree(toolInput);
            } catch (Exception e) {
                return errorJson("invalid JSON input: " + e.getMessage());
            }
            if (!input.isObject()) {
                return errorJson("expected JSON object input");
            }
            JsonNode arrayNode = input.get(listParamName);
            if (arrayNode == null || !arrayNode.isArray()) {
                return errorJson("missing array parameter '" + listParamName + "'");
            }

            ObjectNode template = input.deepCopy();
            template.remove(listParamName);

            Set<JsonNode> seen = new LinkedHashSet<>();
            ArrayNode results = MAPPER.createArrayNode();
            for (JsonNode item : arrayNode) {
                if (seen.size() >= maxListSize) break;
                if (!seen.add(item)) continue;

                ObjectNode perCall = template.deepCopy();
                perCall.set(originalParamName, item);

                ObjectNode entry = MAPPER.createObjectNode();
                entry.set("input", item);
                try {
                    String raw = underlying.call(MAPPER.writeValueAsString(perCall));
                    JsonNode parsed = tryParseJson(raw);
                    if (parsed != null) {
                        entry.set("output", parsed);
                    } else {
                        entry.put("output", raw);
                    }
                } catch (RuntimeException e) {
                    entry.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                } catch (Exception e) {
                    entry.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                results.add(entry);
            }
            try {
                return MAPPER.writeValueAsString(results);
            } catch (Exception e) {
                return errorJson("failed to serialize aggregated result: " + e.getMessage());
            }
        }

        private static JsonNode tryParseJson(String raw) {
            if (raw == null || raw.isEmpty()) return null;
            try {
                return MAPPER.readTree(raw);
            } catch (Exception e) {
                return null;
            }
        }

        private static String errorJson(String message) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("error", message);
            return n.toString();
        }
    }
}
