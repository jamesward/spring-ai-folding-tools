package io.github.jamesward.foldingtools.observe;

import java.time.Instant;
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

class ObserveAndCreateStrategyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObserveAndCreateStrategy strategy = new ObserveAndCreateStrategy();
    private final FoldingToolsProperties properties = new FoldingToolsProperties();

    private static final String FIND_CUSTOMER_INPUT = """
            {"type":"object","properties":{"email":{"type":"string"}},"required":["email"]}""";
    private static final String LIST_ORDERS_INPUT = """
            {"type":"object","properties":{"customerId":{"type":"string"}},"required":["customerId"]}""";

    ObserveAndCreateStrategyTest() {
        properties.getObserve().setEnabled(true);
    }

    @Test
    void promotesBindingThatReachesMinObservationsAndDispatchesThroughChain() {
        AtomicInteger findCalls = new AtomicInteger();
        AtomicInteger listCalls = new AtomicInteger();
        ToolCallback findCustomer = fake("findCustomer", FIND_CUSTOMER_INPUT, args -> {
            findCalls.incrementAndGet();
            return "{\"customerId\":\"C-9\",\"name\":\"Ada\"}";
        });
        ToolCallback listOrders = fake("listOrders", LIST_ORDERS_INPUT, args -> {
            listCalls.incrementAndGet();
            return "{\"orders\":[\"O-1\"],\"echo\":" + args + "}";
        });

        List<FoldingObservation> obs = List.of(
                observation("findCustomer",
                        "{\"email\":\"ada@a.com\"}",
                        "{\"customerId\":\"C-1\",\"name\":\"Ada\"}",
                        1),
                observation("listOrders",
                        "{\"customerId\":\"C-1\"}",
                        "{\"orders\":[\"O-a\"]}",
                        2),
                observation("findCustomer",
                        "{\"email\":\"bob@b.com\"}",
                        "{\"customerId\":\"C-2\",\"name\":\"Bob\"}",
                        3),
                observation("listOrders",
                        "{\"customerId\":\"C-2\"}",
                        "{\"orders\":[\"O-b\"]}",
                        4));

        List<ToolCallback> folded = strategy.fold(ctx(obs, findCustomer, listOrders));

        assertThat(folded).hasSize(1);
        ToolCallback promoted = folded.get(0);
        assertThat(SynthesizedToolCallback.isSynthesized(promoted)).isTrue();
        assertThat(promoted.getToolDefinition().name()).isEqualTo("findCustomerThenListOrders");

        JsonNode out = parse(promoted.call("{\"email\":\"new@c.com\"}"));
        assertThat(findCalls.get()).isEqualTo(1);
        assertThat(listCalls.get()).isEqualTo(1);
        assertThat(out.path("source").path("customerId").asText()).isEqualTo("C-9");
        assertThat(out.path("target").path("orders").get(0).asText()).isEqualTo("O-1");
        assertThat(out.path("target").path("echo").path("customerId").asText()).isEqualTo("C-9");
    }

    @Test
    void singleOccurrenceDoesNotReachDefaultThreshold() {
        ToolCallback findCustomer = fake("findCustomer", FIND_CUSTOMER_INPUT, args -> "{}");
        ToolCallback listOrders = fake("listOrders", LIST_ORDERS_INPUT, args -> "{}");

        List<FoldingObservation> obs = List.of(
                observation("findCustomer",
                        "{\"email\":\"ada@a.com\"}",
                        "{\"customerId\":\"C-1\"}", 1),
                observation("listOrders",
                        "{\"customerId\":\"C-1\"}",
                        "{\"orders\":[]}", 2));

        assertThat(strategy.fold(ctx(obs, findCustomer, listOrders))).isEmpty();
    }

    @Test
    void minObservationsIsConfigurable() {
        properties.getObserve().setMinObservations(1);
        ToolCallback findCustomer = fake("findCustomer", FIND_CUSTOMER_INPUT, args -> "{}");
        ToolCallback listOrders = fake("listOrders", LIST_ORDERS_INPUT, args -> "{}");

        List<FoldingObservation> obs = List.of(
                observation("findCustomer",
                        "{\"email\":\"ada@a.com\"}",
                        "{\"customerId\":\"C-1\"}", 1),
                observation("listOrders",
                        "{\"customerId\":\"C-1\"}",
                        "{\"orders\":[]}", 2));

        List<ToolCallback> folded = strategy.fold(ctx(obs, findCustomer, listOrders));
        assertThat(folded).hasSize(1);
    }

    @Test
    void disabledConfigProducesNothing() {
        properties.getObserve().setEnabled(false);
        ToolCallback findCustomer = fake("findCustomer", FIND_CUSTOMER_INPUT, args -> "{}");
        ToolCallback listOrders = fake("listOrders", LIST_ORDERS_INPUT, args -> "{}");

        List<FoldingObservation> obs = manyMatching();
        assertThat(strategy.fold(ctx(obs, findCustomer, listOrders))).isEmpty();
    }

    @Test
    void selfLoopObservationsAreIgnored() {
        ToolCallback ping = fake("ping",
                "{\"type\":\"object\",\"properties\":{\"token\":{\"type\":\"string\"}},\"required\":[\"token\"]}",
                args -> "{\"token\":\"X\"}");

        List<FoldingObservation> obs = List.of(
                observation("ping", "{\"token\":\"X\"}", "{\"token\":\"X\"}", 1),
                observation("ping", "{\"token\":\"X\"}", "{\"token\":\"X\"}", 2));

        assertThat(strategy.fold(ctx(obs, ping))).isEmpty();
    }

    @Test
    void observationsOfKnownSynthesizedToolsAreIgnored() {
        ToolCallback findCustomer = fake("findCustomer", FIND_CUSTOMER_INPUT, args -> "{}");
        ToolCallback listOrders = fake("listOrders", LIST_ORDERS_INPUT, args -> "{}");
        ToolCallback synth = new SynthesizedToolCallback(
                ToolDefinition.builder()
                        .name("findCustomerThenListOrders")
                        .description("x")
                        .inputSchema(FIND_CUSTOMER_INPUT)
                        .build(),
                args -> "{}",
                List.of("findCustomer", "listOrders"),
                "auto-aggregate");

        List<FoldingObservation> obs = List.of(
                observation("findCustomer",
                        "{\"email\":\"a@a\"}", "{\"customerId\":\"C-1\"}", 1),
                observation("findCustomerThenListOrders",
                        "{\"email\":\"b@b\"}",
                        "{\"source\":{\"customerId\":\"C-2\"},\"target\":{}}", 2),
                observation("listOrders",
                        "{\"customerId\":\"C-1\"}", "{\"orders\":[]}", 3));

        assertThat(strategy.fold(ctx(obs, findCustomer, listOrders, synth))).isEmpty();
    }

    @Test
    void observationsForUnknownToolsAreIgnored() {
        ToolCallback findCustomer = fake("findCustomer", FIND_CUSTOMER_INPUT, args -> "{}");

        List<FoldingObservation> obs = List.of(
                observation("findCustomer", "{\"email\":\"a\"}", "{\"customerId\":\"C-1\"}", 1),
                observation("listOrders", "{\"customerId\":\"C-1\"}", "{\"orders\":[]}", 2),
                observation("findCustomer", "{\"email\":\"b\"}", "{\"customerId\":\"C-2\"}", 3),
                observation("listOrders", "{\"customerId\":\"C-2\"}", "{\"orders\":[]}", 4));

        assertThat(strategy.fold(ctx(obs, findCustomer))).isEmpty();
    }

    @Test
    void nonJsonArgumentsOrResultsAreIgnored() {
        ToolCallback findCustomer = fake("findCustomer", FIND_CUSTOMER_INPUT, args -> "{}");
        ToolCallback listOrders = fake("listOrders", LIST_ORDERS_INPUT, args -> "{}");

        List<FoldingObservation> obs = List.of(
                observation("findCustomer", "not-json", "{\"customerId\":\"C-1\"}", 1),
                observation("listOrders", "{\"customerId\":\"C-1\"}", "also-not-json", 2),
                observation("findCustomer", "{\"email\":\"b\"}", "scalar-only", 3),
                observation("listOrders", "{\"customerId\":\"C-2\"}", "{\"orders\":[]}", 4));

        assertThat(strategy.fold(ctx(obs, findCustomer, listOrders))).isEmpty();
    }

    @Test
    void matchesRequireEqualValuesNotJustMatchingFieldNames() {
        ToolCallback findCustomer = fake("findCustomer", FIND_CUSTOMER_INPUT, args -> "{}");
        ToolCallback listOrders = fake("listOrders", LIST_ORDERS_INPUT, args -> "{}");

        List<FoldingObservation> obs = List.of(
                observation("findCustomer",
                        "{\"email\":\"a@a\"}",
                        "{\"customerId\":\"RESULT-1\"}", 1),
                observation("listOrders",
                        "{\"customerId\":\"DIFFERENT\"}",
                        "{\"orders\":[]}", 2),
                observation("findCustomer",
                        "{\"email\":\"b@b\"}",
                        "{\"customerId\":\"RESULT-2\"}", 3),
                observation("listOrders",
                        "{\"customerId\":\"ALSO-DIFFERENT\"}",
                        "{\"orders\":[]}", 4));

        assertThat(strategy.fold(ctx(obs, findCustomer, listOrders))).isEmpty();
    }

    @Test
    void twoIndependentBindingsAreBothPromoted() {
        ToolCallback findCustomer = fake("findCustomer", FIND_CUSTOMER_INPUT, args -> "{}");
        ToolCallback listOrders = fake("listOrders", LIST_ORDERS_INPUT, args -> "{}");
        ToolCallback getAccount = fake("getAccount",
                "{\"type\":\"object\",\"properties\":{\"accountId\":{\"type\":\"string\"}},\"required\":[\"accountId\"]}",
                args -> "{\"accountId\":\"A\",\"owner\":\"X\"}");
        ToolCallback listTransactions = fake("listTransactions",
                "{\"type\":\"object\",\"properties\":{\"accountId\":{\"type\":\"string\"}},\"required\":[\"accountId\"]}",
                args -> "{}");

        List<FoldingObservation> obs = List.of(
                observation("findCustomer", "{\"email\":\"a\"}", "{\"customerId\":\"C-1\"}", 1),
                observation("listOrders", "{\"customerId\":\"C-1\"}", "{\"orders\":[]}", 2),
                observation("getAccount", "{\"accountId\":\"A-1\"}", "{\"accountId\":\"A-1\",\"owner\":\"X\"}", 3),
                observation("listTransactions", "{\"accountId\":\"A-1\"}", "{\"tx\":[]}", 4),
                observation("findCustomer", "{\"email\":\"b\"}", "{\"customerId\":\"C-2\"}", 5),
                observation("listOrders", "{\"customerId\":\"C-2\"}", "{\"orders\":[]}", 6),
                observation("getAccount", "{\"accountId\":\"A-2\"}", "{\"accountId\":\"A-2\",\"owner\":\"Y\"}", 7),
                observation("listTransactions", "{\"accountId\":\"A-2\"}", "{\"tx\":[]}", 8));

        List<ToolCallback> folded = strategy.fold(
                ctx(obs, findCustomer, listOrders, getAccount, listTransactions));

        assertThat(folded).extracting(tc -> tc.getToolDefinition().name())
                .containsExactlyInAnyOrder(
                        "findCustomerThenListOrders",
                        "getAccountThenListTransactions");
    }

    @Test
    void emptyObservationsReturnEmpty() {
        ToolCallback findCustomer = fake("findCustomer", FIND_CUSTOMER_INPUT, args -> "{}");
        assertThat(strategy.fold(ctx(List.of(), findCustomer))).isEmpty();
    }

    private List<FoldingObservation> manyMatching() {
        return List.of(
                observation("findCustomer",
                        "{\"email\":\"a\"}", "{\"customerId\":\"C-1\"}", 1),
                observation("listOrders",
                        "{\"customerId\":\"C-1\"}", "{\"orders\":[]}", 2),
                observation("findCustomer",
                        "{\"email\":\"b\"}", "{\"customerId\":\"C-2\"}", 3),
                observation("listOrders",
                        "{\"customerId\":\"C-2\"}", "{\"orders\":[]}", 4));
    }

    private static FoldingObservation observation(String tool, String args, String result, int turn) {
        return new FoldingObservation("session-1", tool, args, result, Instant.now(), turn);
    }

    private FoldingContext ctx(List<FoldingObservation> observations, ToolCallback... tools) {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("ignored"))
                .context(Map.of())
                .build();
        return new FoldingContext(
                List.of(tools), request, Optional.of("session-1"),
                new ArrayList<>(observations), properties);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ToolCallback fake(String name, String schema, Function<String, String> handler) {
        ToolDefinition def = ToolDefinition.builder().name(name).description("x").inputSchema(schema).build();
        return new FakeToolCallback(def, handler);
    }

    private record FakeToolCallback(ToolDefinition definition, Function<String, String> handler)
            implements ToolCallback {
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
