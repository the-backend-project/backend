package com.github.thxmasj.backend.core.internal.database;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.Change;
import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.MappingFailure;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;

/// Events of the entity having an id, found by the id event of its id entity
public class EventsByIdEvent {

  private final DatabaseClient client;
  private final Map<UUID, EntityType> entityTypes;
  private final Map<EntityType, List<EventType<?, ?>>> eventTypesByEntityType;
  private final Clock clock;
  private final String sql;

  /// @param sql the SQL selecting the events of the entity, with its entity id and entity type id on each row. It
  ///            selects no rows if no entity has the id.
  public EventsByIdEvent(
      DatabaseClient client,
      String sql,
      Map<EntityType, List<EventType<?, ?>>> eventTypesByEntityType,
      Clock clock
  ) {
    this.client = client;
    this.entityTypes = eventTypesByEntityType.keySet().stream().collect(toMap(EntityType::id, identity()));
    this.eventTypesByEntityType = eventTypesByEntityType;
    this.clock = clock;
    this.sql = sql;
  }

  /// Completes empty if no entity has the id
  public Mono<EventLog> execute(EventType<?, ?> type, byte[] id) {
    return client.sql(sql)
        .name("EventsByIdEvent")
        .bind("type", type.id())
        .bind("data", id)
        .map(row -> {
          UUID entityId = row.get("EntityId", UUID.class);
          UUID entityTypeId = row.get("EntityTypeId", UUID.class);
          EntityType entityType = entityTypes.get(entityTypeId);
          if (entityType == null)
            throw new IllegalStateException("Unknown entity type " + entityTypeId + " of entity having id of type " + type.name());
          try {
            return new TypedEvent(entityType, entityId, Mappers.eventMapper(entityType, eventTypesByEntityType.get(entityType), clock).apply(entityId, row));
          } catch (MappingFailure e) {
            throw new MappingFailure("EventsByIdEvent: Failed to map row to event for entity type " + entityType.name(), e);
          }
        })
        .all()
        .collectList()
        .filter(events -> !events.isEmpty())
        .map(events -> new EventLog(
            events.getFirst().entityType(),
            events.getFirst().entityId(),
            events.stream().<EventLog.Entry<?>>map(TypedEvent::event).toList()
        ));
  }

  private record TypedEvent(EntityType entityType, UUID entityId, Change.Event<?> event) {}

}
