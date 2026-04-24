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

import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Boot autoconfiguration for spring-ai-folding-tools. Active when
 * {@link ToolCallback} is on the classpath (i.e. Spring AI is present).
 *
 * <p>Registers:</p>
 * <ul>
 *   <li>{@link FoldingToolsProperties} bound to
 *       {@code spring.ai.folding-tools.*}.</li>
 *   <li>Default {@link SessionIdResolver} reading
 *       {@code ChatMemory.CONVERSATION_ID} from the advisor context.</li>
 *   <li>Default {@link SessionObservationStore} backed by
 *       {@link InMemorySessionObservationStore}, sized from the
 *       {@code observe.buffer-size-per-session} property.</li>
 *   <li>Each strategy as an individually toggleable bean
 *       ({@code spring.ai.folding-tools.{listify|aggregate|observe}.enabled}).
 *       {@code observe} is off by default (it requires a chat-memory
 *       session id to be useful).</li>
 *   <li>The {@link FoldingToolsAdvisor} itself, receiving every
 *       {@link FoldingStrategy} bean in the context.</li>
 *   <li>An {@link ObservingToolCallingManager} bean ready to plug into
 *       a user-configured {@code ToolCallAdvisor}.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(ToolCallback.class)
@EnableConfigurationProperties(FoldingToolsAutoConfiguration.Properties.class)
public class FoldingToolsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SessionIdResolver foldingToolsSessionIdResolver() {
        return SessionIdResolver.chatMemoryConversationId();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionObservationStore foldingToolsSessionObservationStore(FoldingToolsProperties properties) {
        return new InMemorySessionObservationStore(properties.getObserve().getBufferSizePerSession());
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.folding-tools.listify",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    public AutoListifyStrategy autoListifyStrategy() {
        return new AutoListifyStrategy();
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.folding-tools.aggregate",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    public AutoAggregateStrategy autoAggregateStrategy(ObjectProvider<AggregationHint> hintProvider) {
        return new AutoAggregateStrategy(hintProvider.stream().toList());
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.folding-tools.observe",
            name = "enabled", havingValue = "true")
    public ObserveAndCreateStrategy observeAndCreateStrategy() {
        return new ObserveAndCreateStrategy();
    }

    @Bean
    @ConditionalOnMissingBean
    public FoldingToolsAdvisor foldingToolsAdvisor(
            List<FoldingStrategy> strategies,
            SessionIdResolver sessionIdResolver,
            SessionObservationStore observationStore,
            FoldingToolsProperties properties) {
        return new FoldingToolsAdvisor(strategies, sessionIdResolver, observationStore, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ToolCallingManager.class)
    public ObservingToolCallingManager observingToolCallingManager(
            ToolCallingManager delegate, SessionObservationStore observationStore) {
        return new ObservingToolCallingManager(delegate, observationStore);
    }

    @ConfigurationProperties(prefix = "spring.ai.folding-tools")
    public static class Properties extends FoldingToolsProperties {
    }
}
