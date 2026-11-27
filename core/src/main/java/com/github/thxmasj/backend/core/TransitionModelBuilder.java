package com.github.thxmasj.backend.core;

import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

import com.github.thxmasj.backend.core.EventType.Rollback.Data;
import com.github.thxmasj.backend.core.EventTrigger.EventCondition;
import com.github.thxmasj.backend.core.EventTrigger.Conditional;
import com.github.thxmasj.backend.core.EventTrigger.Immediate;
import com.github.thxmasj.backend.core.StateMachine.CircularChange;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.StateMachine.RejectedEvent;
import com.github.thxmasj.backend.core.TransitionModelBuilder.WithFilter.Alternative;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import com.github.thxmasj.backend.core.internal.ActionTrigger;
import com.github.thxmasj.backend.core.internal.Debug;
import com.github.thxmasj.backend.core.internal.DelayedEvent;
import com.github.thxmasj.backend.core.internal.TransitionStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.ActionStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.AssembleStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.ChoiceStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.ConditionalTriggerStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.DelayedTriggerStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.InputStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.OutputStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.PendingStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.RejectedStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.TriggerStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.UnknownIdStep;
import com.github.thxmasj.backend.core.internal.database.ConditionalTrigger;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Mono;

public class TransitionModelBuilder<I, T, O> {

  /// @param severity the severity of the event the transition accepts
  /// @param delayedEventTypes the types of the delayed events the transition triggers
  /// @param withoutData whether the transition's event has no data, as it ends in `output()` without a data adapter,
  ///                    which only an event type without data may
  public record ModelContext<I, O>(
      boolean reversal,
      State to,
      EventType<I, O> eventType,
      List<EventTrigger<?, ?, ?>> triggers,
      List<Action<?>> actions,
      List<Filter<?, ?, ?>> filters,
      TransitionModel<Data, Data> reverseModel,
      Severity severity,
      List<EventType<?, ?>> delayedEventTypes,
      boolean withoutData
  ) {

    /// The context of a transition on the event type to the state, or to itself if the state is null, with nothing
    /// declared yet
    static <I, O> ModelContext<I, O> of(boolean reversal, State to, EventType<I, O> eventType) {
      return new ModelContext<>(reversal, to, eventType, List.of(), List.of(), List.of(), null, Severity.Info, List.of(), false);
    }

    ModelContext<I, O> withTriggers(List<EventTrigger<?, ?, ?>> triggers) {
      return new ModelContext<>(reversal, to, eventType, triggers, actions, filters, reverseModel, severity, delayedEventTypes, withoutData);
    }

    ModelContext<I, O> withActions(List<Action<?>> actions) {
      return new ModelContext<>(reversal, to, eventType, triggers, actions, filters, reverseModel, severity, delayedEventTypes, withoutData);
    }

    ModelContext<I, O> withFilters(List<Filter<?, ?, ?>> filters) {
      return new ModelContext<>(reversal, to, eventType, triggers, actions, filters, reverseModel, severity, delayedEventTypes, withoutData);
    }

    ModelContext<I, O> withReverseModel(TransitionModel<Data, Data> reverseModel) {
      return new ModelContext<>(reversal, to, eventType, triggers, actions, filters, reverseModel, severity, delayedEventTypes, withoutData);
    }

    ModelContext<I, O> withSeverity(Severity severity) {
      return new ModelContext<>(reversal, to, eventType, triggers, actions, filters, reverseModel, severity, delayedEventTypes, withoutData);
    }

    ModelContext<I, O> asWithoutData() {
      return new ModelContext<>(reversal, to, eventType, triggers, actions, filters, reverseModel, severity, delayedEventTypes, true);
    }

    ModelContext<I, O> withDelayedEventType(EventType<?, ?> delayedEventType) {
      return new ModelContext<>(reversal, to, eventType, triggers, actions, filters, reverseModel, severity, join(delayedEventTypes, delayedEventType), withoutData);
    }

    public String description() {
      if (reversal)
        return "<reversal>";
      return String.format("%s -> %s", eventType.name(), to != null ? to.name() : "<self>");
    }

  }

  /// Sealed, as [#entityId()] must only give the id of an entity the state machine has read or created
  public sealed interface TransitionContext<I> permits TransitionStep.InputStep {
    EventReference triggerEvent();
    State from();
    ZonedDateTime timestamp();
    String correlationId();
    EventLog log();
    I input();
    /// The id of the entity the transition is on, for creating an entity with the same id, e.g. its index entity
    default EntityId entityId() {
      return EntityId.of(log().entityId());
    }
    default EventReference eventReference() {
      return new EventReference(log().entityId(), log().lastEventNumber() + 1);
    }

  }


  public static TransitionModel<Void, State> statusOn() {
    return new TransitionModel<>(
        ModelContext.of(false, null, BuiltinEventTypes.StatusLookedUp),
        inputStep -> inputStep.flatMap(i -> step(i, () -> Mono.just(new OutputStep<>(
            i,
            ProcessResult.accepted(
                new Event<>(
                    i.log().entityId(),
                    i.log().lastEventNumber() + 1,
                    BuiltinEventTypes.StatusLookedUp,
                    i.timestamp(),
                    i.from()
                ),
                i.log().entityType(),
                Severity.Info
            )
        ))))
    );
  }

  public static TransitionModel<Data, Data> rollbackOn(EventType<Data, Data> eventType) {
    return new TransitionModel<>(
        ModelContext.of(false, null, eventType),
        inputStep -> inputStep.flatMap(i -> {
          int rollbackTo = i.stepOutput().toNumber();
          int actualRollbackTo = rollbackTo;
          if (rollbackTo < 0) {
            actualRollbackTo = i.log().lastEventNumber() + rollbackTo; // add negative
          }
          if (
            // Rollback to future event
              actualRollbackTo >= i.log().lastEventNumber()
                  // Rollback to before start
                  || actualRollbackTo < 0
                  // Rollback more than one request
                  || i.log().events().subList(actualRollbackTo, i.log().events().size()).stream().filter(e -> e.type() instanceof EventType.Request<?,?>).count() > 1
                  // Rollback from an event which is not the last
                  //|| rollbackFrom != i.log().lastEventNumber()
          ) {
            int invalidRollbackTo = actualRollbackTo;
            return step(i, () -> Mono.just(new OutputStep<>(
                i,
                ProcessResult.rejected(
                    eventType,
                    new RejectedEvent(
                        eventType,
                        i.log().entityType(),
                        i.log().entityId(),
                        i.from(),
                        invalidRollbackTo
                    )
                )
            )));
          }
          EventLog log = i.log();
          var effectiveEvents = log.effectiveEvents();
          List<EventLog.Entry<?>> eventsToRollback = effectiveEvents.subList(actualRollbackTo, effectiveEvents.size());
          Traverser traverser = i.stateMachine().traverser(log.entityType());
          Mono<TransitionStep<?>> currentContext = Mono.just(i);
          for (var event : eventsToRollback.reversed()) {
            var tm = traverser.transitionForEventNumber(log, event.eventNumber());
            if (tm == null) {
              throw new IllegalStateException(String.format(
                  "No transition found for event number %d",
                  event.eventNumber()
              ));
            }
            if (tm.reverseModel() != null) {
              currentContext = currentContext.flatMap(c -> tm.reverseModel().calculateReverse(
                  step(c, () -> Mono.just(new InputStep<>(
                      null,
                      c,
                      null,
                      tm.reverseModel(),
                      i.from(),
                      event.eventNumber(),
                      i.log(),
                      i.timestamp(),
                      i.correlationId(),
                      i.stepOutput(),
                      i.stateMachine(),
                      i.transaction()
                  )))
              ));
            }
          }
          return currentContext.flatMap(c -> step(c, () -> Mono.just(TransitionModel.withStoredForm(new OutputStep<>(
              c,
              ProcessResult.accepted(
                  new Event<>(
                      i.log().entityId(),
                      i.log().lastEventNumber() + 1,
                      eventType,
                      i.timestamp(),
                      i.stepOutput()
                  ),
                  i.log().entityType(),
                  Severity.Info
              )
          ), null))));
        })
    );
  }

  public static final class WithEvent<I, O> {
    private EventType<I, O> eventType;

    public static <I, O> WithEvent<I, O> onEvent(EventType<I, O> eventType) {
      var b = new WithEvent<I, O>();
      b.eventType = eventType;
      return b;
    }

    public WithToState<I, O> to(State toState) {
      return new WithToState<>(
          ModelContext.of(false, toState, eventType)
      );
    }

    public WithToState<I, O> toSelf() {
      return to(null);
    }

  }

  public static final class WithToState<I, O> {

    private final ModelContext<I, O> modelContext;

    public WithToState(ModelContext<I, O> modelContext) {
      this.modelContext = modelContext;
    }

    /// Declares the severity of the event the transition accepts, for monitoring. It is [Severity#Info] unless
    /// declared. A transition which chooses another one accepts the event of the chosen transition, with the
    /// severity declared on that one.
    public WithToState<I, O> severity(Severity severity) {
      return new WithToState<>(modelContext.withSeverity(requireNonNull(severity, "severity is null")));
    }

    public <T> TransitionModelBuilder<I, T, O> assemble(BiFunction<I, EventLog, T> assembler) {
      return new TransitionModelBuilder<>(
          modelContext,
          inputStep -> inputStep.flatMap(c -> step(c, () -> Mono.just(new AssembleStep<>(
                  c,
                  assembler.apply(c.stepOutput(), c.log())
              )
          )))
      );
    }

    public <T> TransitionModelBuilder<I, T, O> assemble(Function<TransitionContext<I>, T> assembler) {
      return new TransitionModelBuilder<>(
          modelContext,
          inputStep -> inputStep.flatMap(i -> step(i, () -> Mono.just(new AssembleStep<>(i, assembler.apply(i)))))
      );
    }

    public <T> TransitionModelBuilder<I, T, O> assembleReactive(Function<TransitionContext<I>, Mono<T>> assembler) {
      return new TransitionModelBuilder<>(
          modelContext,
          inputStep -> inputStep.flatMap(i -> step(i, () -> assembler.apply(i).map(t -> new AssembleStep<>(i, t))))
      );
    }

    public TransitionModelBuilder<I, I, O> assembleInput() {
      return new TransitionModelBuilder<>(
          modelContext,
          inputStep -> inputStep.flatMap(i -> step(i, () -> Mono.just(new AssembleStep<>(i, i.input()))))
      );
    }

    public <I1, O1> WithEventType<I, Void, O, I1, O1> trigger(EventType<I1, O1> eventType) {
      return new WithEventType<>(
          new TransitionModelBuilder<>(
              modelContext,
              inputStep -> inputStep.flatMap(i -> step(i, () -> Mono.just(new AssembleStep<>(i, null))))
          ),
          eventType
      );
    }

    public TransitionModel<I, O> output() {
      return new TransitionModelBuilder<>(
          modelContext,
          inputStep -> inputStep.flatMap(cc -> step(cc, () -> Mono.just(new AssembleStep<>(cc, null))))
      ).output();
    }

  }

  private final ModelContext<I, O> modelContext;
  private final Function<Mono<InputStep<I>>, Mono<TransitionStep<T>>> builderFunction;

  private TransitionModelBuilder(
      ModelContext<I, O> modelContext,
      Function<Mono<InputStep<I>>, Mono<TransitionStep<T>>> builderFunction
  ) {
    this.modelContext = modelContext;
    this.builderFunction = builderFunction;
  }

  public record WithEventType<I, T, O, I1, O1>(TransitionModelBuilder<I, T, O> builder, EventType<I1, O1> eventType) {

    public record WithCondition<I, T, O, I1, O1>(
        WithEventType<I, T, O, I1, O1> eventType,
        EventType<?, ?> referenceType,
        Function<T, UUID> indexKey
    ) {

      /// The condition is met when the last event of every referenced entity is one of the given types.
      public ConditionalWithEventType<I, T, O, I1, O1> is(EventType<?, ?>... lastTypes) {
        if (lastTypes.length == 0 || lastTypes.length > 10)
          throw new IllegalArgumentException("Between 1 and 10 last event types must be given");
        return new ConditionalWithEventType<>(
            eventType.builder(),
            eventType.eventType(),
            new EventCondition<>(referenceType, indexKey, List.of(lastTypes))
        );
      }

    }

    public record ConditionalWithEventType<I, T, O, I1, O1>(
        TransitionModelBuilder<I, T, O> builder,
        EventType<I1, O1> eventType,
        EventCondition<T> condition
    ) {

      public ConditionalWithEventTypeAndData<I, T, O, I1, O1> with(Function<T, I1> dataAdapter) {
        return new ConditionalWithEventTypeAndData<>(this, dataAdapter, null);
      }

      /// The data of the event is created reactively. It is created when the trigger is stored, not when its condition is
      /// met.
      public ConditionalWithEventTypeAndData<I, T, O, I1, O1> withReactive(Function<T, Mono<I1>> dataAdapter) {
        return new ConditionalWithEventTypeAndData<>(this, createdReactively(eventType), dataAdapter);
      }

    }

    public record ConditionalWithEventTypeAndData<I, T, O, I1, O1>(
        ConditionalWithEventType<I, T, O, I1, O1> eventType,
        Function<T, I1> dataAdapter,
        Function<T, Mono<I1>> reactiveDataAdapter
    ) {

      public ConditionalWithEntity<I, T, O, I1, O1> on(EntityType entityType) {
        requireTarget(entityType);
        return new ConditionalWithEntity<>(this, entityType);
      }

    }

    public record ConditionalWithEntity<I, T, O, I1, O1>(ConditionalWithEventTypeAndData<I, T, O, I1, O1> eventTypeAndData, EntityType entityType) {

      /// @param entitySelector the entity, which may be selected by a fallback if the selector finds none, e.g. with
      ///                       [EntitySelector#idEvent(EventType, Object, EntitySelector)]
      public TransitionModelBuilder<I, T, O> identifiedBy(Function<T, ? extends EntitySelector> entitySelector) {
        var eventType = eventTypeAndData.eventType();
        return eventType.builder().triggerConditional(
            new Conditional<>(
                eventType.eventType(),
                eventTypeAndData.dataAdapter(),
                entitySelector,
                entityType,
                eventType.condition()
            ),
            eventTypeAndData.reactiveDataAdapter()
        );
      }

    }

    public record WithEventTypeAndData<I, T, O, I1, O1>(
        WithEventType<I, T, O, I1, O1> eventType,
        Function<T, I1> dataAdapter,
        Function<T, Mono<I1>> reactiveDataAdapter
    ) {

      public WithEventTypeAndData(WithEventType<I, T, O, I1, O1> eventType, Function<T, I1> dataAdapter) {
        this(eventType, dataAdapter, null);
      }

      public WithEntity<I, T, O, I1, O1> on(EntityType entityType) {
        requireTarget(entityType);
        return new WithEntity<>(this, entityType);
      }

      /// Triggers the event on the entity after the delay, unless another event is stored on it first. The data of the
      /// event is stored whole with the trigger until it fires, in a session of its own, so it must not have a secret,
      /// which isn't masked here as an output is (see [TransitionModel.WithOutput#masked]).
      public TransitionModelBuilder<I, T, O> after(Function<T, Duration> delay) {
        if (reactiveDataAdapter != null)
          throw new UnsupportedOperationException("A delayed event can't have its data created reactively");
        return this.eventType.builder.triggerDelayed(this.eventType.eventType(), this.dataAdapter, delay);
      }

    }

    public record WithIdentifier<I, T, O, I1, O1>(WithEntity<I, T, O, I1, O1> entity, Function<T, ? extends EntitySelector> entitySelector) {

      public <I2, O2> WithEventType<I, Tuple2<T, ProcessResult<O1>>, O, I2, O2> trigger(EventType<I2, O2> eventType) {
        return new WithEventType<>(complete(), eventType);
      }

      public WithFilter<I, Tuple2<T, ProcessResult<O1>>, O> when(Predicate<Tuple2<T, ProcessResult<O1>>> filter) {
        return complete().when(filter);
      }

      public TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> reversible(TransitionModelBuilder<Data, ?, Data> reverseModel) {
        return complete().reversible(reverseModel);
      }

      public TransitionModel.WithOutput<I, O> output(Function<Tuple2<T, ProcessResult<O1>>, O> f) {
        return complete().output(f);
      }

      public TransitionModel<I, O> output() {
        return complete().output();
      }

      private TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> complete() {
        return this.entity.eventTypeAndData.eventType.builder.trigger(
            new Immediate<>(
                this.entity.eventTypeAndData.eventType.eventType,
                this.entity.eventTypeAndData.dataAdapter,
                this.entitySelector,
                this.entity.entityType
            ),
            this.entity.eventTypeAndData.reactiveDataAdapter
        );
      }

    }

    public record WithEntity<I, T, O, I1, O1>(WithEventTypeAndData<I, T, O, I1, O1> eventTypeAndData, EntityType entityType) {

      /// @param entitySelector the entity, which may be selected by a fallback if the selector finds none, e.g. with
      ///                       [EntitySelector#idEvent(EventType, Object, EntitySelector)]
      public TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> identifiedBy(Function<T, ? extends EntitySelector> entitySelector) {
        return new WithIdentifier<>(this, entitySelector).complete();
      }

    }

    public TransitionModelBuilder<I, T, O> after(Function<T, Duration> delay) {
      return this.builder.triggerDelayed(eventType, _ -> null, delay);
    }

    public WithEventTypeAndData<I, T, O, I1, O1> with(Function<T, I1> dataAdapter) {
      return new WithEventTypeAndData<>(this, dataAdapter);
    }

    /// The data of the event is created reactively, e.g. when it needs a value from an external source. Data from the
    /// context of the transition is assembled in the assemble step.
    public WithEventTypeAndData<I, T, O, I1, O1> withReactive(Function<T, Mono<I1>> dataAdapter) {
      return new WithEventTypeAndData<>(this, createdReactively(eventType), dataAdapter);
    }

    /// Triggers the event when the last event of every entity referenced by the index key is one of the given types.
    /// The data of the event is stored whole with the trigger until it fires, in a session of its own, so it must not
    /// have a secret, which isn't masked here as an output is (see [TransitionModel.WithOutput#masked]).
    public WithCondition<I, T, O, I1, O1> when(EventType<?, ?> referenceType, Function<T, UUID> indexKey) {
      return new WithCondition<>(this, referenceType, indexKey);
    }

    public WithEntity<I, T, O, I1, O1> on(EntityType entityType) {
      requireTarget(entityType);
      return new WithEntity<>(new WithEventTypeAndData<>(this, _ -> null), entityType);
    }

  }

  public <I1, O1> WithEventType<I, T, O, I1, O1> trigger(EventType<I1, O1> eventType) {
    return new WithEventType<>(this, eventType);
  }

  public record WithAction<I, T, O, I1>(TransitionModelBuilder<I, T, O> builder, Action<I1> action) {

    public TransitionModelBuilder<I, T, O> with(Function<T, I1> dataAdapter) {
      var modelContext = this.builder.modelContext.withActions(join(this.builder.modelContext.actions, action));
      return new TransitionModelBuilder<>(
          modelContext,
          builder.builderFunction.andThen(c -> c.flatMap(d -> step(d, () -> Mono.just(new ActionStep<>(
              d,
              new ActionTrigger<>(
                  action,
                  dataAdapter.apply(d.stepOutput()),
                  d.inputStep().log()
              )
          )))))
      );
    }

  }

  public <I1> WithAction<I, T, O, I1> trigger(Action<I1> action) {
    return new WithAction<>(this, action);
  }

  /// Chooses the transition of the first choice whose predicate holds. The choices are followed by more of them or by
  /// `otherwise`, as a transition with choices always ends in one of them.
  public Choices<I, T, O> when(Map<Predicate<T>, TransitionModel<T, ?>> choices) {
    return new Choices<>(this).when(choices);
  }

  /// Chooses the transition of the first guarded transition whose guard holds. The choices are followed by more of
  /// them or by `otherwise`, as a transition with choices always ends in one of them.
  public <I1> Choices<I, T, O> choice(List<GuardedTransition<T, I1, ?>> guardedTransitions) {
    return new Choices<>(this).choice(guardedTransitions);
  }

  public <I1, O1> Choices<I, T, O> when(GuardedTransition<T, I1, O1> guardedTransition) {
    return new Choices<>(this).when(guardedTransition);
  }

  /// Chooses the transition if the predicate holds, given by [WithFilter#then]. The choice is followed by more of
  /// them or by `otherwise`, as a transition with choices always ends in one of them.
  public WithFilter<I, T, O> when(Predicate<T> filter) {
    return new WithFilter<>(this, filter);
  }

  /// Chooses the transition unconditionally. The event of this transition isn't stored, but the one of the chosen.
  public <O1> TransitionModel<I, O> otherwise(TransitionModel<T, O1> transitionModel) {
    return new Choices<>(this).otherwise(transitionModel);
  }

  /// Chooses the transition unconditionally, with the input given by the data adapter. The event of this transition
  /// isn't stored, but the one of the chosen.
  public <I1, O1> TransitionModel<I, O> otherwise(TransitionModel<I1, O1> transitionModel, Function<T, I1> dataAdapter) {
    return new Choices<>(this).otherwise(transitionModel, dataAdapter);
  }

  /// A choice of a transition, the alternative, when the predicate holds
  public record Filter<T, I1, O1>(Predicate<T> predicate, Alternative<T, I1, O1> alternative) {}

  /// The transitions chosen by a transition so far, which is followed by more of them or by `otherwise`. A transition
  /// with choices always ends in one of them: only the event of the chosen transition is stored, never the one of the
  /// transition choosing, so the compiler refuses a transition which would otherwise fall through to its own output
  /// when none of its predicates holds.
  public static final class Choices<I, T, O> {

    private final TransitionModelBuilder<I, T, O> builder;

    private Choices(TransitionModelBuilder<I, T, O> builder) {
      this.builder = builder;
    }

    /// Chooses the transition if the predicate holds, and the ones before it don't
    public WithFilter<I, T, O> when(Predicate<T> filter) {
      return new WithFilter<>(builder, filter);
    }

    public Choices<I, T, O> when(Map<Predicate<T>, TransitionModel<T, ?>> choices) {
      var result = this;
      for (var entry : choices.entrySet()) {
        result = result.when(entry.getKey()).then(entry.getValue());
      }
      return result;
    }

    public <I1> Choices<I, T, O> choice(List<GuardedTransition<T, I1, ?>> guardedTransitions) {
      var result = this;
      for (var guardedTransition : guardedTransitions) {
        result = result.when(guardedTransition);
      }
      return result;
    }

    public <I1, O1> Choices<I, T, O> when(GuardedTransition<T, I1, O1> guardedTransition) {
      return when(guardedTransition.guard()).then(guardedTransition.then(), guardedTransition.dataAdapter());
    }

    /// Chooses the transition when none of the ones before it is chosen
    public <O1> TransitionModel<I, O> otherwise(TransitionModel<T, O1> transitionModel) {
      return otherwise(transitionModel, Function.identity());
    }

    /// Chooses the transition, with the input given by the data adapter, when none of the ones before it is chosen
    public <I1, O1> TransitionModel<I, O> otherwise(TransitionModel<I1, O1> transitionModel, Function<T, I1> dataAdapter) {
      return new WithFilter<>(builder, _ -> true).then(transitionModel, dataAdapter).builder.complete();
    }

  }

  public static final class WithFilter<I, T, O> {

    /// The transition chosen, with the data adapter giving its input
    public record Alternative<T, I1, O1>(TransitionModel<I1, O1> model, Function<T, I1> dataAdapter) {}

    private final TransitionModelBuilder<I, T, O> builder;
    private final Predicate<T> predicate;

    private WithFilter(TransitionModelBuilder<I, T, O> builder, Predicate<T> predicate) {
      this.builder = builder;
      this.predicate = predicate;
    }

    public <O1> Choices<I, T, O> then(TransitionModel<T, O1> transitionModel) {
      return then(transitionModel, Function.identity());
    }

    public <I1, O1> Choices<I, T, O> then(TransitionModel<I1, O1> transitionModel, Function<T, I1> dataAdapter) {
      return new Choices<>(complete(new Filter<>(this.predicate, new Alternative<>(transitionModel, dataAdapter))));
    }

    private <I1, O1> TransitionModelBuilder<I, T, O> complete(Filter<T, I1, O1> filter) {
      var modelContext = this.builder.modelContext.withFilters(join(this.builder.modelContext.filters, filter));
      return new TransitionModelBuilder<>(
          modelContext,
          this.builder.builderFunction.andThen(transitionStep -> transitionStep
              .flatMap(c -> {
                if (c instanceof ChoiceStep) {
                  return Mono.just(c);
                }
                return step(c, () -> Mono.just(alternative(filter, c))).flatMap(alternative -> alternative.isEmpty() ?
                    Mono.just(c) :
                    filter.alternative().model().calculate(alternative.get())
                        .flatMap(alternativeOutput -> step(c, () -> Mono.just(new ChoiceStep<>(alternativeOutput, c))))
                );
              })
          )
      );
    }

    /// The input step of the alternative of the filter, if the filter chooses it
    private static <T, I1, O1> Optional<InputStep<I1>> alternative(Filter<T, I1, O1> filter, TransitionStep<T> c) {
      if (!filter.predicate().test(c.stepOutput())) {
        return Optional.empty();
      }
      return Optional.of(new InputStep<>(
          null,
          c,
          null,
          filter.alternative().model(),
          c.inputStep().from(),
          c.inputStep().eventNumber(),
          c.inputStep().log(),
          c.inputStep().timestamp(),
          c.inputStep().correlationId(),
          filter.alternative().dataAdapter().apply(c.stepOutput()),
          c.inputStep().stateMachine(),
          c.inputStep().transaction(),
          c.inputStep().triggerType(),
          c.inputStep().triggerId()
      ));
    }

  }

  public static <T> TransitionModelBuilder<Data, T, Data> assemble(BiFunction<EventLog, EventType<Data, Data>, T> assembler) {
    return assembleReactive(assembler.andThen(Mono::just));
  }

  public static <T> TransitionModelBuilder<Data, T, Data> assembleReactive(BiFunction<EventLog, EventType<Data, Data>, Mono<T>> assembler) {
    return assembleReactiveWithContext((c, rollbackType) -> assembler.apply(c.log(), rollbackType));
  }

  /// A reverse model assembling its data from the context of the rolling back transition, e.g. its correlation id, and
  /// the type of the rollback.
  public static <T> TransitionModelBuilder<Data, T, Data> assembleWithContext(BiFunction<TransitionContext<Data>, EventType<Data, Data>, T> assembler) {
    return assembleReactiveWithContext(assembler.andThen(Mono::just));
  }

  private static <T> TransitionModelBuilder<Data, T, Data> assembleReactiveWithContext(
      BiFunction<TransitionContext<Data>, EventType<Data, Data>, Mono<T>> assembler
  ) {
    return new TransitionModelBuilder<>(
        ModelContext.of(true /* !! */, null, BuiltinEventTypes.EventsRolledBack),
        inputStep -> inputStep.flatMap(
            i -> step(i, () -> {
              // TODO
              EventType<Data, Data> rollbackEventType = (EventType<Data, Data>)i.transitionModel().eventType();
              if (i.previous() != null && i.previous() instanceof InputStep<?> pi)
                rollbackEventType = (EventType<Data, Data>)pi.transitionModel().eventType();
              return assembler.apply(
                  i,
                  rollbackEventType
              ).map(assembled -> new AssembleStep<>(i, assembled));
            })
        )
    );
  }

  public TransitionModelBuilder<I, T, O> reversible(TransitionModelBuilder<Data, ?, Data> reverseModel) {
    return reversible(reverseModel.complete());
  }

  /// The transition reversed by a reverse model with choices, which ends in `otherwise`, e.g.
  /// `assemble(...).when(p).then(m).otherwise(n)`
  public TransitionModelBuilder<I, T, O> reversible(TransitionModel<Data, Data> reverseModel) {
    if (!reverseModel.modelContext().reversal())
      throw new IllegalArgumentException(
          "The reverse model " + reverseModel + " isn't built as one, by assemble(...) or assembleReactive(...) of " +
              "TransitionModelBuilder"
      );
    return new TransitionModelBuilder<>(
        modelContext.withReverseModel(reverseModel),
        builderFunction
    );
  }

  private <I1, O1> TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> trigger(
      EventTrigger<T, I1, O1> eventTrigger,
      Function<T, Mono<I1>> reactiveDataAdapter
  ) {
    Function<Mono<TransitionStep<T>>, Mono<TransitionStep<Tuple2<T, ProcessResult<O1>>>>> f =
        transitionStep -> transitionStep
            .flatMap(c -> step(c, () -> withData(eventTrigger, reactiveDataAdapter, c)).flatMap(t -> c.inputStep().stateMachine().calculateTriggeredEvent(
                        t,
                        c,
                        c.stepOutput(),
                        false,
                        new EventReference(
                            c.inputStep().log().entityId(),
                            c.inputStep().eventNumber()
                        )
                    )
                    .onErrorResume(
                        CircularChange.class,
                        e -> step(c, () -> Mono.just(new PendingStep<>(
                            c,
                            ProcessResult.pending(t, c.stepOutput(), e)
                        )))
                    )
                    .onErrorResume(
                        RejectedEvent.class,
                        e -> step(c, () -> Mono.just(new RejectedStep<>(
                            c,
                            ProcessResult.rejected(eventTrigger.eventType(), e)
                        )))
                    )
                    .onErrorResume(
                        UnknownEntity.class,
                        e -> step(c, () -> Mono.just(new UnknownIdStep<>(
                            c,
                            ProcessResult.unknownId(eventTrigger.eventType(), e)
                        )))
                    )
                .flatMap(output -> step(c, () -> Mono.just(new TriggerStep<>(output, c, eventTrigger))))
            ));
    var eventTriggers = join(modelContext.triggers, eventTrigger);
    return new TransitionModelBuilder<>(
        modelContext.withTriggers(eventTriggers),
        builderFunction.andThen(f)
    );
  }

  /// The delayed event fires unless another event is stored on the entity first. It is created with the number after
  /// the transition's event, and numbered when the changes are stored after the last event actually stored on the
  /// entity, as the transition chosen may not be stored.
  private <I1, O1> TransitionModelBuilder<I, T, O> triggerDelayed(EventType<I1, O1> eventType, Function<T, I1> dataAdapter, Function<T, Duration> delay) {
    Function<Mono<TransitionStep<T>>, Mono<TransitionStep<T>>> f =
        transitionStep -> transitionStep
            .flatMap(c -> step(c, () -> Mono.just(new DelayedTriggerStep<>(
                c,
                new DelayedEvent<>(
                    c.inputStep().log().entityType(),
                    c.inputStep().log().entityId(),
                    eventType,
                    c.inputStep().eventNumber() + 1,
                    dataAdapter.apply(c.stepOutput()),
                    c.inputStep().timestamp().plus(delay.apply(c.stepOutput()))
                )
            ))));
    return new TransitionModelBuilder<>(
        modelContext.withDelayedEventType(eventType),
        builderFunction.andThen(f)
    );
  }

  private <I1, O1> TransitionModelBuilder<I, T, O> triggerConditional(
      Conditional<T, I1, O1> conditionalTrigger,
      Function<T, Mono<I1>> reactiveDataAdapter
  ) {
    Function<Mono<TransitionStep<T>>, Mono<TransitionStep<T>>> f =
        transitionStep -> transitionStep
            // The event is always stored as a ConditionalTrigger. If its condition is already met it is fired right after
            // the triggering change is committed, otherwise it is fired by the poller once the condition is met.
            .flatMap(c -> step(c, () -> withData(conditionalTrigger, reactiveDataAdapter, c).map(eventTrigger -> {
              var condition = conditionalTrigger.condition();
              return new ConditionalTriggerStep<>(
                  c,
                  new ConditionalTrigger<>(
                      Identifiers.newId(),
                      new EventReference(
                          c.inputStep().log().entityId(),
                          c.inputStep().eventNumber()
                      ),
                      eventTrigger.entityType(),
                      conditionalEntityId(eventTrigger, c),
                      eventTrigger.eventType(),
                      eventTrigger.data().apply(c.stepOutput()),
                      c.inputStep().timestamp(),
                      condition.referenceType(),
                      condition.lastTypes(),
                      condition.indexKey().apply(c.stepOutput()),
                      c.inputStep().correlationId()
                  )
              );
            })));
    var eventTriggers = join(modelContext.triggers, conditionalTrigger);
    return new TransitionModelBuilder<>(
        modelContext.withTriggers(eventTriggers),
        builderFunction.andThen(f)
    );
  }

  /// The trigger with the data of its event created, by the reactive data adapter if given. As the result of a step,
  /// it has the data created once.
  private static <T, I1, O1> Mono<EventTrigger<T, I1, O1>> withData(
      EventTrigger<T, I1, O1> eventTrigger,
      Function<T, Mono<I1>> reactiveDataAdapter,
      TransitionStep<T> c
  ) {
    if (reactiveDataAdapter == null) {
      var dataAdapter = eventTrigger.data();
      I1 data = dataAdapter != null ? dataAdapter.apply(c.stepOutput()) : null;
      return Mono.just(eventTrigger.withData(_ -> data));
    }
    return reactiveDataAdapter.apply(c.stepOutput())
        .switchIfEmpty(Mono.error(() -> new IllegalStateException(
            "No data created for " + eventTrigger.eventType().name()
        )))
        .map(data -> eventTrigger.withData(_ -> data));
  }

  /// The data adapter of an event whose data is created reactively, which is never applied.
  private static <T, I1> Function<T, I1> createdReactively(EventType<I1, ?> eventType) {
    return _ -> {
      throw new IllegalStateException("The data of " + eventType.name() + " is created reactively");
    };
  }

  private static <T> UUID conditionalEntityId(EventTrigger<T, ?, ?> eventTrigger, TransitionStep<T> c) {
    var selector = eventTrigger.entitySelector().apply(c.stepOutput());
    if (selector instanceof EntitySelector.ById byId) {
      return byId.id();
    }
    if (selector instanceof EntitySelector.ByIdFromSession
        && c.inputStep().log().entityType().equals(eventTrigger.entityType())) {
      return c.inputStep().log().entityId();
    }
    throw new UnsupportedOperationException("Conditional trigger requires the entity to be identified by id or by the session's entity");
  }

  private static <E> List<E> join(List<E> list, E element) {
    var l = new ArrayList<E>(list.size() + 1);
    l.addAll(list);
    l.add(element);
    return unmodifiableList(l);
  }

  /// The transition with the output of the data adapter, which may be [masked][TransitionModel.WithOutput#masked]
  public TransitionModel.WithOutput<I, O> output(Function<T, O> f) {
    return new TransitionModel.WithOutput<>(modelContext, mask -> outputChain(f, mask));
  }

  /// The steps of the transition ending in the output step of the output, masked when stored if the mask isn't null.
  /// The event is made, and masked, in the output step, which is recorded by the transaction, so that the same step is
  /// given when the changes are calculated again, and the steps after it are recognised.
  private Function<Mono<InputStep<I>>, Mono<OutputStep<O>>> outputChain(Function<T, O> f, UnaryOperator<O> mask) {
    return builderFunction.andThen(a -> a.flatMap(b -> step(b, () -> Mono.just(TransitionModel.withStoredForm(new OutputStep<>(
        b,
        ProcessResult.accepted(
            event(b, f.apply(b.stepOutput())),
            b.inputStep().log().entityType(),
            modelContext.severity()
        )
    ), mask)))));
  }

  /// The event of the transition with the output, masked when stored if the mask isn't null. A transition which
  /// chooses another one has no event of its own, as it ends in the chosen one's, with that one's mask.
  private Event<O> event(TransitionStep<T> step, O output) {
    if (output == null)
      throw new IllegalStateException(String.format(
          "The output of the transition on %s is null, which an event can't have. An event without data, of an event " +
              "type without data, is made by output()",
          modelContext.eventType().name()
      ));
    InputStep<?> inputStep = step.inputStep();
    UUID entityId = inputStep.log().entityId();
    int number = inputStep.log().lastEventNumber() + 1;
    // Its stored form is made in the output step, after the mask, if it is stored, see TransitionModel#withStoredForm
    return new Event<>(entityId, number, modelContext.eventType(), inputStep.timestamp(), output);
  }



  private TransitionModel<I, O> complete() {
    return new TransitionModel<>(
        modelContext,
        builderFunction.andThen(a -> a.flatMap(b -> step(b, () -> {
              if (!(b.previous() instanceof OutputStep) && !(b.previous() instanceof PendingStep)) {
                throw new IllegalStateException("Previous step should be output or pending");
              }
              return Mono.just(new OutputStep<>(
                  b,
                  ProcessResult.completed(
                      b.previous() instanceof OutputStep<?> o ? o.stepOutput() : (b.previous() instanceof PendingStep<?, ?, ?> p ? p.stepOutput() : null),
                      b.inputStep().log().entityId(),
                      b.inputStep().eventNumber(),
                      b.inputStep().log().entityType()
                  )
              ));
            }
        )))
    );
  }

  /// The transition with an event without data, which only an event type without data, of [DataType#none()], may have,
  /// as the state machine checks when it is created. An event type with data has an output, of
  /// [#output(Function)]. It has nothing to mask, so the transition can't be masked: only the one of
  /// [#output(Function)] has [TransitionModel.WithOutput#masked].
  public TransitionModel<I, O> output() {
    return new TransitionModel<>(
        modelContext.asWithoutData(),
        builderFunction.andThen(a -> a.flatMap(b -> step(b, () -> Mono.just(TransitionModel.withStoredForm(new OutputStep<>(
            b,
            ProcessResult.accepted(
                new Event<>(
                    b.inputStep().log().entityId(),
                    b.inputStep().log().lastEventNumber() + 1,
                    modelContext.eventType(),
                    b.inputStep().timestamp(),
                    null
                ),
                b.inputStep().log().entityType(),
                modelContext.severity()
            )
        ), null)))))
    );
  }


  /// The result of a step of the transaction the transition step is part of. Every step is taken like this, such that
  /// it is not executed again when the changes are calculated again from a later step.
  private static <R> Mono<R> step(TransitionStep<?> context, Supplier<Mono<R>> execution) {
    return context.inputStep().transaction().step(context, execution);
  }

  static void log(ModelContext<?, ?> modelContext, String text) {
    Debug.log(ZonedDateTime.now() + ": " + modelContext.description() + ": " + text);
  }

  /// A trigger on an entity type which is null, e.g. a constant read while its class is being initialised as the
  /// transitions are created, would be taken as a trigger on the entity itself
  private static void requireTarget(EntityType entityType) {
    if (entityType == null)
      throw new NullPointerException(
          "The entity type to trigger the event on is null. If it is a constant, the transitions are created before " +
              "it is, e.g. by an inbox declared before it"
      );
  }

}
