package io.github.jamesward.foldingtools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.jamesward.foldingtools.aggregate.AggregationHint;
import io.github.jamesward.foldingtools.aggregate.AutoAggregateStrategy;
import io.github.jamesward.foldingtools.listify.AutoListifyStrategy;
import io.github.jamesward.foldingtools.observe.ObserveAndCreateStrategy;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test: wire the advisor with all three strategies, run a
 * request through, and confirm the outbound tool list contains the
 * synthesized tools each strategy should produce. Separately, drive the
 * observing manager against a constructed {@link ChatResponse} +
 * {@link ToolExecutionResult} to confirm observations are recorded for
 * use by Strategy 3 on the next turn.
 */
class FoldingToolsIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GET_CUSTOMER_INPUT = """
            {"type":"object","properties":{"customerId":{"type":"string"}},"required":["customerId"]}""";
    private static final String FIND_CUSTOMER_INPUT = """
            {"type":"object","properties":{"email":{"type":"string"}},"required":["email"]}""";
    private static final String LIST_ORDERS_INPUT = """
            {"type":"object","properties":{"customerId":{"type":"string"}},"required":["customerId"]}""";

    @Test
    void allThreeStrategiesContributeSynthesizedToolsOnOneRequest() {
        ToolCallback getCustomer = fake("getCustomer", GET_CUSTOMER_INPUT,
                args -> "{\"customerId\":\"" + extract(args, "customerId") + "\",\"name\":\"Ada\"}");
        ToolCallback findCustomer = fake("findCustomer", FIND_CUSTOMER_INPUT,
                args -> "{\"customerId\":\"C-9\",\"name\":\"Ada\"}");
        ToolCallback listOrders = fake("listOrders", LIST_ORDERS_INPUT,
                args -> "{\"orders\":[]}");

        SessionObservationStore store = new InMemorySessionObservationStore(256);
        String sessionId = "session-integration";
        store.record(new FoldingObservation(sessionId, "findCustomer",
                "{\"email\":\"a@a.com\"}", "{\"customerId\":\"C-1\"}", Instant.now(), 1));
        store.record(new FoldingObservation(sessionId, "listOrders",
                "{\"customerId\":\"C-1\"}", "{\"orders\":[]}", Instant.now(), 2));
        store.record(new FoldingObservation(sessionId, "findCustomer",
                "{\"email\":\"b@b.com\"}", "{\"customerId\":\"C-2\"}", Instant.now(), 3));
        store.record(new FoldingObservation(sessionId, "listOrders",
                "{\"customerId\":\"C-2\"}", "{\"orders\":[]}", Instant.now(), 4));

        FoldingToolsProperties props = new FoldingToolsProperties();
        props.getObserve().setEnabled(true);

        List<FoldingStrategy> strategies = List.of(
                new AutoListifyStrategy(),
                new AutoAggregateStrategy(List.of(
                        new AggregationHint("findCustomer", "customerId", "listOrders", "customerId"))),
                new ObserveAndCreateStrategy());

        FoldingToolsAdvisor advisor = new FoldingToolsAdvisor(
                strategies, SessionIdResolver.chatMemoryConversationId(), store, props);

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(getCustomer, findCustomer, listOrders))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("hello", options))
                .context(Map.of(ChatMemory.CONVERSATION_ID, sessionId))
                .build();

        CapturingChain chain = new CapturingChain();
        advisor.adviseCall(request, chain);

        ChatClientRequest captured = chain.captured;
        assertThat(captured).isNotNull();
        ToolCallingChatOptions outOptions = (ToolCallingChatOptions) captured.prompt().getOptions();

        List<String> names = outOptions.getToolCallbacks().stream()
                .map(tc -> tc.getToolDefinition().name())
                .toList();

        assertThat(names).contains("getCustomer", "findCustomer", "listOrders");
        assertThat(names).as("Strategy 1 listifies getCustomer (Id param + get prefix)")
                .contains("getCustomerBatch");
        assertThat(names).as("Strategy 2 via explicit hint + Strategy 3 via observations collapse to one name")
                .contains("findCustomerThenListOrders");
        long aggregatesWithThatName = names.stream()
                .filter(n -> n.equals("findCustomerThenListOrders")).count();
        assertThat(aggregatesWithThatName).isEqualTo(1);

        assertThat(outOptions.getToolContext())
                .containsEntry(ObservingToolCallingManager.SESSION_ID_KEY, sessionId);

        ToolCallback listified = outOptions.getToolCallbacks().stream()
                .filter(tc -> tc.getToolDefinition().name().equals("getCustomerBatch"))
                .findFirst().orElseThrow();
        JsonNode batched = parse(listified.call("{\"customerIds\":[\"A\",\"B\"]}"));
        assertThat(batched).hasSize(2);
        assertThat(batched.get(0).path("output").path("customerId").asText()).isEqualTo("A");
        assertThat(batched.get(1).path("output").path("customerId").asText()).isEqualTo("B");

        ToolCallback aggregate = outOptions.getToolCallbacks().stream()
                .filter(tc -> tc.getToolDefinition().name().equals("findCustomerThenListOrders"))
                .findFirst().orElseThrow();
        JsonNode chained = parse(aggregate.call("{\"email\":\"new@c.com\"}"));
        assertThat(chained.path("source").path("customerId").asText()).isEqualTo("C-9");
        assertThat(chained.has("target")).isTrue();
    }

    @Test
    void observingToolCallingManagerRecordsRealCallsAndSkipsWhenNoSessionId() {
        SessionObservationStore store = new InMemorySessionObservationStore(16);
        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "findCustomer", "{\"customerId\":\"C-1\",\"name\":\"Ada\"}")))
                .build();
        ToolCallingManager delegate = new ToolCallingManager() {
            @Override
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
                return List.of();
            }

            @Override
            public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                return DefaultToolExecutionResult.builder()
                        .conversationHistory(List.of(toolResponseMessage))
                        .build();
            }
        };

        ObservingToolCallingManager observing = new ObservingToolCallingManager(delegate, store);

        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "findCustomer", "{\"email\":\"a@a.com\"}")))
                .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistant)));

        ToolCallingChatOptions withSession = ToolCallingChatOptions.builder()
                .toolContext(Map.of(ObservingToolCallingManager.SESSION_ID_KEY, "sess-1"))
                .build();
        observing.executeToolCalls(new Prompt("hi", withSession), chatResponse);

        List<FoldingObservation> recorded = store.observations("sess-1");
        assertThat(recorded).hasSize(1);
        FoldingObservation obs = recorded.get(0);
        assertThat(obs.toolName()).isEqualTo("findCustomer");
        assertThat(obs.arguments()).contains("a@a.com");
        assertThat(obs.result()).contains("C-1").contains("Ada");

        ToolCallingChatOptions withoutSession = ToolCallingChatOptions.builder().build();
        observing.executeToolCalls(new Prompt("hi", withoutSession), chatResponse);
        assertThat(store.observations("sess-1")).hasSize(1);
    }

    private static ToolCallback fake(String name, String schema, Function<String, String> handler) {
        ToolDefinition def = ToolDefinition.builder().name(name).description("x").inputSchema(schema).build();
        return new FakeToolCallback(def, handler);
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
        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return handler.apply(toolInput);
        }
    }

    private static final class CapturingChain implements CallAdvisorChain, StreamAdvisorChain {
        ChatClientRequest captured;

        @Override
        public ChatClientResponse nextCall(ChatClientRequest request) {
            captured = request;
            return ChatClientResponse.builder()
                    .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))))
                    .context(new HashMap<>())
                    .build();
        }

        @Override
        public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
            captured = request;
            return Flux.empty();
        }

        @Override
        public List<CallAdvisor> getCallAdvisors() {
            return List.of();
        }

        @Override
        public CallAdvisorChain copy(CallAdvisor after) {
            return this;
        }

        @Override
        public List<StreamAdvisor> getStreamAdvisors() {
            return List.of();
        }

        @SuppressWarnings("unused")
        private List<Message> unusedRef() {
            return List.of();
        }
    }
}
