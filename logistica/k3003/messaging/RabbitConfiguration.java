package ar.edu.utn.dds.k3003.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class RabbitConfiguration {

    public static final String EXCHANGE = "logistica.exchange";
    public static final String QUEUE = "logistica.matchmaking";
    public static final String ROUTING_KEY = "logistica.donacion.pendiente";
    public static final String DLX = "logistica.dlx";
    public static final String DLQ = "logistica.matchmaking.dlq";
    public static final String DLQ_ROUTING_KEY = "logistica.donacion.error";

    @Bean
    public DirectExchange logisticaExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    @ConditionalOnProperty(name = "donatrack.read-model.publisher.enabled", havingValue = "true")
    public TopicExchange readModelExchange(
        @Value("${donatrack.read-model.exchange:donatrack.readmodel.exchange}") String exchange
    ) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue matchmakingQueue() {
        return QueueBuilder
            .durable(QUEUE)
            .deadLetterExchange(DLX)
            .deadLetterRoutingKey(DLQ_ROUTING_KEY)
            .build();
    }

    @Bean
    Queue matchmakingDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding deadLetterBinding(
        Queue matchmakingDeadLetterQueue,
        DirectExchange deadLetterExchange
    ) {
        return BindingBuilder
            .bind(matchmakingDeadLetterQueue)
            .to(deadLetterExchange)
            .with(DLQ_ROUTING_KEY);
    }

    @Bean
    public Binding matchmakingBinding(
        Queue matchmakingQueue,
        DirectExchange logisticaExchange
    ) {
        return BindingBuilder.bind(matchmakingQueue)
            .to(logisticaExchange)
            .with(ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMessageConverter(ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper);
    }
}
