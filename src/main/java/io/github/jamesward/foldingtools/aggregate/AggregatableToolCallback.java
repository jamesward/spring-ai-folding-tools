package io.github.jamesward.foldingtools.aggregate;

import org.springframework.ai.tool.ToolCallback;

/**
 * Optional extension of {@link ToolCallback} that exposes the tool's
 * output JSON Schema. Tools that implement this interface become eligible
 * for automatic aggregation matching in {@code AutoAggregateStrategy};
 * tools that don't are still aggregatable via explicit
 * {@link AggregationHint}s.
 *
 * <p>The output schema should describe the shape of the value returned
 * by {@link ToolCallback#call(String)} (after any
 * {@code ToolCallResultConverter} has produced its JSON string).</p>
 */
public interface AggregatableToolCallback extends ToolCallback {

    String outputSchema();
}
