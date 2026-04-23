package io.github.jamesward.foldingtools.listify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.jamesward.foldingtools.FoldingContext;
import io.github.jamesward.foldingtools.FoldingObservation;
import io.github.jamesward.foldingtools.FoldingToolsProperties;
import io.github.jamesward.foldingtools.SynthesizedToolCallback;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

class AutoListifyStrategyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AutoListifyStrategy strategy = new AutoListifyStrategy();
    private final FoldingToolsProperties properties = new FoldingToolsProperties();

    @Test
    void synthesizesBatchToolForLookupWithScalarId() {
        FakeToolCallback getCustomer = new FakeToolCallback(
                "getCustomer",
                "Fetch one customer by id.",
                """
                {
                  "type":"object",
                  "properties":{
                    "customerId":{"type":"string","description":"Customer identifier."},
                    "region":{"type":"string","description":"Region filter."}
                  },
                  "required":["customerId"]
                }
                """,
                args -> "{\"id\":\"" + extract(args, "customerId") + "\",\"name\":\"C-" + extract(args, "customerId") + "\"}");

        List<ToolCallback> folded = strategy.fold(ctx(getCustomer));

        assertThat(folded).hasSize(1);
        ToolCallback batch = folded.get(0);
        assertThat(SynthesizedToolCallback.isSynthesized(batch)).isTrue();
        assertThat(batch.getToolDefinition().name()).isEqualTo("getCustomerBatch");

        JsonNode schema = parse(batch.getToolDefinition().inputSchema());
        assertThat(schema.path("properties").path("customerId").isMissingNode()).isTrue();
        JsonNode listProp = schema.path("properties").path("customerIds");
        assertThat(listProp.path("type").asText()).isEqualTo("array");
        assertThat(listProp.path("items").path("type").asText()).isEqualTo("string");
        assertThat(listProp.path("maxItems").asInt()).isEqualTo(properties.getListify().getMaxListSize());
        assertThat(schema.path("required").toString()).contains("customerIds");
        assertThat(schema.path("properties").path("region").path("type").asText()).isEqualTo("string");
    }

    @Test
    void dispatchesOverListInOrderAndDeduplicates() {
        AtomicInteger calls = new AtomicInteger();
        FakeToolCallback getCustomer = new FakeToolCallback(
                "getCustomer", "x",
                """
                {"type":"object","properties":{"customerId":{"type":"string"}},"required":["customerId"]}
                """,
                args -> {
                    calls.incrementAndGet();
                    return "{\"id\":\"" + extract(args, "customerId") + "\"}";
                });

        ToolCallback batch = strategy.fold(ctx(getCustomer)).get(0);

        String out = batch.call("{\"customerIds\":[\"A\",\"B\",\"A\",\"C\"]}");
        JsonNode arr = parse(out);

        assertThat(arr.isArray()).isTrue();
        assertThat(arr).hasSize(3);
        assertThat(arr.get(0).path("input").asText()).isEqualTo("A");
        assertThat(arr.get(0).path("output").path("id").asText()).isEqualTo("A");
        assertThat(arr.get(1).path("input").asText()).isEqualTo("B");
        assertThat(arr.get(2).path("input").asText()).isEqualTo("C");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void perItemFailuresDoNotAbortTheBatch() {
        FakeToolCallback getCustomer = new FakeToolCallback(
                "getCustomer", "x",
                """
                {"type":"object","properties":{"customerId":{"type":"string"}},"required":["customerId"]}
                """,
                args -> {
                    String id = extract(args, "customerId");
                    if ("BAD".equals(id)) {
                        throw new IllegalStateException("boom for " + id);
                    }
                    return "{\"id\":\"" + id + "\"}";
                });

        ToolCallback batch = strategy.fold(ctx(getCustomer)).get(0);

        JsonNode arr = parse(batch.call("{\"customerIds\":[\"A\",\"BAD\",\"C\"]}"));

        assertThat(arr).hasSize(3);
        assertThat(arr.get(0).path("output").path("id").asText()).isEqualTo("A");
        assertThat(arr.get(1).path("input").asText()).isEqualTo("BAD");
        assertThat(arr.get(1).path("error").asText()).contains("boom for BAD");
        assertThat(arr.get(1).has("output")).isFalse();
        assertThat(arr.get(2).path("output").path("id").asText()).isEqualTo("C");
    }

    @Test
    void preservesExtraParameters() {
        FakeToolCallback getCustomer = new FakeToolCallback(
                "getCustomer", "x",
                """
                {"type":"object",
                 "properties":{
                   "customerId":{"type":"string"},
                   "region":{"type":"string"}
                 },
                 "required":["customerId"]}
                """,
                args -> "{\"echo\":\"" + extract(args, "customerId") + "@" + extract(args, "region") + "\"}");

        ToolCallback batch = strategy.fold(ctx(getCustomer)).get(0);

        JsonNode arr = parse(batch.call("{\"customerIds\":[\"A\",\"B\"],\"region\":\"us-east\"}"));

        assertThat(arr.get(0).path("output").path("echo").asText()).isEqualTo("A@us-east");
        assertThat(arr.get(1).path("output").path("echo").asText()).isEqualTo("B@us-east");
    }

    @Test
    void respectsMaxListSize() {
        properties.getListify().setMaxListSize(2);
        AtomicInteger calls = new AtomicInteger();
        FakeToolCallback getCustomer = new FakeToolCallback(
                "getCustomer", "x",
                """
                {"type":"object","properties":{"customerId":{"type":"string"}},"required":["customerId"]}
                """,
                args -> {
                    calls.incrementAndGet();
                    return "{}";
                });

        ToolCallback batch = strategy.fold(ctx(getCustomer)).get(0);

        JsonNode schema = parse(batch.getToolDefinition().inputSchema());
        assertThat(schema.path("properties").path("customerIds").path("maxItems").asInt()).isEqualTo(2);

        JsonNode arr = parse(batch.call("{\"customerIds\":[\"A\",\"B\",\"C\",\"D\"]}"));
        assertThat(arr).hasSize(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void skipsToolsWhoseNameDoesNotMatchLookupPattern() {
        FakeToolCallback createCustomer = new FakeToolCallback(
                "createCustomer", "x",
                """
                {"type":"object","properties":{"customerId":{"type":"string"}},"required":["customerId"]}
                """,
                args -> "{}");

        assertThat(strategy.fold(ctx(createCustomer))).isEmpty();
    }

    @Test
    void skipsToolsWithNoScalarIdLikeParameter() {
        FakeToolCallback searchByName = new FakeToolCallback(
                "findCustomer", "x",
                """
                {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}
                """,
                args -> "{}");

        assertThat(strategy.fold(ctx(searchByName))).isEmpty();
    }

    @Test
    void skipsAlreadySynthesizedTools() {
        ToolCallback synthesized = new SynthesizedToolCallback(
                ToolDefinition.builder()
                        .name("getCustomerBatch")
                        .description("x")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"customerIds\":{\"type\":\"array\"}}}")
                        .build(),
                args -> "[]",
                List.of("getCustomer"),
                "auto-listify");

        assertThat(strategy.fold(ctx(synthesized))).isEmpty();
    }

    @Test
    void respectsCustomParameterNameRegex() {
        properties.getListify().setParameterNameRegex(".*Ref$");
        FakeToolCallback getOrder = new FakeToolCallback(
                "getOrder", "x",
                """
                {"type":"object","properties":{"orderRef":{"type":"string"}},"required":["orderRef"]}
                """,
                args -> "{\"ref\":\"" + extract(args, "orderRef") + "\"}");

        List<ToolCallback> folded = strategy.fold(ctx(getOrder));
        assertThat(folded).hasSize(1);
        assertThat(folded.get(0).getToolDefinition().name()).isEqualTo("getOrderBatch");
        JsonNode schema = parse(folded.get(0).getToolDefinition().inputSchema());
        assertThat(schema.path("properties").path("orderRefs").path("type").asText()).isEqualTo("array");
    }

    private FoldingContext ctx(ToolCallback... tools) {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("ignored"))
                .context(Map.of())
                .build();
        return new FoldingContext(
                List.of(tools), request, Optional.empty(),
                new ArrayList<FoldingObservation>(), properties);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String extract(String json, String field) {
        try {
            return MAPPER.readTree(json).path(field).asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record FakeToolCallback(ToolDefinition definition, Function<String, String> handler)
            implements ToolCallback {

        FakeToolCallback(String name, String description, String schema, Function<String, String> handler) {
            this(ToolDefinition.builder().name(name).description(description).inputSchema(schema).build(), handler);
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return handler.apply(toolInput);
        }
    }
}
