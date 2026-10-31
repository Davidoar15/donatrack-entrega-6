package ar.edu.utn.dds.k3003.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publica snapshots de dominio después del commit de la transacción local. */
@Component
@ConditionalOnProperty(name = "donatrack.read-model.publisher.enabled", havingValue = "true")
public class ReadModelEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(ReadModelEventPublisher.class);

  private final RabbitTemplate rabbitTemplate;
  private final boolean enabled;
  private final String source;
  private final String exchange;
  private final String routingKey;

  public ReadModelEventPublisher(
      RabbitTemplate rabbitTemplate,
      @Value("${donatrack.read-model.publisher.enabled:false}") boolean enabled,
      @Value("${spring.application.name:donatrack}") String source,
      @Value("${donatrack.read-model.exchange:donatrack.readmodel.exchange}") String exchange,
      @Value("${donatrack.read-model.routing-key:readmodel.changed}") String routingKey) {
    this.rabbitTemplate = rabbitTemplate;
    this.enabled = enabled;
    this.source = source;
    this.exchange = exchange;
    this.routingKey = routingKey;
  }

  public void publicar(
      String resourceType, String resourceId, String operation, Map<String, Object> payload) {
    if (!enabled) {
      return;
    }
    if (resourceType == null || resourceType.isBlank()
        || resourceId == null || resourceId.isBlank()
        || operation == null || operation.isBlank()) {
      throw new IllegalArgumentException("Datos incompletos para publicar un evento del modelo de lectura");
    }

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("eventId", UUID.randomUUID().toString());
    event.put("source", source);
    event.put("resourceType", resourceType);
    event.put("resourceId", resourceId);
    event.put("operation", operation.toUpperCase());
    event.put("occurredAt", Instant.now().toString());
    event.put("payload", payload == null ? Map.of() : new LinkedHashMap<>(payload));

    Runnable send = () -> enviar(event);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          send.run();
        }
      });
    } else {
      send.run();
    }
  }

  private void enviar(Map<String, Object> event) {
    try {
      rabbitTemplate.convertAndSend(exchange, routingKey, event);
      log.info("readmodel.event.published eventId={} resourceType={} resourceId={} operation={}",
          event.get("eventId"), event.get("resourceType"), event.get("resourceId"), event.get("operation"));
    } catch (AmqpException exception) {
      // El fallo queda visible en logs; no se devuelve un error HTTP engañoso después del commit.
      log.error("readmodel.event.publish_failed eventId={} resourceType={} resourceId={}",
          event.get("eventId"), event.get("resourceType"), event.get("resourceId"), exception);
    }
  }
}
