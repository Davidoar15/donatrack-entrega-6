package ar.edu.utn.dds.k3003.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@ConditionalOnProperty(name = "donatrack.read-model.publisher.enabled", havingValue = "true")
public class ReadModelRabbitConfiguration {

  @Bean
  public TopicExchange readModelExchange(
      @Value("${donatrack.read-model.exchange:donatrack.readmodel.exchange}") String exchange) {
    return new TopicExchange(exchange, true, false);
  }

  @Bean
  public MessageConverter readModelMessageConverter(ObjectMapper mapper) {
    return new Jackson2JsonMessageConverter(mapper);
  }
}
