package io.github.jamesward.foldingtools.aggregate;

/**
 * Declarative binding between two tools: after calling {@code sourceToolName},
 * extract the field at {@code sourceOutputField} from its JSON output and
 * pass it as {@code targetInputParam} to {@code targetToolName}.
 *
 * <p>The source output field is a top-level property name in the source
 * tool's returned JSON object. Nested paths are not supported in v0.</p>
 */
public record AggregationHint(
        String sourceToolName,
        String sourceOutputField,
        String targetToolName,
        String targetInputParam) {
}
