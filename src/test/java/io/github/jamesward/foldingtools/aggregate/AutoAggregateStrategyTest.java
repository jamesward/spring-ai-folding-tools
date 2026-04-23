package io.github.jamesward.foldingtools.aggregate;

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

class AutoAggregateStrategyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FoldingToolsProperties properties = new FoldingToolsProperties();

    private static final String FIND_CUSTOMER_INPUT = """
            {"type":"object",
             "properties":{"email":{"type":"string"}},
             "required":["email"]}
            """;
    private static final String CUSTOMER_OUTPUT = """
            {"type":"object",
             "properties":{
               "customerId":{"type":"string"},
               "name":{"type":"string"}
             }}
            """;
    private static final String LIST_ORDERS_INPUT = """
            {"type":"object",
             "properties":{"customerId":{"type":"string"}},
             "required":["customerId"]}
            """;

    @Test
    void explicitHintSynthesizesAggregateAndDispatchesThroughChain() {
        AtomicInteger srcCalls = new AtomicInteger();
        AtomicInteger tgtCalls = new AtomicInteger();
        ToolCallback findCustomer = fake("findCustomer", "Lookup customer by email", FIND_CUSTOMER_INPUT, args -> {
            srcCalls.incrementAndGet();
            return "{\"customerId\":\"C-42\",\"name\":\"Ada\"}";
        });
        ToolCallback listOrders = fake("listOrders", "List orders by customer id", LIST_ORDERS_INPUT, args -> {
            tgtCalls.incrementAndGet();
            return "{\"orders\":[\"O-1\",\"O-2\"],\"calledWith\":" + args + "}";
        });

        AutoAggregateStrategy strategy = new AutoAggregateStrategy(List.of(
                new AggregationHint("findCustomer", "customerId", "listOrders", "customerId")));

        List<ToolCallback> folded = strategy.fold(ctx(findCustomer, listOrders));

        assertThat(folded).hasSize(1);
        ToolCallback aggregate = folded.get(0);
        assertThat(SynthesizedToolCallback.isSynthesized(aggregate)).isTrue();
        assertThat(aggregate.getToolDefinition().name()).isEqualTo("findCustomerThenListOrders");
        assertThat(aggregate.getToolDefinition().inputSchema()).contains("\"email\"");

        String out = aggregate.call("{\"email\":\"ada@example.com\"}");
        JsonNode parsed = parse(out);

        assertThat(srcCalls.get()).isEqualTo(1);
        assertThat(tgtCalls.get()).isEqualTo(1);
        assertThat(parsed.path("source").path("customerId").asText()).isEqualTo("C-42");
        assertThat(parsed.path("target").path("orders").get(0).asText()).isEqualTo("O-1");
        assertThat(parsed.path("target").path("calledWith").path("customerId").asText()).isEqualTo("C-42");
    }

    @Test
    void autoMatchOnAggregatableSourceWithUniqueTarget() {
        ToolCallback findCustomer = fakeAggregatable(
                "findCustomer", "x", FIND_CUSTOMER_INPUT, CUSTOMER_OUTPUT,
                args -> "{\"customerId\":\"C-1\",\"name\":\"Ada\"}");
        ToolCallback listOrders = fake("listOrders", "x", LIST_ORDERS_INPUT,
                args -> "{\"orders\":[\"O-1\"]}");

        AutoAggregateStrategy strategy = new AutoAggregateStrategy();
        List<ToolCallback> folded = strategy.fold(ctx(findCustomer, listOrders));

        assertThat(folded).hasSize(1);
        assertThat(folded.get(0).getToolDefinition().name()).isEqualTo("findCustomerThenListOrders");

        JsonNode out = parse(folded.get(0).call("{\"email\":\"ada@example.com\"}"));
        assertThat(out.path("target").path("orders").get(0).asText()).isEqualTo("O-1");
    }

    @Test
    void autoMatchSkippedWhenTargetIsAmbiguous() {
        ToolCallback findCustomer = fakeAggregatable(
                "findCustomer", "x", FIND_CUSTOMER_INPUT, CUSTOMER_OUTPUT,
                args -> "{\"customerId\":\"C-1\"}");
        ToolCallback listOrders = fake("listOrders", "x", LIST_ORDERS_INPUT, args -> "{}");
        ToolCallback listInvoices = fake("listInvoices", "x", LIST_ORDERS_INPUT, args -> "{}");

        List<ToolCallback> folded = new AutoAggregateStrategy().fold(
                ctx(findCustomer, listOrders, listInvoices));

        assertThat(folded).hasSize(2);
        assertThat(folded).allSatisfy(tc ->
                assertThat(tc.getToolDefinition().name()).startsWith("findCustomerThen"));
    }

    @Test
    void hintTakesPrecedenceOverAutoMatch() {
        ToolCallback findCustomer = fakeAggregatable(
                "findCustomer", "x", FIND_CUSTOMER_INPUT, CUSTOMER_OUTPUT,
                args -> "{\"customerId\":\"C-1\"}");
        ToolCallback listOrders = fake("listOrders", "x", LIST_ORDERS_INPUT,
                args -> "{\"orders\":[]}");

        AutoAggregateStrategy strategy = new AutoAggregateStrategy(List.of(
                new AggregationHint("findCustomer", "customerId", "listOrders", "customerId")));

        List<ToolCallback> folded = strategy.fold(ctx(findCustomer, listOrders));

        assertThat(folded).hasSize(1);
    }

    @Test
    void sourceFailureShortCircuitsWithError() {
        ToolCallback findCustomer = fake("findCustomer", "x", FIND_CUSTOMER_INPUT,
                args -> { throw new IllegalStateException("db down"); });
        AtomicInteger tgtCalls = new AtomicInteger();
        ToolCallback listOrders = fake("listOrders", "x", LIST_ORDERS_INPUT,
                args -> { tgtCalls.incrementAndGet(); return "{}"; });

        AutoAggregateStrategy strategy = new AutoAggregateStrategy(List.of(
                new AggregationHint("findCustomer", "customerId", "listOrders", "customerId")));

        ToolCallback aggregate = strategy.fold(ctx(findCustomer, listOrders)).get(0);
        JsonNode out = parse(aggregate.call("{\"email\":\"x\"}"));

        assertThat(tgtCalls.get()).isEqualTo(0);
        assertThat(out.path("error").asText()).contains("findCustomer failed").contains("db down");
        assertThat(out.has("target")).isFalse();
    }

    @Test
    void targetFailureIncludesSourceOutputAndTargetError() {
        ToolCallback findCustomer = fake("findCustomer", "x", FIND_CUSTOMER_INPUT,
                args -> "{\"customerId\":\"C-1\"}");
        ToolCallback listOrders = fake("listOrders", "x", LIST_ORDERS_INPUT,
                args -> { throw new IllegalStateException("orders svc down"); });

        AutoAggregateStrategy strategy = new AutoAggregateStrategy(List.of(
                new AggregationHint("findCustomer", "customerId", "listOrders", "customerId")));

        ToolCallback aggregate = strategy.fold(ctx(findCustomer, listOrders)).get(0);
        JsonNode out = parse(aggregate.call("{\"email\":\"x\"}"));

        assertThat(out.path("source").path("customerId").asText()).isEqualTo("C-1");
        assertThat(out.path("error").asText()).contains("listOrders failed").contains("orders svc down");
        assertThat(out.has("target")).isFalse();
    }

    @Test
    void bindingFieldMissingInSourceOutputProducesError() {
        ToolCallback findCustomer = fake("findCustomer", "x", FIND_CUSTOMER_INPUT,
                args -> "{\"name\":\"Ada\"}");
        ToolCallback listOrders = fake("listOrders", "x", LIST_ORDERS_INPUT, args -> "{}");

        AutoAggregateStrategy strategy = new AutoAggregateStrategy(List.of(
                new AggregationHint("findCustomer", "customerId", "listOrders", "customerId")));

        ToolCallback aggregate = strategy.fold(ctx(findCustomer, listOrders)).get(0);
        JsonNode out = parse(aggregate.call("{\"email\":\"x\"}"));

        assertThat(out.path("error").asText()).contains("customerId").contains("missing");
        assertThat(out.has("target")).isFalse();
    }

    @Test
    void hintReferencingUnknownToolIsSkipped() {
        ToolCallback only = fake("findCustomer", "x", FIND_CUSTOMER_INPUT, args -> "{}");

        AutoAggregateStrategy strategy = new AutoAggregateStrategy(List.of(
                new AggregationHint("findCustomer", "customerId", "doesNotExist", "customerId")));

        assertThat(strategy.fold(ctx(only))).isEmpty();
    }

    @Test
    void targetWithExtraRequiredParamsIsSkipped() {
        ToolCallback findCustomer = fakeAggregatable(
                "findCustomer", "x", FIND_CUSTOMER_INPUT, CUSTOMER_OUTPUT,
                args -> "{\"customerId\":\"C-1\"}");
        String listOrdersWithExtras = """
                {"type":"object",
                 "properties":{
                   "customerId":{"type":"string"},
                   "region":{"type":"string"}
                 },
                 "required":["customerId","region"]}
                """;
        ToolCallback listOrders = fake("listOrders", "x", listOrdersWithExtras,
                args -> "{}");

        assertThat(new AutoAggregateStrategy().fold(ctx(findCustomer, listOrders))).isEmpty();
    }

    @Test
    void synthesizedCallbacksAreNotRecursivelyAggregated() {
        ToolCallback findCustomer = fake("findCustomer", "x", FIND_CUSTOMER_INPUT,
                args -> "{\"customerId\":\"C-1\"}");
        ToolCallback listOrders = fake("listOrders", "x", LIST_ORDERS_INPUT, args -> "{}");

        AutoAggregateStrategy pass1 = new AutoAggregateStrategy(List.of(
                new AggregationHint("findCustomer", "customerId", "listOrders", "customerId")));
        List<ToolCallback> first = pass1.fold(ctx(findCustomer, listOrders));
        assertThat(first).hasSize(1);

        List<ToolCallback> tools = new ArrayList<>(List.of(findCustomer, listOrders));
        tools.addAll(first);

        AutoAggregateStrategy pass2 = new AutoAggregateStrategy(List.of(
                new AggregationHint("findCustomerThenListOrders", "source", "listOrders", "customerId")));
        assertThat(pass2.fold(ctx(tools.toArray(new ToolCallback[0])))).isEmpty();
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

    private static ToolCallback fake(String name, String desc, String schema, Function<String, String> handler) {
        ToolDefinition def = ToolDefinition.builder().name(name).description(desc).inputSchema(schema).build();
        return new FakeToolCallback(def, handler);
    }

    private static ToolCallback fakeAggregatable(String name, String desc, String inputSchema,
                                                 String outputSchema, Function<String, String> handler) {
        ToolDefinition def = ToolDefinition.builder().name(name).description(desc).inputSchema(inputSchema).build();
        return new FakeAggregatableToolCallback(def, outputSchema, handler);
    }

    private record FakeToolCallback(ToolDefinition definition, Function<String, String> handler)
            implements ToolCallback {
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String toolInput) { return handler.apply(toolInput); }
    }

    private record FakeAggregatableToolCallback(
            ToolDefinition definition, String outputSchema, Function<String, String> handler)
            implements AggregatableToolCallback {
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String outputSchema() { return outputSchema; }
        @Override public String call(String toolInput) { return handler.apply(toolInput); }
    }
}
