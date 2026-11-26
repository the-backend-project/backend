package com.github.thxmasj.backend.database.mssql.internal;

import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.TableType;
import com.github.thxmasj.backend.core.internal.ResolvedModels;
import com.github.thxmasj.backend.core.internal.database.ConditionalTriggers;
import com.github.thxmasj.backend.core.internal.database.DeleteDelayedTrigger;
import com.github.thxmasj.backend.core.internal.database.EntitiesByIndex;
import com.github.thxmasj.backend.core.internal.database.EventsByEntityId;
import com.github.thxmasj.backend.core.internal.database.EventsByIdEvent;
import com.github.thxmasj.backend.core.internal.database.EventsByLastIndexEvent;
import com.github.thxmasj.backend.core.internal.database.NextDelayedTrigger;
import com.github.thxmasj.backend.core.internal.database.Storage;
import com.github.thxmasj.backend.core.internal.database.StorageProvider;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/// The storage of SQL Server
public class MssqlStorage implements StorageProvider {

  @Override
  public String database() {
    return "mssql";
  }

  @Override
  public Storage storage(
      DatabaseClient client,
      ResolvedModels models,
      String schemaName,
      Clock clock
  ) {
    List<EntityType> entityTypes = models.entityTypes();
    Map<EntityType, List<EventType<?, ?>>> eventTypesByEntityType = models.eventTypesByEntityType();
    return new Storage(
        new ChangeState(client, schemaName, clock),
        new EventsByEntityId(client, entityType -> Queries.eventsByEntityId(schemaName, Tables.table(TableType.of(entityType))), entityTypes, clock),
        new EventsByIdEvent(client, Queries.eventsByIdEvent(schemaName), eventTypesByEntityType, clock),
        new EventsByLastIndexEvent(client, Queries.eventsByLastIndexEvent(schemaName), clock),
        new EntitiesByIndex(client, Queries.entitiesByIndex(schemaName), eventTypesByEntityType, clock),
        new NextDelayedTrigger(client, Queries.nextDelayedTrigger(schemaName), models.eventTypes(), clock, entityTypes),
        new DeleteDelayedTrigger(client, Queries.deleteDelayedTrigger(schemaName)),
        new ConditionalTriggers(client, Queries.conditionalTriggers(schemaName), eventTypesByEntityType, clock)
    );
  }
}
