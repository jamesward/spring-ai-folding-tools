package io.github.jamesward.foldingtools;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;

public interface FoldingStrategy {

    String name();

    List<ToolCallback> fold(FoldingContext context);
}
