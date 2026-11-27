package com.github.thxmasj.backend.core.internal.database;

import static java.util.Objects.requireNonNull;

import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.StoredEvent;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.MappingFailure;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import java.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public class EventsByEntityId {

  private final DatabaseClient client;
  private final Map<EntityType, String> sqls;
  private final Clock clock;

  /// @param sql the SQL selecting the events of an entity of an entity type, in the dialect of the database
  public EventsByEntityId(
      DatabaseClient client,
      Function<EntityType, String> sql,
      List<EntityType> entityTypes,
      Clock clock
  ) {
    this.client = client;
    this.clock = clock;
    this.sqls = new HashMap<>();
    for (var entityType : entityTypes)
      this.sqls.put(entityType, sql.apply(entityType));
  }

  public Mono<EventLog> execute(EntityType entityType, List<EventType<?, ?>> eventTypes, UUID entityId) {
    String sqlToPrepare = requireNonNull(sqls.get(entityType), "Unknown model " + entityType.name());
    var eventMapper = Mappers.eventMapper(eventTypes, clock);
    return client.sql(sqlToPrepare)
        .name("EventsByEntityId")
        .bind("entityId", entityId)
        .<StoredEvent<?>>map(row -> {
          try {
            return eventMapper.apply(entityId, row);
          } catch (MappingFailure e) {
            throw new MappingFailure("EventsByEntityId: Failed to map row to event for entity type " + entityType.name(), e);
          }
        })
        .all()
        .collectList()
        .map(events -> {
          if (events.isEmpty())
            throw new UnknownEntity(entityType, entityId);
          return new EventLog(entityType, entityId, Collections.unmodifiableList(events));
        });
  }

}
