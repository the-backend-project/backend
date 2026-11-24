package com.github.thxmasj.backend.database.mssql.internal;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.TriggerType;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.internal.DelayedEvent;
import com.github.thxmasj.backend.core.internal.database.ChangeState.Change.FiredTrigger;
import com.github.thxmasj.backend.core.internal.database.ConditionalTrigger;
import com.github.thxmasj.backend.core.internal.database.TriggerFiredElsewhere;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

/// Stores the changes in a single batch with an explicit transaction
class ChangeState extends com.github.thxmasj.backend.core.internal.database.ChangeState {

  private final String schema;

  ChangeState(DatabaseClient databaseClient, String schema, Clock clock) {
    super(databaseClient, clock);
    this.schema = schema;
  }

  @Override
  protected String sql(List<Change> changes) {
    return
        """
        SET XACT_ABORT ON;
        BEGIN TRANSACTION;
        DECLARE @OutboxElement         TABLE (ChangeIndex TINYINT, MessageIndex TINYINT, RequestId BINARY(16));
        DECLARE @QueueElement          TABLE (ChangeIndex TINYINT, MessageIndex TINYINT, RequestId BINARY(16), ElementId BINARY(8), Guaranteed BIT);
        DECLARE @QueueElementToProcess TABLE (ChangeIndex TINYINT, MessageIndex TINYINT, RequestId BINARY(16), ElementId BINARY(8));
        """ + IntStream.range(0, changes.size()).mapToObj(i -> insertSql(i, "p" + i + "_", changes.get(i))).collect(joining("\n")) +
            """
            INSERT INTO @QueueElementToProcess (ChangeIndex, MessageIndex, RequestId, ElementId)
            SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElement
            WHERE Guaranteed = 0;
            
            SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElementToProcess ORDER BY ChangeIndex ASC, MessageIndex ASC
            COMMIT TRANSACTION;
            """;
  }

  private String insertSql(
      int changeIndex,
      String parameterPrefix,
      Change change
  ) {
    EntityModel entityModel = change.entityModel();
    Event<?> event = change.newEvent();
    DelayedEvent<?> delayedEvent = change.delayedEvent();
    ConditionalTrigger<?> conditionalTrigger = change.conditionalTrigger();
    FiredTrigger firedTrigger = change.firedTrigger();
    String sql =
        """
        DECLARE @entityId{changeIndex} BINARY(16)
        SELECT @entityId{changeIndex}=:entityId
        """.replace("{changeIndex}", String.valueOf(changeIndex));
    if (event != null && (entityModel instanceof IndexEntityModel || entityModel instanceof IdEntityModel)) {
      sql +=
          """
          INSERT INTO [{schema}].[{table}] (
            EntityId,
            EventNumber,
            Type,
            Timestamp,
            Data
          ) VALUES (
            @entityId{changeIndex},
            :eventNumber,:type,:timestamp,:data
          )
          """.replace("{schema}", schema)
              .replace("{table}", entityModel instanceof IdEntityModel ? "IdEvent" : "IndexEvent")
              .replace("{changeIndex}", String.valueOf(changeIndex));
    } else if (event != null) {
      if (event.eventNumber() == 1) {
        sql +=
            """
            INSERT INTO [{schema}].[Entity] (
              EntityId,
              EntityModelId
            ) VALUES (
              @entityId{changeIndex},
              :entityModelId
            );
            """.replace("{schema}", schema)
                .replace("{changeIndex}", String.valueOf(changeIndex));
      }
      // A delayed trigger holds the number its event would get, so it is stale once that number is taken
      sql +=
          """
          DELETE [{schema}].[DelayedTrigger] FROM [{schema}].[DelayedTrigger] WITH (INDEX([ixEntityId]))
          WHERE EntityId=@entityId{changeIndex} AND EventNumber<=:eventNumber;
          """.replace("{changeIndex}", String.valueOf(changeIndex))
              .replace("{schema}", schema);
      sql +=
          """
          INSERT INTO [{schema}].[Event] (
            EntityId,
            EventNumber,
            Type,
            Timestamp,
            Data
          ) VALUES (
            @entityId{changeIndex},
            :eventNumber,:type,:timestamp,:data
          )
          """.replace("{schema}", schema)
              .replace("{changeIndex}", String.valueOf(changeIndex));
    }

    if (firedTrigger != null) {
      // The trigger fired an event which isn't stored, which would otherwise have deleted it. If it is gone, another
      // transaction fired it first, e.g. of another instance which picked it too, so this one fails, as the event it
      // fired would otherwise be taken twice.
      sql += (firedTrigger.type() == TriggerType.DELAYED ?
          """
          DELETE [{schema}].[DelayedTrigger] FROM [{schema}].[DelayedTrigger] WITH (INDEX([ixEntityId]))
          WHERE EntityId=@entityId{changeIndex} AND EventNumber<=:eventNumber;
          """.replace("{changeIndex}", String.valueOf(changeIndex)).replace("{schema}", schema) :
          """
          DELETE [{schema}].[ConditionalTrigger]
          WHERE Id=:triggerId;
          """.replace("{schema}", schema))
          + "IF @@ROWCOUNT = 0 THROW 50001, '" + TriggerFiredElsewhere.MARKER + "', 1;\n";
    }

    if (conditionalTrigger != null) {
      sql +=
          """
          INSERT INTO [{schema}].[ConditionalTrigger] (
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
            @entityId{changeIndex},
            :conditionEntityModelId,
            :triggerEntityId,
            :triggerEventNumber,
            :type,:data,:timestamp,:lastTypes,:referenceType,:indexKey,:correlationId
          );
          """.replace("{changeIndex}", String.valueOf(changeIndex))
              .replace("{schema}", schema);
    }

    if (delayedEvent != null) {
      // Replace the previous delayed trigger, which may not have been deleted yet by the new event's change. One of a
      // transition on an event not stored has the number of the pending one, which it replaces. The state machine leaves out
      // a delayed trigger of the transaction which a later event stored on the entity replaces, and refuses two with the
      // same number, so this only deletes those of earlier transactions.
      sql +=
          """
          DELETE [{schema}].[DelayedTrigger] FROM [{schema}].[DelayedTrigger] WITH (INDEX([ixEntityId]))
          WHERE EntityId=@entityId{changeIndex} AND EventNumber<=:eventNumber;
          """.replace("{changeIndex}", String.valueOf(changeIndex))
              .replace("{schema}", schema);
      sql +=
          """
          INSERT INTO [{schema}].[DelayedTrigger] (
            EntityId,
            EntityModelId,
            EventNumber,
            Type,
            Data,
            Deadline,
            CorrelationId,
            Attempt
          ) VALUES (
            @entityId{changeIndex},
            :entityModelId,
            :eventNumber,
            :type,
            :data,
            :deadline,
            :correlationId,
            0
          );
          """.replace("{changeIndex}", String.valueOf(changeIndex))
              .replace("{schema}", schema);
    }
    // Remove comments
    sql = sql.lines().map(line -> line.replaceAll("--.*", "")).collect(joining("\n")) + "\n";
    return sql.replaceAll(":([a-zA-Z0-9_]+)", ":" + parameterPrefix + "$1");
  }

  /// The duplicate key of an id event, as reported by SQL Server with its type as a UUID (see
  /// `MssqlClient.withUuid(String)`), e.g. "019a6b2c-1d3e-7f40-8a1b-2c3d4e5f6071, 0x7B22..."
  @Override
  protected String duplicateKey(Event<?> idEvent) {
    return idEvent.type().id() + ", 0x" + HexFormat.of().formatHex(idEvent.getMarshalledData());
  }

}
