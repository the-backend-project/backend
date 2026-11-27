package com.github.thxmasj.backend.core.internal.database;

import static java.lang.String.format;

import com.github.thxmasj.backend.core.Change;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.EventReference;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.MappingFailure;
import com.github.thxmasj.backend.core.database.Row;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

public class ConditionalTriggers {

  private final DatabaseClient databaseClient;
  private final Clock clock;
  private final Map<UUID, Map<UUID, EventType<?, ?>>> eventTypesByEntityTypeId;
  private final Map<UUID, EntityType> entityTypes;
  private final String sql;

  /// @param sql the SQL selecting the oldest conditional triggers whose condition is met, at most `:limit` of them
  public ConditionalTriggers(
      DatabaseClient databaseClient,
      String sql,
      Map<EntityType, List<EventType<?, ?>>> eventTypesByEntityType,
      Clock clock
  ) {
    this.databaseClient = databaseClient;
    this.eventTypesByEntityTypeId = eventTypesByEntityType.entrySet().stream().collect(Collectors.toMap(
        e -> e.getKey().id(),
        e -> e.getValue().stream().collect(Collectors.toMap(EventType::id, Function.identity()))
    ));
    this.clock = clock;
    this.entityTypes = eventTypesByEntityType.keySet().stream()
        .collect(Collectors.toMap(EntityType::id, Function.identity()));
    this.sql = sql;
  }

  /**
   * The oldest waiting conditional triggers whose condition is met, at most the given limit.
   */
  public Flux<Change.ConditionalTrigger<?>> execute(int limit) {
    return databaseClient.sql(sql)
        .name("ConditionalTriggers")
        .bind("limit", limit)
        .<Change.ConditionalTrigger<?>>map(this::map)
        .all();
  }

  private Change.ConditionalTrigger<?> map(Row row) {
    UUID entityTypeId = row.get("EntityTypeId", UUID.class);
    EntityType entityType = entityTypes.get(entityTypeId);
    if (entityType == null)
      throw new MappingFailure(format("No entity type for id %s", entityTypeId));
    return create(
        row.get("Id", UUID.class),
        new EventReference(row.get("TriggerEntityId", UUID.class), row.get("TriggerEventNumber", Integer.class)),
        entityType,
        row.get("EntityId", UUID.class),
        eventType(entityType, row.get("Type", UUID.class)),
        row.get("Data", byte[].class),
        ZonedDateTime.of(row.get("Timestamp", LocalDateTime.class), clock.getZone()),
        anyEventType(row.get("ReferenceType", UUID.class)),
        Arrays.stream(row.get("LastTypes", String.class).split(","))
            .<EventType<?, ?>>map(id -> anyEventType(UUID.fromString(id)))
            .toList(),
        DataType.uuid().unmarshal(row.get("IndexKey", byte[].class)),
        row.get("CorrelationId", String.class)
    );
  }

  private EventType<?, ?> eventType(EntityType entityType, UUID eventTypeId) {
    EventType<?, ?> eventType = eventTypesByEntityTypeId.get(entityType.id()).get(eventTypeId);
    if (eventType == null)
      throw new MappingFailure(format("No event type for type id %s in entity type %s", eventTypeId, entityType.name()));
    return eventType;
  }

  /**
   * Reference and last types belong to the referenced entities, whose entity type is not known here. They are only
   * used for their id and name, so any entity type's event type with a matching id will do.
   */
  private EventType<?, ?> anyEventType(UUID eventTypeId) {
    return eventTypesByEntityTypeId.values().stream()
        .map(eventTypes -> eventTypes.get(eventTypeId))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new MappingFailure(format("No event type for type id %s", eventTypeId)));
  }

  private <T> Change.ConditionalTrigger<T> create(
      UUID id,
      EventReference triggerEvent,
      EntityType entityType,
      UUID entityId,
      EventType<T, ?> type,
      byte[] data,
      ZonedDateTime timestamp,
      EventType<?, ?> referenceType,
      List<EventType<?, ?>> lastTypes,
      UUID indexKey,
      String correlationId
  ) {
    return new Change.ConditionalTrigger<>(
        id,
        triggerEvent,
        entityType,
        entityId,
        type,
        data != null ? type.inputDataType().unmarshal(data) : null,
        timestamp,
        referenceType,
        lastTypes,
        indexKey,
        correlationId
    );
  }

}
