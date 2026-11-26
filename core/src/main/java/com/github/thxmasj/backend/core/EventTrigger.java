package com.github.thxmasj.backend.core;

import com.github.thxmasj.backend.core.EntitySelector.ById;
import com.github.thxmasj.backend.core.EntitySelector.CreationMode;
import com.github.thxmasj.backend.core.EventTrigger.ByConditional;
import com.github.thxmasj.backend.core.EventTrigger.ByDelayed;
import com.github.thxmasj.backend.core.EventTrigger.Conditional;
import com.github.thxmasj.backend.core.EventTrigger.Immediate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.idEvent;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;

/// Triggers an event of a type on an entity, with the data of the event and the entity selected from the input of the
/// trigger, and with the correlation id of the transaction the trigger starts, if it starts one:
///
/// - [Immediate] triggers the event at once, given to [StateMachine#onEvent(Immediate, Object)] or triggered by a
///   transition;
/// - [Conditional] is a trigger of a transition which is stored, and fires once its condition is met;
/// - [ByDelayed] and [ByConditional] fire a stored delayed or conditional trigger, with the correlation id of the
///   transaction which stored it.
///
/// An entity selector may have a fallback, e.g. [EntitySelector#idEvent(EventType, Object, EntitySelector)], which
/// selects the entity if the selector finds none.
public abstract sealed class EventTrigger<T, I, O> permits Immediate, Conditional, ByDelayed, ByConditional {

  public record EventCondition<T>(
      EventType<?, ?> referenceType,
      Function<T, UUID> indexKey,
      List<EventType<?, ?>> lastTypes
  ) {}

  private final EventType<I, O> eventType;
  private final Function<T, I> data;
  private final Function<T, ? extends EntitySelector> entitySelector;
  private final EntityModel entityModel;
  private final Function<T, String> correlationId;

  private EventTrigger(
      EventType<I, O> eventType,
      Function<T, I> data,
      Function<T, ? extends EntitySelector> entitySelector,
      EntityModel entityModel,
      Function<T, String> correlationId
  ) {
    this.eventType = eventType;
    this.data = data;
    this.entitySelector = entitySelector;
    this.entityModel = entityModel;
    this.correlationId = correlationId;
  }

  public static <I, O> Immediate<I, I, O> trigger(EventType<I, O> eventType, EntityModel entityModel, UUID entityId, I data) {
    return new Immediate<>(eventType, _ -> data, _ -> entityId(entityId), entityModel);
  }

  public static <I, O> Immediate<I, I, O> trigger(EventType<I, O> eventType, EntityModel entityModel) {
    return new Immediate<>(eventType, d -> d, newEntityId(), entityModel);
  }

  public static <I, O> Immediate<I, I, O> trigger(EventType<I, O> eventType, EntityModel entityModel, UUID entityId) {
    return new Immediate<>(eventType, d -> d, _ -> entityId(entityId), entityModel);
  }

  public static <I, O, ID> Immediate<I, I, O> trigger(EventType<I, O> eventType, EntityModel entityModel, EventType<?, ID> idType, ID id) {
    return new Immediate<>(eventType, d -> d, _ -> idEvent(idType, id), entityModel);
  }

  public EventType<I, O> eventType() {
    return eventType;
  }

  /// The data of the event, of the input of the trigger, or null if the event has no data
  public Function<T, I> data() {
    return data;
  }

  /// The entity the event is triggered on, of the input of the trigger
  public Function<T, ? extends EntitySelector> entitySelector() {
    return entitySelector;
  }

  public EntityModel entityModel() {
    return entityModel;
  }

  /// The correlation id of the transaction the trigger starts with the input, or a random UUID if the trigger has no
  /// function for it. The event of a trigger of a transition is in the transaction of the transition, with its
  /// correlation id.
  public String correlationId(T input) {
    return correlationId == null ? UUID.randomUUID().toString() : correlationId.apply(input);
  }

  /// This trigger with the data of the event given by the function
  abstract EventTrigger<T, I, O> withData(Function<T, I> data);

  /// Triggers the event at once, given to [StateMachine#onEvent(Immediate, Object)] or triggered by a transition
  public static final class Immediate<T, I, O> extends EventTrigger<T, I, O> {

    public Immediate(
        EventType<I, O> eventType,
        Function<T, I> data,
        Function<T, ? extends EntitySelector> entitySelector,
        EntityModel entityModel
    ) {
      this(eventType, data, entitySelector, entityModel, null);
    }

    private Immediate(
        EventType<I, O> eventType,
        Function<T, I> data,
        Function<T, ? extends EntitySelector> entitySelector,
        EntityModel entityModel,
        Function<T, String> correlationId
    ) {
      super(eventType, data, entitySelector, entityModel, correlationId);
    }

    /// This trigger with the correlation id of the transaction it starts taken from its input, e.g. from a header of a
    /// received request. The function is applied once for each call of [StateMachine#onEvent(Immediate, Object)], so
    /// a correlation id it generates, e.g. for a request without the header, is the same in the attempts of the call.
    /// Without the function, each call has a random UUID.
    public Immediate<T, I, O> withCorrelationId(Function<T, String> correlationId) {
      return new Immediate<>(eventType(), data(), entitySelector(), entityModel(), correlationId);
    }

    /// This trigger with the given correlation id of the transaction it starts
    public Immediate<T, I, O> withCorrelationId(String correlationId) {
      return withCorrelationId(_ -> correlationId);
    }

    @Override
    Immediate<T, I, O> withData(Function<T, I> data) {
      return new Immediate<>(eventType(), data, entitySelector(), entityModel(), ((EventTrigger<T, I, O>) this).correlationId);
    }

  }

  /// A trigger of a transition which is stored, with the correlation id of the transaction, and fires as a
  /// [ByConditional] once its condition is met
  public static final class Conditional<T, I, O> extends EventTrigger<T, I, O> {

    private final EventCondition<T> condition;

    public Conditional(
        EventType<I, O> eventType,
        Function<T, I> data,
        Function<T, ? extends EntitySelector> entitySelector,
        EntityModel entityModel,
        EventCondition<T> condition
    ) {
      super(eventType, data, entitySelector, entityModel, null);
      this.condition = condition;
    }

    public EventCondition<T> condition() {
      return condition;
    }

    @Override
    Conditional<T, I, O> withData(Function<T, I> data) {
      return new Conditional<>(eventType(), data, entitySelector(), entityModel(), condition);
    }

  }

  /// Fires a stored delayed trigger on its entity, with the correlation id of the transaction which stored it. The
  /// event deletes the trigger.
  public static final class ByDelayed<I, O> extends EventTrigger<Void, I, O> {

    ByDelayed(EventType<I, O> eventType, I data, UUID entityId, EntityModel entityModel, String correlationId) {
      super(eventType, _ -> data, _ -> entityId(entityId), entityModel, _ -> correlationId);
    }

    @Override
    ByDelayed<I, O> withData(Function<Void, I> data) {
      throw new UnsupportedOperationException("The data of a delayed trigger is stored with it");
    }

  }

  /// Fires a stored conditional trigger whose condition is met, with the correlation id of the transaction which
  /// stored it. The event deletes the trigger. The entity is created by the event if it doesn't exist yet.
  public static final class ByConditional<I, O> extends EventTrigger<Void, I, O> {

    private final UUID id;

    ByConditional(UUID id, EventType<I, O> eventType, I data, UUID entityId, EntityModel entityModel, String correlationId) {
      super(eventType, _ -> data, _ -> new ById(entityId, CreationMode.CreateIfNotExists), entityModel, _ -> correlationId);
      this.id = id;
    }

    /// The id of the stored trigger
    public UUID id() {
      return id;
    }

    @Override
    ByConditional<I, O> withData(Function<Void, I> data) {
      throw new UnsupportedOperationException("The data of a conditional trigger is stored with it");
    }

  }

}
