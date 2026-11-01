# DonaTrack — Entrega 6: delta de los cuatro componentes existentes

Este ZIP contiene **únicamente archivos nuevos o editados** respecto del ZIP de Entrega 5. No contiene los repositorios completos. Para aplicarlo, extraé el contenido sobre cada repositorio correspondiente, conservando las rutas relativas.

## Alcance

Se incorporó en los cuatro módulos la publicación explícita de eventos de cambios de dominio para alimentar el modelo de lectura CQRS de Entrega 6. Los eventos se emiten después del commit de la transacción local. También se sincronizan con eventos `DELETE` las operaciones administrativas de limpieza que ya existían, para no dejar proyecciones obsoletas cuando se reinician datos.

**No se incluyen ni se modifican** el Servicio de Consultas, el API Gateway, MCP Server ni Bot de Telegram. Tampoco se agregan Dockerfiles, archivos Swagger/OpenAPI, dashboards ni documentación específica de otros servicios.

## Archivos por componente

### donaciones-main

**EDITADOS**

- `pom.xml`
- `src/main/java/ar/edu/utn/dds/k3003/Fachada.java`
- `src/main/resources/application.properties`

**NUEVOS**

- `src/main/java/ar/edu/utn/dds/k3003/messaging/ReadModelEventPublisher.java`
- `src/main/java/ar/edu/utn/dds/k3003/messaging/ReadModelRabbitConfiguration.java`

### Donadores-Y-Entidades-main

**EDITADOS**

- `pom.xml`
- `src/main/java/ar/edu/utn/dds/k3003/Fachada.java`
- `src/main/java/ar/edu/utn/dds/k3003/controllers/SistemaController.java`
- `src/main/java/ar/edu/utn/dds/k3003/model/Donador.java`
- `src/main/resources/application.properties`

**NUEVOS**

- `src/main/java/ar/edu/utn/dds/k3003/messaging/ReadModelEventPublisher.java`
- `src/main/java/ar/edu/utn/dds/k3003/messaging/ReadModelRabbitConfiguration.java`

### Componente_Logistica-main

**EDITADOS**

- `src/main/java/ar/edu/utn/dds/k3003/Fachada.java`
- `src/main/java/ar/edu/utn/dds/k3003/messaging/RabbitConfiguration.java`
- `src/main/java/ar/edu/utn/dds/k3003/services/AdminDbService.java`
- `src/main/resources/application.properties`

**NUEVOS**

- `src/main/java/ar/edu/utn/dds/k3003/messaging/ReadModelEventPublisher.java`

### Incentivos-main

**EDITADOS**

- `pom.xml`
- `src/main/java/ar/edu/utn/dds/k3003/Fachada.java`
- `src/main/resources/application.properties`

**NUEVOS**

- `src/main/java/ar/edu/utn/dds/k3003/messaging/ReadModelEventPublisher.java`
- `src/main/java/ar/edu/utn/dds/k3003/messaging/ReadModelRabbitConfiguration.java`

## Eventos publicados

Todos los módulos publican JSON con el siguiente contrato común:

```json
{
  "eventId": "UUID único",
  "source": "nombre-del-componente",
  "resourceType": "tipo de recurso",
  "resourceId": "identificador del recurso",
  "operation": "UPSERT o DELETE",
  "occurredAt": "fecha ISO-8601 en UTC",
  "payload": { "snapshot": "del recurso" }
}
```

| Componente | `resourceType` publicados | Cambios cubiertos |
|---|---|---|
| Donaciones | `donacion`, `producto`, `categoria` | Alta y cambio de estado/queja de donaciones; altas, modificaciones y bajas de productos y categorías; limpieza administrativa. |
| Donadores y Entidades | `donador`, `entidad`, `necesidad`, `queja` | Alta y modificación de donadores/entidades; cambios de estado y categoría; historial de categorías; altas, modificaciones, satisfacción y bajas de necesidades; quejas; limpieza administrativa. |
| Logística | `deposito`, `paquete`, `asignacion` | Alta, modificación y baja de depósitos; ingreso de paquetes; cambios de estado de paquetes y asignaciones; asignación desde stock, matchmaking y entregas; limpieza administrativa. |
| Incentivos | `insignia`, `mision`, `incentivo_donador` | Altas, modificaciones y bajas de insignias/misiones; asignaciones y progreso de incentivos; limpieza administrativa. |

El publisher envía los mensajes al exchange y routing key configurados. El Servicio de Consultas (fuera de este ZIP) debe declarar/bindear una cola durable a ese exchange y consumir el contrato anterior. Los `resourceType` se envían en singular, exactamente como aparecen en la tabla.

## Configuración de despliegue

Configurar en los cuatro servicios:

```text
READ_MODEL_PUBLISHER_ENABLED=true
RABBITMQ_URL=amqps://spitutun:... # Misma que usa Logística y sus Workers
```

Opcionales — deben coincidir con la configuración del consumidor:

```text
READ_MODEL_EXCHANGE=donatrack.readmodel.exchange
READ_MODEL_ROUTING_KEY=readmodel.changed
```

El publisher queda **desactivado por defecto** (`READ_MODEL_PUBLISHER_ENABLED=false`) para conservar el funcionamiento de Entrega 5 cuando no se configura la mensajería del modelo de lectura. En Logística se conserva además su configuración RabbitMQ existente para el flujo de matchmaking; no se reemplaza su exchange, cola ni routing key de negocio.

## Notas importantes

1. **No hay carga retroactiva automática.** Al habilitar el publisher, se publican los cambios que ocurran a partir de ese momento. Los registros que ya estaban en las bases de Entrega 5 necesitan una sincronización inicial/reproceso por separado o volver a generarse mediante las operaciones de los módulos. Esa carga inicial pertenece al proceso que construya el modelo de lectura y no se implementa en este delta.
2. **Publicación después del commit, sin outbox transaccional.** Si RabbitMQ falla después de que la base del módulo confirmó la operación, el componente registra el error en logs y no revierte una operación ya confirmada. Para garantía durable ante caídas del broker haría falta implementar un outbox/reintento; no se agregó porque implicaría ampliar el alcance y el esquema persistente de los cuatro módulos.
3. **Historial de categorías.** Donadores y Entidades ahora persiste `Donador.historialCategorias` en una colección JPA (`donador_historial_categorias`). Con `spring.jpa.hibernate.ddl-auto=update`, Hibernate crea/actualiza esa estructura. Para donadores preexistentes cuyo historial no se guardaba, no es posible reconstruir sus categorías pasadas: al primer cambio/evento se registra como mínimo la categoría actual conocida y desde ahí se acumulan cambios reales.
4. Las operaciones de limpieza publican eventos `DELETE` antes de borrar los datos, pero se despachan al broker después del commit. El consumidor debe aplicar los `DELETE` de forma idempotente, igual que los `UPSERT`.

## Validación realizada

- Se verificó que los cuatro `pom.xml` siguen siendo XML válido.
- Se realizó una pasada de análisis sintáctico Java; no se detectaron errores de sintaxis en los fuentes revisados.
- **No pude completar `mvn compile` ni las pruebas:** Maven Wrapper no logró descargar Maven 3.9.9 desde `repo.maven.apache.org` en este entorno. Por lo tanto, este ZIP no debe considerarse una compilación Maven validada; ejecutar `./mvnw -DskipTests compile` y las pruebas de cada repositorio antes de desplegar.
