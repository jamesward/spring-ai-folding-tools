package io.github.jamesward.foldingtools.autoconfigure;

import java.util.List;

import io.github.jamesward.foldingtools.FoldingStrategy;
import io.github.jamesward.foldingtools.FoldingToolsAdvisor;
import io.github.jamesward.foldingtools.FoldingToolsProperties;
import io.github.jamesward.foldingtools.InMemorySessionObservationStore;
import io.github.jamesward.foldingtools.ObservingToolCallingManager;
import io.github.jamesward.foldingtools.SessionIdResolver;
import io.github.jamesward.foldingtools.SessionObservationStore;
import io.github.jamesward.foldingtools.aggregate.AggregationHint;
import io.github.jamesward.foldingtools.aggregate.AutoAggregateStrategy;
import io.github.jamesward.foldingtools.listify.AutoListifyStrategy;
import io.github.jamesward.foldingtools.observe.ObserveAndCreateStrategy;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class FoldingToolsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoldingToolsAutoConfiguration.class))
            .withUserConfiguration(ToolCallingManagerConfig.class);

    @Test
    void registersAdvisorAndDefaultBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(FoldingToolsAdvisor.class);
            assertThat(context).hasSingleBean(SessionIdResolver.class);
            assertThat(context).hasSingleBean(SessionObservationStore.class);
            assertThat(context.getBean(SessionObservationStore.class))
                    .isInstanceOf(InMemorySessionObservationStore.class);
            assertThat(context).hasSingleBean(ObservingToolCallingManager.class);
        });
    }

    @Test
    void listifyAndAggregateOnByDefaultObserveOff() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AutoListifyStrategy.class);
            assertThat(context).hasSingleBean(AutoAggregateStrategy.class);
            assertThat(context).doesNotHaveBean(ObserveAndCreateStrategy.class);

            List<FoldingStrategy> strategies = context.getBean(FoldingToolsAdvisor.class)
                    .getClass() == FoldingToolsAdvisor.class
                    ? context.getBeanProvider(FoldingStrategy.class).stream().toList()
                    : List.of();
            assertThat(strategies).extracting(FoldingStrategy::name)
                    .containsExactlyInAnyOrder("auto-listify", "auto-aggregate");
        });
    }

    @Test
    void observeTurnsOnExplicitly() {
        contextRunner
                .withPropertyValues("spring.ai.folding-tools.observe.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObserveAndCreateStrategy.class);
                    List<String> names = context.getBeanProvider(FoldingStrategy.class)
                            .stream().map(FoldingStrategy::name).toList();
                    assertThat(names).contains("observe-and-create");
                });
    }

    @Test
    void individualStrategiesCanBeDisabled() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.folding-tools.listify.enabled=false",
                        "spring.ai.folding-tools.aggregate.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AutoListifyStrategy.class);
                    assertThat(context).doesNotHaveBean(AutoAggregateStrategy.class);
                });
    }

    @Test
    void propertiesAreBoundUnderSpringAiFoldingToolsPrefix() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.folding-tools.max-synthesized-tools=7",
                        "spring.ai.folding-tools.listify.max-list-size=42",
                        "spring.ai.folding-tools.observe.min-observations=5")
                .run(context -> {
                    FoldingToolsProperties props = context.getBean(FoldingToolsProperties.class);
                    assertThat(props.getMaxSynthesizedTools()).isEqualTo(7);
                    assertThat(props.getListify().getMaxListSize()).isEqualTo(42);
                    assertThat(props.getObserve().getMinObservations()).isEqualTo(5);
                });
    }

    @Test
    void userBeansOverrideDefaults() {
        contextRunner
                .withUserConfiguration(CustomBeansConfig.class)
                .run(context -> {
                    SessionIdResolver resolver = context.getBean(SessionIdResolver.class);
                    assertThat(resolver).isSameAs(CustomBeansConfig.CUSTOM_RESOLVER);
                    SessionObservationStore store = context.getBean(SessionObservationStore.class);
                    assertThat(store).isSameAs(CustomBeansConfig.CUSTOM_STORE);
                });
    }

    @Test
    void aggregationHintsArePickedUpByStrategy() {
        contextRunner
                .withUserConfiguration(HintConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AutoAggregateStrategy.class);
                    assertThat(context.getBeansOfType(AggregationHint.class)).hasSize(2);
                });
    }

    @Configuration
    static class ToolCallingManagerConfig {
        @Bean
        ToolCallingManager toolCallingManager() {
            return new ToolCallingManager() {
                @Override
                public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
                    return List.of();
                }

                @Override
                public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                    throw new UnsupportedOperationException("stub");
                }
            };
        }
    }

    @Configuration
    static class CustomBeansConfig {
        static final SessionIdResolver CUSTOM_RESOLVER = req -> java.util.Optional.of("custom");
        static final SessionObservationStore CUSTOM_STORE = new InMemorySessionObservationStore(8);

        @Bean
        SessionIdResolver customResolver() {
            return CUSTOM_RESOLVER;
        }

        @Bean
        SessionObservationStore customStore() {
            return CUSTOM_STORE;
        }
    }

    @Configuration
    static class HintConfig {
        @Bean
        AggregationHint a() {
            return new AggregationHint("findCustomer", "customerId", "listOrders", "customerId");
        }

        @Bean
        AggregationHint b() {
            return new AggregationHint("getAccount", "accountId", "listTransactions", "accountId");
        }
    }
}
