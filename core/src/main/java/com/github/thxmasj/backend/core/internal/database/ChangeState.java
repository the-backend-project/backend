package com.github.thxmasj.backend.core.internal.database;

import static java.util.Objects.requireNonNullElse;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.IdEntityType;
import com.github.thxmasj.backend.core.StoredEvent;
import com.github.thxmasj.backend.core.TriggerType;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.DatabaseClient.PrimaryKeyConstraintViolation;
import com.github.thxmasj.backend.core.database.DatabaseClient.Query.Builder;
import com.github.thxmasj.backend.core.database.DatabaseClient.UniqueIndexConstraintViolation;
import com.github.thxmasj.backend.core.database.TableType;
import com.github.thxmasj.backend.core.internal.DelayedEvent;
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
    StoredEvent<?> event = change.newEvent();
    if (change.entityId() != null)
      spec.bind("entityId", change.entityId());
    spec.bind("entityTypeId", change.entityType().id());
    spec.bind("timestamp", timestamp.withZoneSameInstant(clock.getZone()).toLocalDateTime());
    if (event != null) {
      spec.bind("eventNumber", event.eventNumber())
          .bind("type", event.type().id())
          .bindNullable("data", byte[].class, event.data());
    }
    if (change.firedTrigger() != null) {
      if (change.firedTrigger().type() == TriggerType.CONDITIONAL)
        spec.bind("triggerId", change.firedTrigger().id());
      else
        // The transaction a delayed trigger fires has the trigger's correlation id
        spec.bind("eventNumber", change.firedTrigger().eventNumber())
            .bind("firedType", change.firedTrigger().eventTypeId())
            .bind("correlationId", correlationId);
    }
    if (change.conditionalTrigger() != null) {
      spec
          .bind("conditionalTriggerId", change.conditionalTrigger().id())
          .bind("type", change.conditionalTrigger().type().id())
          .bindNullable("data", byte[].class, marshal(change.conditionalTrigger()))
          .bind("conditionEntityTypeId", change.conditionalTrigger().entityType().id())
          .bind("triggerEntityId", change.conditionalTrigger().triggerEvent().entityId())
          .bind("triggerEventNumber", change.conditionalTrigger().triggerEvent().eventNumber())
          .bind("lastTypes", change.conditionalTrigger().lastTypes().stream()
              .map(t -> t.id().toString())
              .collect(Collectors.joining(",")))
          .bind("referenceType", change.conditionalTrigger().referenceType().id())
          .bind("indexKey", DataType.uuid().marshal(change.conditionalTrigger().indexKey()))
          .bind("correlationId", change.conditionalTrigger().correlationId());
    }
    if (change.delayedEvent() != null) {
      ZonedDateTime deadline = change.delayedEvent().after();
      spec
          .bind("eventNumber", change.delayedEvent().eventNumber())
          .bind("type", change.delayedEvent().type().id())
          .bindNullable("data", byte[].class, marshal(change.delayedEvent()))
          .bind("deadline", deadline.withZoneSameInstant(clock.getZone()).toLocalDateTime())
          .bind("correlationId", correlationId);
    }
  }

  private static <T> byte[] marshal(DelayedEvent<T> delayedEvent) {
    return delayedEvent.type().inputDataType().marshal(delayedEvent.data());
  }

  private static <T> byte[] marshal(ConditionalTrigger<T> conditionalTrigger) {
    return conditionalTrigger.type().inputDataType().marshal(conditionalTrigger.data());
  }

  /// A change to store: a new event, as it is stored, a delayed or conditional trigger, or the deletion of a stored
  /// trigger which fired an event not stored
  ///
  /// @param newEvent           the new event as it is stored, or null
  /// @param delayedEvent       the delayed event to store, or null
  /// @param conditionalTrigger the conditional trigger to store, or null
  /// @param firedTrigger       the stored trigger this change deletes, as it fired an event not stored, or null
  public record Change(
      EntityType entityType,
      UUID entityId,
      StoredEvent<?> newEvent,
      DelayedEvent<?> delayedEvent,
      ConditionalTrigger<?> conditionalTrigger,
      FiredTrigger firedTrigger
  ) {

    /// A stored trigger which fired an event not stored, which is deleted by this change, as no event stored deletes it
    ///
    /// @param id          the id of a conditional trigger, or null for a delayed one
    /// @param eventNumber the number of the event it fired, which a delayed trigger has
    /// @param eventTypeId the type of the event it fired, by which a delayed trigger is told from one which replaced
    ///                    it with the same number
    public record FiredTrigger(TriggerType type, UUID id, int eventNumber, UUID eventTypeId) {}

    public static Change ofEvent(EntityType entityType, StoredEvent<?> newEvent) {
      return new Change(entityType, newEvent.entityId(), newEvent, null, null, null);
    }

    public static Change ofDelayedEvent(DelayedEvent<?> delayedEvent) {
      return new Change(delayedEvent.entityType(), delayedEvent.entityId(), null, delayedEvent, null, null);
    }

    public static Change ofConditionalTrigger(ConditionalTrigger<?> conditionalTrigger) {
      return new Change(conditionalTrigger.entityType(), conditionalTrigger.entityId(), null, null, conditionalTrigger, null);
    }

    public static Change ofFiredTrigger(EntityType entityType, UUID entityId, FiredTrigger firedTrigger) {
      return new Change(entityType, entityId, null, null, null, firedTrigger);
    }

    /// The change without the data of its event, which may be a secret
    @Override
    public String toString() {
      if (newEvent != null)
        return entityType.name() + ":" + entityId + ":ev:[" + newEvent.typeName() + "]:" + newEvent.eventNumber();
      if (delayedEvent != null)
        return entityType.name() + ":" + entityId + ":ev:[" + delayedEvent.type().name() + "]:" + delayedEvent.eventNumber() + " after " + delayedEvent.after();
      if (conditionalTrigger != null)
        return entityType.name() + ":" + entityId + ":trigger(conditional):[" + conditionalTrigger.type().name() + "]";
      return entityType.name() + ":" + entityId + ":fired(" + firedTrigger.type() + "):" + firedTrigger.eventNumber();
    }

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
  protected abstract String duplicateKey(StoredEvent<?> idEvent);

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
              .filter(change -> change.entityType() instanceof IdEntityType && change.newEvent() != null)
              .<StoredEvent<?>>map(Change::newEvent)
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
          .filter(change -> change.newEvent() != null
              && entityId.equals(change.entityId())
              && change.newEvent().eventNumber() == eventNumber
              && tableType.stores(change.entityType()))
          .findFirst()
          .map(change -> new EventAlreadyExists(change, changes, violation))
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
