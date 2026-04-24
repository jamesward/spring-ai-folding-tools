package io.github.jamesward.foldingtools.observe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.jamesward.foldingtools.FoldingContext;
import io.github.jamesward.foldingtools.FoldingObservation;
import io.github.jamesward.foldingtools.FoldingStrategy;
import io.github.jamesward.foldingtools.FoldingToolsProperties;
import io.github.jamesward.foldingtools.SynthesizedToolCallback;
import io.github.jamesward.foldingtools.aggregate.ChainSynthesizer;

import org.springframework.ai.tool.ToolCallback;

/**
 * Strategy 3: learn tool composites from observed call sequences.
 *
 * <p>Walks the current session's {@link FoldingObservation}s, looking for
 * pairs where a later tool's argument value equals a top-level scalar
 * field in an earlier tool's result. Every such pair is counted as one
 * occurrence of a binding
 * {@code (sourceTool, sourceField) → (targetTool, targetParam)}. Bindings
 * whose count reaches
 * {@link FoldingToolsProperties.Observe#getMinObservations()} are promoted
 * to synthesized aggregate tools (via {@link ChainSynthesizer}).</p>
 *
 * <p>Unlike {@code AutoAggregateStrategy}, this strategy has runtime
 * evidence that the binding works — the model has already used it. So we
 * can accept looser matches than the static type-graph would allow (name
 * mismatches, type coercions) as long as the observed values matched.
 * v0 still requires exact JSON value equality for a match to count.</p>
 *
 * <p>Observations of tools already present in {@code sourceTools} as
 * {@link SynthesizedToolCallback}s are ignored — we do not learn composites
 * of our own composites.</p>
 */
public final class ObserveAndCreateStrategy implements FoldingStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "observe-and-create";
    }

    @Override
    public List<ToolCallback> fold(FoldingContext context) {
        FoldingToolsProperties.Observe cfg = context.properties().getObserve();
        if (!cfg.isEnabled()) {
            return List.of();
        }
        List<FoldingObservation> observations = context.observations();
        if (observations.size() < 2) {
            return List.of();
        }

        Map<String, ToolCallback> byName = new HashMap<>();
        Set<String> synthesizedNames = new HashSet<>();
        for (ToolCallback t : context.sourceTools()) {
            String n = t.getToolDefinition().name();
            byName.put(n, t);
            if (SynthesizedToolCallback.isSynthesized(t)) {
                synthesizedNames.add(n);
            }
        }

        List<ParsedObs> parsed = new ArrayList<>(observations.size());
        for (FoldingObservation o : observations) {
            if (synthesizedNames.contains(o.toolName())) continue;
            if (!byName.containsKey(o.toolName())) continue;
            JsonNode args = tryParse(o.arguments());
            JsonNode result = tryParse(o.result());
            if (args == null || result == null) continue;
            if (!args.isObject() || !result.isObject()) continue;
            parsed.add(new ParsedObs(o, args, result));
        }

        Map<BindingKey, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < parsed.size(); i++) {
            ParsedObs a = parsed.get(i);
            for (int j = i + 1; j < parsed.size(); j++) {
                ParsedObs b = parsed.get(j);
                if (a.obs.toolName().equals(b.obs.toolName())) continue;
                accumulate(counts, a, b);
            }
        }

        List<ToolCallback> produced = new ArrayList<>();
        Set<String> producedNames = new HashSet<>();
        int min = Math.max(1, cfg.getMinObservations());
        for (Map.Entry<BindingKey, Integer> entry : counts.entrySet()) {
            if (entry.getValue() < min) continue;
            BindingKey key = entry.getKey();
            ToolCallback source = byName.get(key.sourceName());
            ToolCallback target = byName.get(key.targetName());
            if (source == null || target == null) continue;

            ToolCallback synth = ChainSynthesizer.synthesize(
                    source, target, key.sourceField(), key.targetParam(), name());
            if (synth != null && producedNames.add(synth.getToolDefinition().name())) {
                produced.add(synth);
            }
        }
        return produced;
    }

    private void accumulate(Map<BindingKey, Integer> counts, ParsedObs a, ParsedObs b) {
        Iterator<Map.Entry<String, JsonNode>> aFields = a.result.fields();
        while (aFields.hasNext()) {
            Map.Entry<String, JsonNode> aEntry = aFields.next();
            if (!aEntry.getValue().isValueNode() || aEntry.getValue().isNull()) continue;
            Iterator<Map.Entry<String, JsonNode>> bFields = b.args.fields();
            while (bFields.hasNext()) {
                Map.Entry<String, JsonNode> bEntry = bFields.next();
                if (!bEntry.getValue().isValueNode() || bEntry.getValue().isNull()) continue;
                if (aEntry.getValue().equals(bEntry.getValue())) {
                    BindingKey key = new BindingKey(
                            a.obs.toolName(), aEntry.getKey(),
                            b.obs.toolName(), bEntry.getKey());
                    counts.merge(key, 1, Integer::sum);
                }
            }
        }
    }

    private static JsonNode tryParse(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private record ParsedObs(FoldingObservation obs, JsonNode args, JsonNode result) {
    }

    private record BindingKey(String sourceName, String sourceField, String targetName, String targetParam) {
    }
}
