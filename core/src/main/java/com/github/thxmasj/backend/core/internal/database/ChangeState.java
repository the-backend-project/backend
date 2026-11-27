package com.github.thxmasj.backend.core.internal.database;

import static java.util.Objects.requireNonNullElse;

import com.github.thxmasj.backend.core.Change;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.IdEntityType;
import com.github.thxmasj.backend.core.TriggerType;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.DatabaseClient.PrimaryKeyConstraintViolation;
import com.github.thxmasj.backend.core.database.DatabaseClient.Query.Builder;
import com.github.thxmasj.backend.core.database.DatabaseClient.UniqueIndexConstraintViolation;
import com.github.thxmasj.backend.core.database.TableType;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/// Stores the changes of a transition atomically
public abstract class ChangeState {

  private final DatabaseClient databaseClient;
  private final Clock clock;

  protected ChangeState(DatabaseClient databaseClient, Clock clock) {
    this.databaseClient = databaseClient;
    this.clock = clock;
  }

  private void bind(Builder spec, ZonedDateTime timestamp, String correlationId, Change change) {
    if (change.entityId() != null)
      spec.bind("entityId", change.entityId());
    spec.bind("entityTypeId", change.entityType().id());
    spec.bind("timestamp", timestamp.withZoneSameInstant(clock.getZone()).toLocalDateTime());
    switch (change) {
      case Change.Event<?> event -> spec
          .bind("eventNumber", event.eventNumber())
          .bind("type", event.type().id())
          .bindNullable("data", byte[].class, event.data());
      case Change.FiredTrigger fired -> {
        if (fired.type() == TriggerType.CONDITIONAL)
          spec.bind("triggerId", fired.id());
        else
          // The transaction a delayed trigger fires has the trigger's correlation id
          spec.bind("eventNumber", fired.eventNumber())
              .bind("firedType", fired.eventTypeId())
              .bind("correlationId", correlationId);
      }
      case Change.ConditionalTrigger<?> conditional -> spec
          .bind("conditionalTriggerId", conditional.id())
          .bind("type", conditional.type().id())
          .bindNullable("data", byte[].class, marshal(conditional))
          .bind("conditionEntityTypeId", conditional.entityType().id())
          .bind("triggerEntityId", conditional.triggerEvent().entityId())
          .bind("triggerEventNumber", conditional.triggerEvent().eventNumber())
          .bind("lastTypes", conditional.lastTypes().stream()
              .map(t -> t.id().toString())
              .collect(Collectors.joining(",")))
          .bind("referenceType", conditional.referenceType().id())
          .bind("indexKey", DataType.uuid().marshal(conditional.indexKey()))
          .bind("correlationId", conditional.correlationId());
      case Change.DelayedTrigger<?> delayed -> spec
          .bind("eventNumber", delayed.eventNumber())
          .bind("type", delayed.type().id())
          .bindNullable("data", byte[].class, marshal(delayed))
          .bind("deadline", delayed.deadline().withZoneSameInstant(clock.getZone()).toLocalDateTime())
          .bind("correlationId", correlationId);
    }
  }

  private static <T> byte[] marshal(Change.DelayedTrigger<T> delayedTrigger) {
    return delayedTrigger.type().inputDataType().marshal(delayedTrigger.data());
  }

  private static <T> byte[] marshal(Change.ConditionalTrigger<T> conditionalTrigger) {
    return conditionalTrigger.type().inputDataType().marshal(conditionalTrigger.data());
  }


  public record OutboxElement(int changeIndex, int messageIndex, UUID requestId, byte[] elementId) {}

  /// Stores the changes in one transaction. A transaction with nothing to store, e.g. of events which are not stored,
  /// doesn't reach the database.
  public Flux<OutboxElement> execute(ZonedDateTime timestamp, String correlationId, List<Change> changes) {
    if (changes.isEmpty())
      return Flux.empty();
    Builder spec = databaseClient.sql(sql(changes)).name("ChangeState");
    for (int i = 0; i < changes.size(); i++) {bind(spec.parameterPrefix("p" + i + "_"), timestamp, correlationId, changes.get(i));}
    return spec.map(row -> new OutboxElement(
            row.get("ChangeIndex", Integer.class),
            row.get("MessageIndex", Integer.class),
            row.get("RequestId", UUID.class),
            row.get("ElementId", byte[].class)
        ))
        .all()
        .onErrorMap(failure -> requireNonNullElse(conflict(failure, changes), failure))
        .onErrorMap(this::isTriggerFiredElsewhere, TriggerFiredElsewhere::new);
  }

  /// The SQL storing the changes in a single transaction, in the dialect of the database. Parameters are prefixed with
  /// the index of their change, as in "p0_". It selects the outbox elements to process, if any.
  protected abstract String sql(List<Change> changes);

  /// The key of an id event in its unique index, as reported by the database, i.e. its type and data
  protected abstract String duplicateKey(Change.Event<?> idEvent);

  /// The conflict with what another transaction stored which the failure to store the changes is, or null if it is
  /// none. A primary key violation on a table of entities or events (see [TableType]) is an [EventAlreadyExists] of the
  /// change whose event exists, and a violation of the unique index of the id events an [IdEventAlreadyExists] of the
  /// id event whose id is taken.
  private Throwable conflict(Throwable failure, List<Change> changes) {
    return switch (failure) {
      case PrimaryKeyConstraintViolation violation when violation.tableType() != null ->
          eventAlreadyExists(violation, changes);
      case UniqueIndexConstraintViolation violation when violation.tableType() == TableType.IdEvent ->
          changes.stream()
              .filter(change -> change.entityType() instanceof IdEntityType)
              .<Change.Event<?>>mapMulti((change, events) -> {
                if (change instanceof Change.Event<?> event)
                  events.accept(event);
              })
              .filter(event -> violation.duplicateKey().equalsIgnoreCase(duplicateKey(event)))
              .<Throwable>map(event -> new IdEventAlreadyExists(event, violation))
              .findFirst()
              .orElse(null);
      default -> null;
    };
  }

  /// The event which exists, of the change storing the event of the entity id and number of the key, of an entity
  /// type whose events are stored in a table of the type of the violation's. The id and index entities of an entity have its entity id,
  /// so where it is tells them apart.
  private static EventAlreadyExists eventAlreadyExists(
      PrimaryKeyConstraintViolation violation,
      List<Change> changes
  ) {
    TableType tableType = violation.tableType();
    try {
      String[] key = violation.duplicateKey().split(",");
      UUID entityId = UUID.fromString(key[0].trim());
      int eventNumber = tableType == TableType.Entity ? 1 : Integer.parseInt(key[1].trim());
      return changes.stream()
          .<Change.Event<?>>mapMulti((change, events) -> {
            if (change instanceof Change.Event<?> event)
              events.accept(event);
          })
          .filter(event -> entityId.equals(event.entityId())
              && event.eventNumber() == eventNumber
              && tableType.stores(event.entityType()))
          .findFirst()
          .map(event -> new EventAlreadyExists(event, changes, violation))
          .orElseGet(() -> new EventAlreadyExists(violation));
    } catch (RuntimeException _) {
      return new EventAlreadyExists(violation);
    }
  }

  /// Whether the failure is the one the store's SQL fails with when the deletion of the trigger which fired the
  /// transaction deletes nothing, as another transaction fired it first (see [TriggerFiredElsewhere])
  protected abstract boolean isTriggerFiredElsewhere(Throwable failure);

  /// The innermost cause of the failure, the error of the database itself, rather than the messages of the clients
  /// around it, which have the SQL
  protected static Throwable databaseError(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null && root.getCause() != root)
      root = root.getCause();
    return root;
  }

}
