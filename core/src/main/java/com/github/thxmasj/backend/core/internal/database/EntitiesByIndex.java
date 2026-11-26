package com.github.thxmasj.backend.core.internal.database;

import static java.util.Collections.unmodifiableList;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.Row;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class EntitiesByIndex {

  private final DatabaseClient client;
  private final Map<UUID, BiFunction<UUID, Row, Event<?>>> eventMappers;
  private final Map<UUID, EntityType> entityTypes;
  private final String sql;

  /// Maps the events of each entity type with its own event types
  ///
  /// @param sql the SQL selecting the events of the indexed entities
  public EntitiesByIndex(
      DatabaseClient client,
      String sql,
      Map<? extends EntityType, List<EventType<?, ?>>> eventTypesByEntityType,
      Clock clock
  ) {
    this.client = client;
    this.eventMappers = eventTypesByEntityType.entrySet().stream()
        .collect(Collectors.toMap(e -> e.getKey().id(), e -> Mappers.eventMapper(e.getValue(), clock)));
    this.entityTypes = eventTypesByEntityType.keySet().stream().collect(Collectors.toMap(EntityType::id, Function.identity()));
    this.sql = sql;
  }

  public Flux<EventLog> execute(UUID key, EventType<?, ?> referenceType) {
    return execute(DataType.uuid().marshal(key), referenceType);
  }

  /// @param key the marshalled index key
  public Flux<EventLog> execute(byte[] key, EventType<?, ?> referenceType) {
    return client.sql(sql)
        .name("EntitiesByIndex")
        .bind("value", key)
        .bind("referenceType", referenceType.id())
        .map(row -> {
          UUID entityId = row.get("EntityId", UUID.class);
          UUID entityTypeId = row.get("EntityTypeId", UUID.class);
          // Rows of entity types unknown to this state machine are skipped
          if (!entityTypes.containsKey(entityTypeId))
            return Optional.<ModelEvent>empty();
          return Optional.of(new ModelEvent(entityId, entityTypeId, eventMappers.get(entityTypeId).apply(entityId, row)));
        })
        .all()
        .flatMap(Mono::justOrEmpty)
        .collectList()
        .flatMapIterable(events -> {
          Map<UUID, List<Event<?>>> eventsByEntityId = new LinkedHashMap<>();
          Map<UUID, UUID> entityTypeIdByEntityId = new HashMap<>();
          for (ModelEvent event : events) {
            entityTypeIdByEntityId.putIfAbsent(event.entityId(), event.entityTypeId());
            eventsByEntityId.computeIfAbsent(event.entityId(), _ -> new ArrayList<>()).add(event.event());
          }
          List<EventLog> eventLogs = new ArrayList<>();
          for (Entry<UUID, List<Event<?>>> entry : eventsByEntityId.entrySet()) {
            UUID modelId = entityTypeIdByEntityId.get(entry.getKey());
            EntityType entityType = entityTypes.get(modelId);
            eventLogs.add(new EventLog(entityType, entry.getKey(), unmodifiableList(entry.getValue())));
          }
          return eventLogs;
        });
  }

  private record ModelEvent(UUID entityId, UUID entityTypeId, Event<?> event) {}

}
