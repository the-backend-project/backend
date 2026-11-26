package com.github.thxmasj.backend.database.pgsql.internal;

import static com.github.thxmasj.backend.database.pgsql.internal.Names.table;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.TriggerType;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.TableType;
import com.github.thxmasj.backend.core.internal.DelayedEvent;
import com.github.thxmasj.backend.core.internal.database.ChangeState.Change.FiredTrigger;
import com.github.thxmasj.backend.core.internal.database.ConditionalTrigger;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

/// Stores the changes in a single round trip without an explicit transaction. PostgreSQL then executes them in a
/// single implicit transaction, which is rolled back if any of them fails.
@SuppressWarnings("StringConcatenationInLoop")
class ChangeState extends com.github.thxmasj.backend.core.internal.database.ChangeState {

  private final String schema;

  ChangeState(DatabaseClient databaseClient, String schema, Clock clock) {
    super(databaseClient, clock);
    this.schema = schema;
  }

  @Override
  protected String sql(List<Change> changes) {
    return IntStream.range(0, changes.size()).mapToObj(i -> insertSql("p" + i + "_", changes.get(i))).collect(joining("\n"));
  }

  /// The value the deletion of the trigger which fired a transaction fails to cast if it deletes nothing
  private static final String TRIGGER_FIRED_ELSEWHERE = "TriggerFiredElsewhere";

  /// PostgreSQL's error of the failed cast, whose message has the value it failed to cast. Its quotes are those of the
  /// server's language, so only the value is looked for, in the database's error, which doesn't have the SQL. Every
  /// client of PostgreSQL gives its error as the innermost cause.
  @Override
  protected boolean isTriggerFiredElsewhere(Throwable failure) {
    String message = databaseError(failure).getMessage();
    return message != null && message.contains(TRIGGER_FIRED_ELSEWHERE);
  }

  /// The duplicate key of an id event, as reported by PostgreSQL, e.g. "f721bf54-891b-41a0-aa63-5d3cbde52437, \x7b22..."
  @Override
  protected String duplicateKey(Event<?> idEvent) {
    return idEvent.type().id() + ", \\x" + HexFormat.of().formatHex(idEvent.storedData());
  }

  private String insertSql(
      String parameterPrefix,
      Change change
  ) {
    EntityModel entityModel = change.entityModel();
    Event<?> event = change.newEvent();
    DelayedEvent<?> delayedEvent = change.delayedEvent();
    ConditionalTrigger<?> conditionalTrigger = change.conditionalTrigger();
    FiredTrigger firedTrigger = change.firedTrigger();
    String sql = "";
    if (event != null && (entityModel instanceof IndexEntityModel || entityModel instanceof IdEntityModel)) {
      sql +=
          """
          INSERT INTO {table} (
            EntityId,
            EventNumber,
            Type,
            Timestamp,
            Data
          ) VALUES (
            :entityId,
            :eventNumber,:type,:timestamp,:data
          );
          """.replace("{table}", table(schema, Tables.table(TableType.of(entityModel))));
    } else if (event != null) {
      if (event.eventNumber() == 1) {
        sql +=
            """
            INSERT INTO {Entity} (
              EntityId,
              EntityModelId
            ) VALUES (
              :entityId,
              :entityModelId
            );
            """.replace("{Entity}", table(schema, "Entity"));
      }
      // A delayed trigger holds the number its event would get, so it is stale once that number is taken
      sql +=
          """
          DELETE FROM {DelayedTrigger}
          WHERE EntityId=:entityId AND EventNumber<=:eventNumber;
          """.replace("{DelayedTrigger}", table(schema, "DelayedTrigger"));
      sql +=
          """
          INSERT INTO {Event} (
            EntityId,
            EventNumber,
            Type,
            Timestamp,
            Data
          ) VALUES (
            :entityId,
            :eventNumber,:type,:timestamp,:data
          );
          """.replace("{Event}", table(schema, "Event"));
    }

    if (firedTrigger != null) {
      // The trigger fired an event which isn't stored, which would otherwise have deleted it. If it is gone, another
      // transaction fired it first, e.g. of another instance which picked it too, so this one fails, as the event it
      // fired would otherwise be taken twice. A delayed trigger is the one of its number, type and correlation id, as
      // one which replaced it may have its number. The statement fails by inserting a row only if nothing was deleted, with a
      // cast which fails with the marker, as a statement of its own, which every client runs without reading a result.
      // The cast depends on the deletion, so it isn't folded, and so failed, when the statement is planned.
      sql += firedTrigger.type() == TriggerType.DELAYED ?
          """
          WITH deleted AS (
            DELETE FROM {Table} WHERE EntityId=:entityId AND EventNumber=:eventNumber AND Type=:firedType AND CorrelationId=:correlationId RETURNING 1
          )
          INSERT INTO {Table} (EntityId)
          SELECT CAST('{marker}' || (SELECT COUNT(*) FROM deleted) AS UUID) WHERE NOT EXISTS (SELECT 1 FROM deleted);
          """.replace("{Table}", table(schema, "DelayedTrigger")).replace("{marker}", TRIGGER_FIRED_ELSEWHERE) :
          """
          WITH deleted AS (
            DELETE FROM {Table} WHERE Id=:triggerId RETURNING 1
          )
          INSERT INTO {Table} (Id)
          SELECT CAST('{marker}' || (SELECT COUNT(*) FROM deleted) AS UUID) WHERE NOT EXISTS (SELECT 1 FROM deleted);
          """.replace("{Table}", table(schema, "ConditionalTrigger")).replace("{marker}", TRIGGER_FIRED_ELSEWHERE);
    }

    if (conditionalTrigger != null) {
      sql +=
          """
          INSERT INTO {ConditionalTrigger} (
            Id,
            EntityId,
            EntityModelId,
            TriggerEntityId,
            TriggerEventNumber,
            Type,
            Data,
            Timestamp,
            LastTypes,
            ReferenceType,
            IndexKey,
            CorrelationId
          ) VALUES (
            :conditionalTriggerId,
            :entityId,
            :conditionEntityModelId,
            :triggerEntityId,
            :triggerEventNumber,
            :type,:data,:timestamp,:lastTypes,:referenceType,:indexKey,:correlationId
          );
          """.replace("{ConditionalTrigger}", table(schema, "ConditionalTrigger"));
    }

    if (delayedEvent != null) {
      // Replace the previous delayed trigger, which may not have been deleted yet by the new event's change. One of a
      // transition on an event not stored has the number of the pending one, which it replaces. The state machine leaves out
      // a delayed trigger of the transaction which a later event stored on the entity replaces, and refuses two with the
      // same number, so this only deletes those of earlier transactions.
      sql +=
          """
          DELETE FROM {DelayedTrigger}
          WHERE EntityId=:entityId AND EventNumber<=:eventNumber;
          """.replace("{DelayedTrigger}", table(schema, "DelayedTrigger"));
      sql +=
          """
          INSERT INTO {DelayedTrigger} (
            EntityId,
            EntityModelId,
            EventNumber,
            Type,
            Data,
            Deadline,
            CorrelationId,
            Attempt
          ) VALUES (
            :entityId,
            :entityModelId,
            :eventNumber,
            :type,
            :data,
            :deadline,
            :correlationId,
            0
          );
          """.replace("{DelayedTrigger}", table(schema, "DelayedTrigger"));
    }
    return sql.replaceAll(":([a-zA-Z0-9_]+)", ":" + parameterPrefix + "$1");
  }

}
