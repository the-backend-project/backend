package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.Tuples.tuple;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.EntitySelector.ById;
import com.github.thxmasj.backend.core.EntitySelector.ByIdEvent;
import com.github.thxmasj.backend.core.EntitySelector.ByIdFromSession;
import com.github.thxmasj.backend.core.EntitySelector.ByLastIndexed;
import com.github.thxmasj.backend.core.EntitySelector.ByLastReferencing;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Accepted;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Completed;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.IdExists;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Pending;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Rejected;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.UnknownId;
import com.github.thxmasj.backend.core.Tuples.Tuple3;
import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.EntityGroupNotInitialised;
import com.github.thxmasj.backend.core.database.MappingFailure;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import com.github.thxmasj.backend.core.internal.ActionTrigger;
import com.github.thxmasj.backend.core.internal.Transaction;
import com.github.thxmasj.backend.core.internal.Debug;
import com.github.thxmasj.backend.core.internal.DelayedEvent;
import com.github.thxmasj.backend.core.internal.TransitionStep;
import com.github.thxmasj.backend.core.internal.ResolvedModels;
import com.github.thxmasj.backend.core.internal.TransitionStep.ActionStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.AssembleStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.ChoiceStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.ConditionalTriggerStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.DelayedTriggerStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.IdExistsStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.InputStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.OutputStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.PendingStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.RejectedStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.TriggerStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.UnknownIdStep;
import com.github.thxmasj.backend.core.internal.database.ChangeState;
import com.github.thxmasj.backend.core.internal.database.ChangeState.Change;
import com.github.thxmasj.backend.core.internal.database.ConditionalTrigger;
import com.github.thxmasj.backend.core.internal.database.DelayedTrigger;
import com.github.thxmasj.backend.core.internal.database.EventAlreadyExists;
import com.github.thxmasj.backend.core.internal.database.IdEventAlreadyExists;
import com.github.thxmasj.backend.core.internal.database.Storage;
import com.github.thxmasj.backend.core.internal.database.StorageProvider;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;
import reactor.util.retry.RetrySpec;

public class StateMachine implements AutoCloseable {

  private static final int MAX_CONDITIONAL_TRIGGERS_PER_POLL = 10;

  private final Function<List<String>, Mono<Void>> delayer;
  private final Clock clock;
  private final Listener listener;
  private final List<Looper<?>> workers = new ArrayList<>();
  private final DatabaseClient client;
  private final Storage storage;
  private final ResolvedModels models;
  private final Map<EntityModel, Traverser> traversers;
  private final Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel;

  public StateMachine(
      List<? extends EntityModel> entityModels,
      DataSource dataSource,
      String schemaName,
      boolean withResolving
  ) {
    this(null, entityModels, dataSource, null, schemaName, null, Clock.systemUTC(), null, withResolving);
  }

  public StateMachine(
      Function<List<String>, Mono<Void>> delayer,
      List<? extends EntityModel> entityModels,
      DataSource dataSource,
      DataSource schemaDataSource,
      String schemaName,
      String role,
      Clock clock,
      Listener listener
  ) {
    this(delayer, entityModels, dataSource, schemaDataSource, schemaName, role, clock, listener, true);
  }

  /// The database is the one behind the data source (see [Database#of(DataSource)])
  public StateMachine(
      Function<List<String>, Mono<Void>> delayer,
      List<? extends EntityModel> entityModels,
      DataSource dataSource,
      DataSource schemaDataSource,
      String schemaName,
      String role,
      Clock clock,
      Listener listener,
      boolean withResolving
  ) {
    Database database = Database.of(dataSource);
    this(delayer, entityModels, database, database.client(dataSource), schemaDataSource, schemaName, role, clock, listener, withResolving);
  }

  /// @param delayer delays the storing of new events until the returned [Mono] completes, e.g. for tests to hold a
  ///                request at an event. It is given a key `<correlation id>-<event type id>` of each new event, so an
  ///                event is identified by its [EventType#id()], which stays the same when the event type is renamed.
  ///                Null for no delay.
  /// @param entityModels the entity models to run, each with its transitions, of which the state machine also runs
  ///                     the models the transitions trigger events on, and so on. It is enough to give the models
  ///                     which nothing triggers events on, e.g. the entity models of an inbox (see
  ///                     `HttpInbox#entityModels()`). An [EntityModelGroup] is not one of them, since it has no entities
  ///                     of its own.
  /// @throws IllegalArgumentException if one of the entity models given is an [EntityModelGroup], or if two of the
  ///                                  models run have the same id
  /// @throws NullPointerException if one of the entity models given is null, e.g. a constant not created yet
  /// @param client a client of the database, which is closed with the state machine
  /// @param schemaDataSource a data source of the database to create the schema with, or null if it is created
  ///                         elsewhere
  public StateMachine(
      Function<List<String>, Mono<Void>> delayer,
      List<? extends EntityModel> entityModels,
      Database database,
      DatabaseClient client,
      DataSource schemaDataSource,
      String schemaName,
      String role,
      Clock clock,
      Listener listener,
      boolean withResolving
  ) {
    ResolvedModels resolved = ResolvedModels.of(entityModels);
    List<EntityModel> models = resolved.entityModels();
    this.models = resolved;
    Debug.log("Supported entity models:\n" + models.stream().map(EntityModel::name).collect(joining("\n")));
    this.traversers = checkedTraversers(models);
    this.eventTypesByEntityModel = resolved.eventTypesByEntityModel();
    this.delayer = delayer != null ? delayer : _ -> Mono.empty();
    if (schemaDataSource != null) {
      database.createSchema(schemaDataSource, schemaName, role)
          .timeout(Duration.ofSeconds(20), Mono.error(new RuntimeException("Schema creation timed out. Is the database running?")))
          .block();
    }
    this.clock = clock;
    this.client = client;
    this.storage = StorageProvider.storage(database, client, resolved, schemaName, clock);
    this.listener = listener;
    if (withResolving) {
      Looper<ResolverStatus> resolverLooper = new Looper<>(
          "ResolverWorker",
          false,
          () -> resolveState().flux().switchIfEmpty(Flux.just(ResolverStatus.Empty)),
          status -> switch (status) {
            case Ok -> Duration.ZERO;
            case Empty -> ofSeconds(1);
            case Error -> ofSeconds(10);
          }
      );
      resolverLooper.loop();
      workers.add(resolverLooper);
      Looper<ResolverStatus> conditionalTriggerLooper = new Looper<>(
          "ConditionalTriggerWorker",
          false,
          () -> fireConditionalTriggers().flux(),
          _ -> ofSeconds(1)
      );
      conditionalTriggerLooper.loop();
      workers.add(conditionalTriggerLooper);
    }
    Debug.log("Initialized " + getClass().getName() + " on database schema " + schemaName);
  }

  /// Stops the workers resolving delayed triggers and conditional triggers, waits for their ongoing work to complete, and closes
  /// the client, and with it any connection pool. Requests should be stopped before this is called.
  @Override
  public void close() throws Exception {
    workers.forEach(Looper::stop);
    for (var worker : workers)
      if (!worker.awaitStopped(ofSeconds(30)))
        Debug.log("Gave up waiting for a worker to stop");
    client.close();
  }

  /// The traversers of the transitions of the models, which check them. Fails with the refusals of every model, each
  /// named, so that the models are fixed in one go.
  private static Map<EntityModel, Traverser> checkedTraversers(List<EntityModel> models) {
    var traversers = new HashMap<EntityModel, Traverser>();
    var refusals = new ArrayList<String>();
    for (var model : models) {
      try {
        traversers.put(model, new Traverser(model.transitions(), model.stored(), model));
      } catch (IllegalArgumentException e) {
        refusals.add("The transitions of entity model " + model.name() + " are refused: " + e.getMessage());
      }
    }
    if (!refusals.isEmpty())
      throw new IllegalArgumentException(String.join("\n", refusals));
    return traversers;
  }


    private EventLog emptyEventLog(EntityModel entityModel) {
    return new EventLog(entityModel, Identifiers.newId(), List.of());
  }

  private EventLog emptyEventLog(EntityModel entityModel, UUID entityId) {
    return new EventLog(entityModel, entityId, List.of());
  }

  enum ResolverStatus {Ok, Empty, Error}

  private <T> InputEvent<T> inputEvent(DelayedTrigger<T> delayedTrigger) {
    return new InputEvent<>(delayedTrigger.eventType(), delayedTrigger.data());
  }

  /**
   * Process the next delayed event that has reached its delayedTrigger.
   */
  Mono<ResolverStatus> resolveState() {
    var backoff = new DelaySpecification(ofSeconds(10), ofMinutes(10), ofHours(5), 1.5);
    return storage.nextDelayedTrigger().execute(backoff)
        .doOnNext(d -> Debug.log(ZonedDateTime.now() + ": Next delayed trigger: " + d))
        .zipWhen(delayedTrigger -> storage.eventsByEntityId().execute(delayedTrigger.entityModel(), eventTypesByEntityModel.get(delayedTrigger.entityModel()), delayedTrigger.entityId())
            .switchIfEmpty(Mono.error(new RuntimeException("Unknown entity " + delayedTrigger.entityId() + " for delayed event")))
        )
        .flatMap(delayedTriggerAndEventLog -> {
          DelayedTrigger<?> delayedTrigger = delayedTriggerAndEventLog.getT1();
          EventLog eventLog = delayedTriggerAndEventLog.getT2();
          if (eventLog.events().getLast().eventNumber() + 1 != delayedTrigger.eventNumber()) {
            Debug.log("Deleting scheduled event: Event number (" + delayedTrigger.eventNumber() + ") != event log's last number (" + eventLog.events().getLast().eventNumber() + ") + 1");
            // Race! The state has already been resolved by another resolver or incoming request. Which is OK!
            return storage.deleteDelayedTrigger().execute(delayedTrigger.entityId(), delayedTrigger.eventNumber()).map(_ -> ResolverStatus.Ok);
          }
          Debug.log("Delayed trigger eventNumber: " + delayedTrigger.eventNumber() + ", log's last event number: "
              + eventLog.lastEventNumber());
          var currentState = traversers.get(delayedTrigger.entityModel()).currentState(eventLog);
          if (currentState == null)
            return Mono.error(new RuntimeException(
                "Invalid event log: " + eventLog.events().stream().map(Event::typeName).collect(joining(","))));
          InputEvent<?> event = inputEvent(delayedTrigger);
          Debug.log("resolveState with: " + event);
          return firedByDelayedTrigger(delayedTrigger.correlationId(), event, eventLog)
              .flatMap(processResult -> switch (processResult) {
                    // State is resolved and delayed trigger already deleted by the change triggered by this event.
                    case Accepted<?> _ -> Mono.just(ResolverStatus.Ok);
                    case Completed<?> _ -> Mono.just(ResolverStatus.Ok);
                    case ProcessResult<?> r -> Mono.error(new IllegalStateException("Unexpected result for state resolving: " + r));
                  }
              ).contextWrite(Correlation.contextOf(delayedTrigger.correlationId()));
        })
        .onErrorReturn(MappingFailure.class, ResolverStatus.Error)
        .doOnError(e -> { if (listener != null) listener.processNextDelayedTriggerFailed(e); })
        .onErrorReturn(ResolverStatus.Error)
        .switchIfEmpty(Mono.just(ResolverStatus.Empty));
  }

  public sealed interface ProcessResult<T> permits Accepted, Completed, IdExists, Pending, Rejected, UnknownId {

    record Completed<T>(ProcessResult<?> choiceResult, UUID entityId, int eventNumber, EntityModel entityModel) implements ProcessResult<T> {
      public Completed<T> completed() {return this;}
    }

    /// @param severity the severity declared on the transition which accepted the event. The trigger which fired the
    ///                 transaction, if any, is on its first step, see [InputStep#triggerType()].
    record Accepted<T>(Event<T> event, EntityModel entityModel, Severity severity) implements ProcessResult<T> {
      public Accepted<T> accepted() {return this;}
    }

    record Rejected<T>(EventType<?, T> eventType, RejectedEvent exception, AtomicBoolean handled) implements ProcessResult<T> {

      public Accepted<T> accepted() {
        Debug.log("accepted() called on ProcessResult.Rejected");
        throw exception;
      }

      public Rejected<T> rejected() {return this;}

      @Override
      public boolean isRejected() {
        handled.set(true);
        return true;
      }

      public boolean isHandled() {
        return handled.get();
      }
    }

    /// An id event not stored, as its id exists
    ///
    /// @param idEvent the id event not stored
    /// @param log     the event log of the entity having the id
    record IdExists<T>(EventType<?, T> eventType, Event<?> idEvent, EventLog log) implements ProcessResult<T> {

      public IdExists<T> idExists() {return this;}
    }

    record UnknownId<T>(EventType<?, T> eventType, UnknownEntity exception) implements ProcessResult<T> {

      public UnknownId<T> unknownId() {return this;}
    }

    record Pending<T, I, O>(EventTrigger<T, I, O> trigger, T inputData, CircularChange exception) implements ProcessResult<O> {

      @Override
      public Pending<?, ?, O> pending() {
        return this;
      }
    }

    static <T> Completed<T> completed(ProcessResult<?> choiceResult, UUID entityId, int eventNumber, EntityModel entityModel) {
      return new Completed<>(choiceResult, entityId, eventNumber, entityModel);
    }

    default boolean isCompleted() {
      return this instanceof Completed;
    }

    default Completed<T> completed() {
      throw new IllegalStateException("Result is " + this);
    }

    static <T> Accepted<T> accepted(Event<T> event, EntityModel entityModel) {
      return new Accepted<>(event, entityModel, Severity.Info);
    }

    static <T> Accepted<T> accepted(Event<T> event, EntityModel entityModel, Severity severity) {
      return new Accepted<>(event, entityModel, severity);
    }

    default boolean isAccepted() {
      return this instanceof Accepted;
    }

    default Accepted<T> accepted() {
      throw new IllegalStateException("Result is " + this);
    }

    static <T> Rejected<T> rejected(EventType<?, T> eventType, RejectedEvent exception) {
      return new Rejected<>(eventType, exception, new AtomicBoolean());
    }

    default boolean isRejected() {
      return this instanceof Rejected;
    }

    default Rejected<T> rejected() {
      throw new IllegalStateException("Result is " + this);
    }

    static <T> IdExists<T> idExists(EventType<?, T> eventType, Event<?> idEvent, EventLog log) {
      return new IdExists<>(eventType, idEvent, log);
    }

    default boolean isIdExists() {
      return this instanceof IdExists;
    }

    default IdExists<T> idExists() {
      throw new IllegalStateException("Result is " + this);
    }

    static <T> UnknownId<T> unknownId(EventType<?, T> eventType, UnknownEntity exception) {
      return new UnknownId<>(eventType, exception);
    }

    default boolean isPending() {
      return this instanceof Pending;
    }

    default Pending<?, ?, T> pending() {
      throw new IllegalStateException("Result is " + this);
    }

    static <T, I, O> Pending<T, I, O> pending(EventTrigger<T, I, O> trigger, T inputData, CircularChange exception) {
      return new Pending<>(trigger, inputData, exception);
    }

    default boolean isUnknownId() {
      return this instanceof UnknownId;
    }

    default UnknownId<T> unknownId() {
      return (UnknownId<T>) this;
    }

  }

  private Mono<EventLog> eventLogByEntityId(EntityModel entityModel, UUID entityId) {
    return storage.eventsByEntityId().execute(entityModel, eventTypesByEntityModel.get(entityModel), entityId);
  }

  public <O> Flux<Event<?>> onEvent(EventTrigger<Void, Void, O> eventTrigger) {
    return onEvent(UUID.randomUUID().toString(), eventTrigger);
  }

  public <O> Flux<Event<?>> onEvent(String correlationId, EventTrigger<Void, Void, O> eventTrigger) {
    return onEvent(correlationId, eventTrigger, null);
  }

  public <I, O> Flux<Event<?>> onEvent(EventTrigger<I, I, O> eventTrigger, I input) {
    return onEvent(UUID.randomUUID().toString(), eventTrigger, input);
  }

  /// Fails with an [IllegalArgumentException] if the state machine doesn't run the entity model triggered on, the
  /// same instance (see [#runs(EntityModel)]).
  public <T, I, O> Flux<Event<?>> onEvent(String correlationId, EventTrigger<T, I, O> eventTrigger, T input) {
    EntityModel target = eventTrigger.entityModel();
    if (target != null && !(target instanceof EntityModelGroup) && !runs(target))
      return Flux.error(models.notRun(target, "the state machine"));
    return onEvent(correlationId, eventTrigger, input, null);
  }

  /// Whether the state machine runs the entity model, the same instance as it was given or found. Another instance
  /// with its id isn't, as it may differ, e.g. be created from other configuration. The models run are given by
  /// [#entityModels()].
  public boolean runs(EntityModel entityModel) {
    return models.runs(entityModel);
  }

  private <T, I, O> Flux<Event<?>> onEvent(
      String correlationId,
      EventTrigger<T, I, O> eventTrigger,
      T input,
      @Nullable EventReference triggerEvent
  ) {
    Debug.log("onEvent " + correlationId + " " + eventTrigger.eventSpec().eventType().name());
    I adaptedInput = eventTrigger.eventSpec().inputAdapter().apply(input);
    Many<Event<?>> responseSink = Sinks.many().unicast().onBackpressureBuffer();
    return eventLog(eventTrigger, input, null)
        .onErrorMap(UnknownEntity.class, e -> new RejectedEvent(eventTrigger.eventSpec().eventType(), e))
        .flatMapMany(log ->
                onEvent(correlationId, eventTrigger.eventSpec().eventType(), adaptedInput, log, null, eventTrigger.triggerType(), eventTrigger.triggerId(), triggerEvent)
                    .contextWrite(ctx -> ctx.put("RS/" + log.entityId(), responseSink))
                    .thenMany(responseSink.asFlux())
                    .retryWhen(RetrySpec.fixedDelay(3, Duration.ofMillis(500))
                        .filter(e -> e instanceof EventAlreadyExists)
                        // Avoid the "Thundering Herd" problem
                        .jitter(1.0)
                        .doAfterRetry(signal -> Debug.log(
                            System.currentTimeMillis() + ": Retried (" + signal.totalRetries() + ") due to "
                                + signal.failure().getMessage()))
                        // Rethrow the exception on exhaustion so it can be handled downstream
                        .onRetryExhaustedThrow((_, signal) -> signal.failure())
                    )
                    .switchIfEmpty(Flux.error(new RuntimeException("onEvent: No response")))
        );
  }

  private <I> Mono<ProcessResult<?>> onEvent(
      String correlationId,
      InputEvent<I> inputEvent,
      EventLog eventLog,
      TransitionStep<?> requestStep
  ) {
    return onEvent(correlationId, inputEvent.eventType(), inputEvent.data(), eventLog, requestStep, null, null, null);
  }

  /// The event of a delayed trigger, whose trigger is deleted with the change of the event
  private <I> Mono<ProcessResult<?>> firedByDelayedTrigger(String correlationId, InputEvent<I> event, EventLog eventLog) {
    return onEvent(correlationId, event.eventType(), event.data(), eventLog, null, TriggerType.DELAYED, null, null);
  }

  /// The changes to store, collected from the last step to the first. A delayed event is numbered after the event of
  /// the transition triggering it, if that is stored, and with its number otherwise, which the next event stored on the
  /// entity takes: it fires unless another event is stored first. The event of the transition, or of the one it
  /// chooses, is the first one accepted on the entity after the delayed trigger, as the events it triggers on its own
  /// entity are calculated after it.
  private List<Change> changes(@NotNull TransitionStep<?> tail) {
    ArrayList<Change> changes = new ArrayList<>();
    var nextEvents = new HashMap<EntityKey, Event<?>>();
    for (var c = tail; c != null; c = c.previous()) {
      switch (c) {
        case OutputStep<?> occ
            when occ.stepOutput().isAccepted() && !(occ.previous() instanceof ChoiceStep<?>) -> {
          var accepted = occ.stepOutput().accepted();
          nextEvents.put(new EntityKey(accepted.entityModel().id(), accepted.event().entityId()), accepted.event());
          changes.add(Change.fromAcceptedEvent(accepted));
        }
        case OutputStep<?> _ -> {}
        case DelayedTriggerStep<?, ?> dtcc -> changes.add(Change.fromDelayedEvent(numbered(dtcc.delayedEvent(), nextEvents)));
        case ConditionalTriggerStep<?, ?> ctcc -> changes.add(Change.fromConditionalTrigger(ctcc.conditionalTrigger()));
        case AssembleStep<?> _ -> {}
        case ChoiceStep<?> _ -> {}
        case InputStep<?> _ -> {}
        case TriggerStep<?, ?> _ -> {}
        case PendingStep<?, ?, ?> _ -> {}
        case RejectedStep<?> _ -> {}
        case UnknownIdStep<?> _ -> {}
        case IdExistsStep<?> _ -> {}
        case ActionStep<?, ?> _ -> {}
      }
    }
    return changes;
  }

  /// The delayed event numbered after the event of its transition, the next one accepted on its entity, if that is
  /// stored, or with its number if it isn't
  private static <T> DelayedEvent<T> numbered(DelayedEvent<T> delayedEvent, Map<EntityKey, Event<?>> nextEvents) {
    Event<?> transitionsEvent = nextEvents.get(new EntityKey(delayedEvent.entityModel().id(), delayedEvent.entityId()));
    if (transitionsEvent == null)
      return delayedEvent;
    return delayedEvent.withEventNumber(transitionsEvent.eventNumber() + (transitionsEvent.type().stored() ? 1 : 0));
  }

  /// The action trigger with the log of its entity as the transaction of the action's result reads it, a later one: with
  /// its events as stored, e.g. masked, also those of the same transaction before the transition's, and with the
  /// event of the transition if it is stored, as the next event stored follows one which isn't. An entity of the
  /// session only has its events in the session, as they are.
  private static <T> ActionTrigger<T> withEventAsRead(ActionTrigger<T> actionTrigger, Event<?> event) {
    if (actionTrigger.eventLog().isSessionOnlyWith(event))
      return actionTrigger.withEvent(event);
    var asRead = new ActionTrigger<>(actionTrigger.action(), actionTrigger.data(), actionTrigger.eventLog().asStored());
    return event.type().stored() ? asRead.withEvent(event.asStored()) : asRead;
  }

  private List<ActionTrigger<?>> actionTriggers(@NotNull TransitionStep<?> tail) {
    ArrayList<ActionTrigger<?>> actionTriggers = new ArrayList<>();
    OutputStep<?> lastOutput = null;
    for (var c = tail; c != null; c = c.previous()) {
      switch (c) {
        case OutputStep<?> occ -> lastOutput = occ;
        case ActionStep<?, ?> acc -> {
          if (lastOutput != null && lastOutput.stepOutput().isAccepted()) {
            actionTriggers.add(withEventAsRead(acc.actionTrigger(), lastOutput.stepOutput().accepted().event()));
          }
        }
        default -> {}
      }
    }
    return actionTriggers;
  }

  private <I> Mono<ProcessResult<?>> onEvent(
      String correlationId,
      EventType<I, ?> eventType,
      I input,
      EventLog eventLog,
      TransitionStep<?> stage1,
      TriggerType triggerType,
      UUID triggerId,
      @Nullable EventReference triggerEvent
  ) {
    Debug.log(eventLog.entityModel().name() + ": onEvent " + eventType.name() + " with log " + eventLog.events().stream().map(Event::typeName).collect(joining(",")));
    var now = ZonedDateTime.now(clock);
    var tuple = transitionModel(eventLog, eventType);
    if (tuple.t2() == null) {
      Debug.log("onEvent " + eventType.name() + ": No transition model found");
      return Mono.just(ProcessResult.rejected(
          eventType,
          new RejectedEvent(eventType, eventLog, tuple.t3())
      ));
    }
    if (tuple.t3() != null) {
      Debug.log("onEvent " + eventType.name() + ": Current state: " +  tuple.t3());
    }
    // Deferred, as a retry is a transaction of its own
    return Mono.defer(() -> calculateAndStore(
        tuple.t2(),
        new InputStep<>(
            stage1,
            null,
            triggerEvent,
            tuple.t2(),
            tuple.t3(),
            eventLog.lastEventNumber() + 1,
            tuple.t1(),
            now,
            correlationId,
            input,
            this,
            new Transaction(),
            triggerType,
            triggerId
        )
    ));
  }

  /// Tells the listener that the transition from the input step failed, unless it was told of the failure already, by
  /// the transition triggered by this one which it was thrown in
  void transitionFailed(InputStep<?> inputStep, Throwable failure) {
    if (listener != null && inputStep.transaction().isNewFailure(failure))
      listener.transitionFailed(
          inputStep.correlationId(),
          inputStep.log().entityModel(),
          inputStep.log().entityId(),
          inputStep.transitionModel().eventType(),
          failure
      );
  }

  /// Calculates the changes of the transition from its input step and stores them. If an id of the changes is
  /// found to exist, the changes are calculated again from the first trigger of the id, with the id existing as the
  /// result of triggering it. The steps before the trigger are not executed again, as the transaction has their results.
  private <I, O> Mono<ProcessResult<?>> calculateAndStore(TransitionModel<I, O> transitionModel, InputStep<I> initial) {
    return transitionModel.calculate(initial)
        .flatMap(this::calculatePendingChanges)
        .flatMap(output -> switch (finalResult(output)) {
          case Accepted<?> _, Completed<?> _ -> storeChanges(initial.timestamp(), initial.correlationId(), output)
              .thenReturn(output.stepOutput());
          case Rejected<?> r -> transitionModel.rejectModel() == null ? Mono.error(r.exception()) :
              transitionModel.rejectModel().calculate(
                  new InputStep<>(
                      null,
                      null,
                      null,
                      transitionModel,
                      initial.from(),
                      initial.eventNumber(),
                      initial.log(),
                      initial.timestamp(),
                      initial.correlationId(),
                      tuple(
                          initial.input(),
                          initial.log().entityModel(),
                          transitionModel.eventType(),
                          r.exception.getMessage()
                      ),
                      this,
                      initial.transaction()
                  )
              )
              .flatMap(rejectChanges -> rejectChanges.stepOutput().isAccepted() ?
                  storeChanges(initial.timestamp(), initial.correlationId(), rejectChanges).thenReturn(rejectChanges.stepOutput()) :
                  Mono.error(new RuntimeException(
                      "Failed to handle rejected event: " + rejectChanges.stepOutput() + " (original: "
                      + output.stepOutput() + ")"))
              );
          case ProcessResult<?> r -> Mono.error(new RuntimeException("onEvent: " + r));
        })
        .onErrorResume(
            IdEventAlreadyExists.class,
            e -> storage.eventsByIdEvent().execute(e.idEvent().type(), e.idEvent().getMarshalledData())
                .switchIfEmpty(Mono.error(e))
                .doOnNext(_ -> Debug.log("onEvent " + transitionModel.eventType().name() + ": " + e.getMessage()))
                .flatMap(log -> initial.transaction().recomputeWith(ProcessResult.idExists(e.idEvent().type(), e.idEvent(), log)) ?
                    calculateAndStore(transitionModel, initial) :
                    // The id event would be calculated the same again
                    Mono.error(e)
                )
        );
  }

  /// The existing id, if the triggered event is an id event with an id found to exist when storing the changes
  private <I, O> TransitionStep<ProcessResult<O>> idExists(EventTrigger<?, I, O> eventTrigger, TransitionStep<?> tail, I data) {
    if (!(eventTrigger.entityModel() instanceof IdEntityModel))
      return null;
    EventType<I, O> type = eventTrigger.eventSpec().eventType();
    return tail.inputStep().transaction().existingIds().stream()
        .filter(existing -> existing.idEvent().type().id().equals(type.id())
            && Arrays.equals(existing.idEvent().getMarshalledData(), type.inputDataType().marshal(data)))
        .findFirst()
        .map(existing -> new IdExistsStep<>(tail, ProcessResult.idExists(type, existing.idEvent(), existing.log())))
        .orElse(null);
  }

  private ProcessResult<?> finalResult(@NotNull TransitionStep<? extends ProcessResult<?>> tail) {
    for (TransitionStep<?> current = tail; current != null; current = current.previous()) {
      if (current instanceof RejectedStep<?> p && !p.stepOutput().isHandled()) return p.stepOutput();
    }
    return tail.stepOutput();
  }

  private Mono<TransitionStep<? extends ProcessResult<?>>> calculatePendingChanges(@NotNull TransitionStep<? extends ProcessResult<?>> tail) {
    List<PendingStep<?, ?, ?>> pendingChanges = new ArrayList<>();
    for (TransitionStep<?> current = tail; current != null; current = current.previous()) {
      if (current instanceof PendingStep<?, ?, ?> p) pendingChanges.add(p);
    }
    Mono<TransitionStep<? extends ProcessResult<?>>> result = Mono.just(tail);
    for (var p : pendingChanges) {
      result = result.flatMap(r -> calculatePendingChange(p, r));
    }
    return result;
  }

  private <T, I, O> Mono<? extends TransitionStep<? extends ProcessResult<?>>> calculatePendingChange(PendingStep<T, I, O> p, TransitionStep<?> previous) {
    return calculateTriggeredEvent(
        p.stepOutput().trigger(),
        previous,
        p.stepOutput().inputData(),
        true,
        new EventReference(p.inputStep().log().entityId(), p.inputStep().eventNumber())
    );
  }


  /// The current state of the selected entity. Completes empty if the entity does not exist.
  /// Fails with an [IllegalArgumentException] if the state machine doesn't run the entity model, the same instance
  /// (see [#runs(EntityModel)]).
  public Mono<State> onStatus(EntitySelector entitySelector, EntityModel entityModel) {
    if (!runs(entityModel))
      return Mono.error(models.notRun(entityModel, "the state machine"));
    return eventLog(entitySelector, entityModel, null)
        .map(log -> traversers.get(entityModel).currentState(log))
        .onErrorResume(UnknownEntity.class, _ -> Mono.empty());
  }

  private <T> Flux<Event<?>> fireConditionalTrigger(ConditionalTrigger<T> conditionalTrigger) {
    return fireConditionalTrigger(conditionalTrigger, conditionalTrigger.type());
  }

  private <T, O> Flux<Event<?>> fireConditionalTrigger(
      ConditionalTrigger<T> conditionalTrigger,
      EventType<T, O> eventType
  ) {
    var eventTrigger = new EventTrigger<Void, T, O>(
        new EventTrigger.EventSpec<>(eventType, _ -> conditionalTrigger.data()),
        // The target entity may not exist yet, in which case it is created by the fired event
        List.of(_ -> new ById(conditionalTrigger.entityId(), EntitySelector.CreationMode.CreateIfNotExists)),
        conditionalTrigger.entityModel(),
        false,
        null,
        TriggerType.CONDITIONAL,
        conditionalTrigger.id()
    );
    // The conditional trigger is deleted together with the change of the fired event
    return onEvent(conditionalTrigger.correlationId(), eventTrigger, null, conditionalTrigger.triggerEvent())
        .contextWrite(Correlation.contextOf(conditionalTrigger.correlationId()));
  }

  /**
   * Fire waiting conditional triggers whose condition is now met. The waiting row is deleted together with the change
   * of the fired event. Returns Ok if an event was fired, otherwise Empty (or Error).
   */
  Mono<ResolverStatus> fireConditionalTriggers() {
    Debug.log("fireConditionalTriggers()");
    return storage.conditionalTriggers().execute(MAX_CONDITIONAL_TRIGGERS_PER_POLL)
        .doOnNext(t -> Debug.log("fireConditionalTriggers() found trigger: " + t))
        .concatMap(event -> fireConditionalTrigger(event)
            .hasElements()
            .onErrorResume(e -> {
              Debug.log("Failed to fire conditional trigger " + event.type().name() + " on " + event.entityId(), e);
              return Mono.empty();
            })
        )
        .any(fired -> fired)
        .map(fired -> fired ? ResolverStatus.Ok : ResolverStatus.Empty)
        .onErrorReturn(ResolverStatus.Error);
  }

  private <T, I, O> Mono<EventLog> eventLog(
      EventTrigger<T, I, O> eventTrigger,
      T inputData,
      TransitionStep<?> transitionStep
  ) {
    if (eventTrigger.createEntity() && eventTrigger.entityModel() instanceof EntityModelGroup)
      throw new IllegalArgumentException("An entity can't be created in the group " + eventTrigger.entityModel().name());
    return eventTrigger.createEntity() ?
        Mono.just(emptyEventLog(eventTrigger.entityModel())) :
        eventLog(eventTrigger.entitySelectors().getFirst().apply(inputData), eventTrigger.entityModel(), transitionStep);
  }

  private <I> Mono<EventLog> eventLog(
      EntitySelector entitySelector,
      EntityModel entityModel,
      @Nullable TransitionStep<?> transitionStep
  ) {
    if (entityModel instanceof EntityModelGroup && !(entitySelector instanceof ByIdFromSession))
      throw new IllegalArgumentException("An entity of the group " + entityModel.name() + " can only be identified by the session");
    return switch (entitySelector) {
      case EntitySelector.ByIdFromSession _ -> {
        EventLog log = logFromSteps(transitionStep, entityModel);
        // The model of the session's entity, which is not the target if that is a group
        EntityModel model = log.entityModel();
        UUID id = log.entityId();
        log = logFromNestedChanges(id, model, transitionStep);
        if (log == null) yield eventLogByEntityId(model, id);
        else yield Mono.just(log);
      }
      case ById s -> {
        UUID entityId = s.id();
        yield switch (s.creationMode()) {
          case NeverCreate -> Mono.justOrEmpty(logFromNestedChanges(entityId, entityModel, transitionStep))
              .switchIfEmpty(storage.eventsByEntityId().execute(entityModel, eventTypesByEntityModel.get(entityModel), entityId));
          case CreateIfNotExists -> Mono.justOrEmpty(logFromNestedChanges(entityId, entityModel, transitionStep))
              .switchIfEmpty(storage.eventsByEntityId().execute(entityModel, eventTypesByEntityModel.get(entityModel), entityId))
              .onErrorResume(UnknownEntity.class, _ -> Mono.just(emptyEventLog(entityModel, entityId)));
          case AlwaysCreate -> Mono.just(emptyEventLog(entityModel, s.id()));
        };
      }
      case ByIdEvent<?> s -> eventLog(s, entityModel, transitionStep);
      case ByLastIndexed<?> s -> eventLog(s, entityModel);
      case ByLastReferencing s -> eventLog(s, entityModel);
      case EntitySelector s -> throw new IllegalStateException("Unexpected value: " + s);
    };
  }

  private <T> Mono<EventLog> eventLog(ByIdEvent<T> selector, EntityModel entityModel, TransitionStep<?> transitionStep) {
    Mono<EventLog> log = storage.eventsByIdEvent().execute(selector.type(), selector.type().outputDataType().marshal(selector.id()))
        .filter(l -> l.entityModel().equals(entityModel));
    return switch (selector.creationMode()) {
      case NeverCreate -> log.switchIfEmpty(Mono.defer(() -> selector.fallback() != null ?
          eventLog(selector.fallback(), entityModel, transitionStep) :
          Mono.error(new UnknownEntity(entityModel, selector.type(), selector.id()))
      ));
      case CreateIfNotExists -> log.switchIfEmpty(Mono.fromSupplier(() -> emptyEventLog(entityModel)));
      case AlwaysCreate -> Mono.just(emptyEventLog(entityModel));
    };
  }

  @SuppressWarnings("unchecked")
  private <G> Mono<EventLog> eventLog(ByLastIndexed<G> selector, EntityModel entityModel) {
    if (!(selector.type().outputDataType() instanceof GroupedDataType<?, ?> dataType))
      throw new IllegalArgumentException("Data type of index event type " + selector.type().name() + " is not grouped");
    Mono<EventLog> log = storage.eventsByLastIndexEvent().execute(
        entityModel,
        eventTypesByEntityModel.get(entityModel),
        selector.type().id(),
        ((GroupedDataType<?, G>) dataType).marshalGroup(selector.group()),
        selector.position()
    );
    return switch (selector.creationMode()) {
      case NeverCreate -> log.switchIfEmpty(Mono.error(() -> new EntityGroupNotInitialised(entityModel, selector.type(), selector.group())));
      case CreateIfNotExists -> log.switchIfEmpty(Mono.fromSupplier(() -> emptyEventLog(entityModel)));
      case AlwaysCreate -> throw new IllegalStateException("Unexpected value: " + selector.creationMode());
    };
  }

  /// The entity of the model created last of those referencing the id, by entity id if they were created at the same
  /// time, as entity ids are time-ordered
  private Mono<EventLog> eventLog(ByLastReferencing selector, EntityModel entityModel) {
    return storage.entitiesByIndex().execute(selector.id(), selector.type())
        .filter(log -> log.entityModel().equals(entityModel))
        .reduce((a, b) -> Comparator.comparing(EventLog::created).thenComparing(EventLog::entityId).compare(a, b) >= 0 ? a : b)
        .switchIfEmpty(Mono.error(() -> new UnknownEntity(entityModel, selector.type(), selector.id())));
  }

  /// The log of the entity of the model the session has, as it was given to its transition, searched in the transaction
  /// of the step and then in the transactions of the session before it, each walked once. The transactions of a session
  /// are linked by the first step of each, which refers to the tail of the one before.
  private EventLog logFromSteps(TransitionStep<?> context, EntityModel entityModel) {
    for (var transaction = context; transaction != null; transaction = previousTransaction(transaction)) {
      for (var c = transaction; c != null; c = c.previous()) {
        if (c instanceof InputStep<?> i && EntityModelGroup.targets(entityModel, i.log().entityModel()))
          return i.log();
      }
    }
    throw new NoSuchElementException(entityModel.name() + " not found in transition step");
  }

  /// The log of the entity as the session has it, with the events accepted in the transaction of the step, or, for an
  /// entity of the session only, in the transactions of the session before it, or null if the session has no events of
  /// it. A stored entity is otherwise read from the database, as an earlier transaction's events of it may have been
  /// followed by those of other sessions. An id entity or index entity has the same entity id as its entity, so the
  /// entity model is matched too.
  private EventLog logFromNestedChanges(UUID entityId, EntityModel entityModel, TransitionStep<?> transitionStep) {
    var found = inTransaction(entityId, entityModel, transitionStep);
    if (found.log() != null || !hasEntitiesOfTheSessionOnly(entityModel))
      return found.log();
    while (found.previousTransaction() != null) {
      found = inTransaction(entityId, entityModel, found.previousTransaction());
      if (found.log() != null)
        return found.log().isSessionOnly() ? found.log() : null;
    }
    return null;
  }

  /// What a walk of a transaction found: the log of the entity with the last event accepted on it, or, if it has none,
  /// the tail of the transaction before it in the session, if any
  private record InTransaction(EventLog log, TransitionStep<?> previousTransaction) {}

  /// Walks the transaction of the step once, from its tail, for the last event accepted on the entity
  private static InTransaction inTransaction(UUID entityId, EntityModel entityModel, TransitionStep<?> transitionStep) {
    for (var c = transitionStep; c != null; c = c.previous()) {
      if (c instanceof OutputStep<?>(TransitionStep<?> previous, ProcessResult<?> stepOutput)
          && stepOutput instanceof Accepted<?>(Event<?> event, EntityModel model, _)
          && event.entityId().equals(entityId)
          && model.equals(entityModel)
          && !(previous instanceof ChoiceStep)) {
        EventLog log = c.inputStep().log();
        // An event which is not stored is not in the log of a stored entity, which the next event stored follows
        if (event.type().stored() || log.isSessionOnlyWith(event))
          return new InTransaction(log.withNewEvent(event), null);
      }
    }
    return new InTransaction(null, previousTransaction(transitionStep));
  }

  /// Whether entities of the session only are of the model, which a group is not
  private boolean hasEntitiesOfTheSessionOnly(EntityModel entityModel) {
    Traverser traverser = traversers.get(entityModel);
    return traverser == null || traverser.hasEntitiesOfTheSessionOnly();
  }

  private record EntityKey(UUID entityModelId, UUID entityId) {
    static EntityKey of(Change change) {
      return new EntityKey(change.entityModel().id(), change.entityId());
    }
  }

  /// The entities of the session only among those the transition step accepted events on
  private static Set<EntityKey> sessionOnlyEntities(TransitionStep<?> tail) {
    var keys = new HashSet<EntityKey>();
    for (var c = tail; c != null; c = c.previous()) {
      if (c instanceof OutputStep<?>(TransitionStep<?> previous, ProcessResult<?> stepOutput)
          && stepOutput instanceof Accepted<?>(Event<?> event, EntityModel model, _)
          && !(previous instanceof ChoiceStep)
          && c.inputStep().log().isSessionOnlyWith(event))
        keys.add(new EntityKey(model.id(), event.entityId()));
    }
    return keys;
  }

  /// The changes with the deletion of the stored trigger which fired the event of the transaction, if one did. A
  /// conditional trigger is deleted only by this. A delayed trigger is deleted by the change of an event stored on its
  /// entity too, which deletes the delayed triggers it follows, so this is only needed if no event is stored on it, e.g.
  /// as the transition chosen isn't. It comes first, as a delayed event the transaction triggers on the entity
  /// replaces the trigger.
  private static List<Change> withFiredTrigger(List<Change> changes, TransitionStep<?> tail, Storing storing) {
    InputStep<?> initial = initial(tail);
    if (initial == null || initial.triggerType() == null)
      return changes;
    var entity = new EntityKey(initial.log().entityModel().id(), initial.log().entityId());
    if (initial.triggerType() == TriggerType.DELAYED && hasStoredEvent(changes, entity, storing))
      return changes;
    var withDeletion = new ArrayList<Change>(changes.size() + 1);
    withDeletion.add(Change.fromFiredTrigger(
        initial.log().entityModel(),
        initial.log().entityId(),
        new Change.FiredTrigger(initial.triggerType(), initial.triggerId(), initial.eventNumber())
    ));
    withDeletion.addAll(changes);
    return withDeletion;
  }

  private static boolean hasStoredEvent(List<Change> changes, EntityKey entity, Storing storing) {
    return changes.stream().anyMatch(change -> change.newEvent() != null && EntityKey.of(change).equals(entity) && storing.isStored(change));
  }

  /// The changes of the events with an entity id, in the order the events are emitted to the one who triggered the
  /// event: by number, those of the entity model coming first among the changes first, as an id or index entity, which
  /// has the entity id of its entity, follows the entity, and the events with the same number on an entity, of which
  /// all but the last aren't stored, in the order they were calculated, the reverse of the order they are collected in
  private static List<Change> inEmitOrder(List<Change> collected) {
    var position = new IdentityHashMap<Change, Integer>();
    var modelRank = new HashMap<UUID, Integer>();
    for (Change change : collected) {
      position.put(change, position.size());
      modelRank.putIfAbsent(change.entityModel().id(), modelRank.size());
    }
    return collected.stream()
        .sorted(Comparator.<Change>comparingInt(change -> change.newEvent().eventNumber())
            .thenComparingInt(change -> modelRank.get(change.entityModel().id()))
            .thenComparingInt(change -> -position.get(change)))
        .toList();
  }

  /// The tail of the transaction before that of the step in the session, which the first step of a transaction refers
  /// to, or null if it is the first of the session
  private static TransitionStep<?> previousTransaction(TransitionStep<?> step) {
    InputStep<?> initial = initial(step);
    return initial != null ? initial.stage1() : null;
  }

  /// The first step of the transaction of the step, of the event it calculates
  private static InputStep<?> initial(TransitionStep<?> step) {
    InputStep<?> initial = null;
    for (var c = step; c != null; c = c.previous()) {
      if (c instanceof InputStep<?> i) initial = i;
    }
    return initial;
  }

  /// Which changes of a transaction are stored, by the entities of the session only among those it accepted events on,
  /// and the entity whose transition created each conditional trigger
  private record Storing(Set<EntityKey> sessionOnly, Map<ConditionalTrigger<?>, EntityKey> conditionalTriggerOwners) {

    static Storing of(TransitionStep<?> tail) {
      var owners = new IdentityHashMap<ConditionalTrigger<?>, EntityKey>();
      for (var c = tail; c != null; c = c.previous())
        if (c instanceof ConditionalTriggerStep<?, ?> step)
          owners.put(step.conditionalTrigger(), new EntityKey(step.inputStep().log().entityModel().id(), step.inputStep().log().entityId()));
      return new Storing(sessionOnlyEntities(tail), owners);
    }

    /// The entity whose storing decides that of the change: that of its event or delayed event, or, for a conditional
    /// trigger, the entity whose transition created it, whose event it refers to, rather than the one it fires on
    EntityKey owner(Change change) {
      return change.conditionalTrigger() != null ? conditionalTriggerOwners.get(change.conditionalTrigger()) : EntityKey.of(change);
    }

    /// Whether the change is stored. The deletion of the trigger which fired the transaction always is, as the trigger
    /// is stored whatever its entity, which may be one of the session only, e.g. of a model not stored, and would
    /// otherwise fire again.
    boolean isStored(Change change) {
      if (change.firedTrigger() != null)
        return true;
      if (sessionOnly.contains(owner(change)))
        return false;
      return change.newEvent() == null || change.newEvent().type().stored();
    }

  }

  /// The changes without the delayed events which a later event stored on their entity in the transaction replaces, as
  /// the event the delayed one is numbered for is taken. The changes are stored from the last calculated to the first,
  /// so such a delayed event would otherwise be stored after the event which replaces it, and be left pending.
  private static List<Change> withoutReplacedDelayedEvents(List<Change> changes, Storing storing) {
    var lastStored = new HashMap<EntityKey, Integer>();
    for (var change : changes)
      if (change.newEvent() != null && storing.isStored(change))
        lastStored.merge(EntityKey.of(change), change.newEvent().eventNumber(), Math::max);
    return changes.stream()
        .filter(change -> change.delayedEvent() == null
            || change.delayedEvent().eventNumber() > lastStored.getOrDefault(EntityKey.of(change), 0))
        .toList();
  }

  /// Fails for two delayed triggers on an entity with the same number in the transaction, e.g. of a transition and one
  /// on an event not stored which it triggers on its own entity, as an entity has at most one pending delayed trigger.
  /// One followed by a later event stored on the entity is left out, as that event replaces it, but of two with the same
  /// number one would quietly replace the other.
  private static void requireAtMostOneDelayedTriggerPerEntity(List<Change> changes) {
    record Key(EntityKey entity, int eventNumber) {}
    var delayed = new HashMap<Key, Change>();
    for (var change : changes) {
      if (change.delayedEvent() == null)
        continue;
      var other = delayed.putIfAbsent(new Key(EntityKey.of(change), change.delayedEvent().eventNumber()), change);
      if (other != null)
        throw new IllegalStateException(String.format(
            "%s and %s are both delayed triggers on %s/%s numbered %d in one transaction, but an entity has at most one " +
                "pending delayed trigger, so one would quietly replace the other",
            other.delayedEvent().type().name(),
            change.delayedEvent().type().name(),
            change.entityModel().name(),
            change.entityId(),
            change.delayedEvent().eventNumber()
        ));
    }
  }

  /// Fails for a change which is not stored but would have to be: a delayed or conditional trigger of an entity of the
  /// session only, which fires in a session of its own
  private static void requireStorable(List<Change> changes, Storing storing) {
    requireAtMostOneDelayedTriggerPerEntity(changes);
    for (var change : changes) {
      if (storing.isStored(change))
        continue;
      if (change.delayedEvent() != null || change.conditionalTrigger() != null)
        throw new IllegalStateException(String.format(
            "%s on %s/%s is a delayed or conditional trigger of an entity of the session only, whose events are not " +
                "stored, but it fires in a session of its own",
            change.delayedEvent() != null ? change.delayedEvent().type().name() : change.conditionalTrigger().type().name(),
            change.entityModel().name(),
            storing.owner(change).entityId()
        ));
    }
  }

  private void checkCircular(TransitionStep<?> tail, EntityModel entityModel, UUID entityId, InputEvent<?> inputEvent) {
    for (var c = tail; c != null; c = c.previous()) {
      switch (c) {
        case OutputStep<?> o
            when o.stepOutput().isAccepted() && o.stepOutput().accepted().entityModel().equals(entityModel): return;
        case InputStep<?> i when i.log().entityModel().equals(entityModel):
          throw new CircularChange(inputEvent, entityModel, entityId);
        default:
      }
    }
  }

  <T, I, O> Mono<TransitionStep<ProcessResult<O>>> calculateTriggeredEvent(
      EventTrigger<T, I, O> eventTrigger,
      TransitionStep<?> tail,
      T data,
      boolean skipCircularCheck,
      EventReference triggerEvent
  ) {
    I adaptedData = eventTrigger.eventSpec().inputAdapter() != null ? eventTrigger.eventSpec().inputAdapter().apply(data) : null;
    // The step results in the input step the event is calculated from, or in the step of its id existing
    return tail.inputStep().transaction()
        .step(tail, () -> triggered(eventTrigger, tail, data, adaptedData, skipCircularCheck, triggerEvent))
        .flatMap(triggered -> this.<I, O>calculate(triggered));
  }

  /// The step of the result of a triggered event: the given one, unless it is the input step of a transition,
  /// which the event is then calculated from
  @SuppressWarnings("unchecked")
  private <I, O> Mono<? extends TransitionStep<ProcessResult<O>>> calculate(TransitionStep<?> triggered) {
    return triggered instanceof InputStep<?> initial ?
        ((TransitionModel<I, O>) initial.transitionModel()).calculate((InputStep<I>) initial) :
        Mono.just((TransitionStep<ProcessResult<O>>) triggered);
  }

  /// The input step of the transition calculating the triggered event, or the step of its id existing, if it
  /// is an id event with an id found to exist when storing the changes
  private <T, I, O> Mono<TransitionStep<?>> triggered(
      EventTrigger<T, I, O> eventTrigger,
      TransitionStep<?> tail,
      T data,
      I adaptedData,
      boolean skipCircularCheck,
      EventReference triggerEvent
  ) {
    TransitionStep<ProcessResult<O>> idExists = idExists(eventTrigger, tail, adaptedData);
    if (idExists != null)
      return Mono.just(idExists);
    return Mono.deferContextual(ctx -> {
          // The entity model of the entity, which for a trigger on a group is the model of the session's entity
          EntityModel entityModel = eventTrigger.entityModel();
          UUID entityId = null;
          switch (eventTrigger.entitySelectors().getFirst().apply(data)) {
            case ByIdFromSession _ -> {
              try {
                EventLog sessionLog = logFromSteps(tail, entityModel);
                entityModel = sessionLog.entityModel();
                entityId = sessionLog.entityId();
              } catch (NoSuchElementException e) {
                throw new RuntimeException(e.getMessage() + "\nFound in Reactor context:\n" +
                    ctx.stream().map(x -> x.toString()).collect(joining("\n")));
              }
            }
            case ById s -> entityId = s.id();
            default -> {}
          }
          if (!skipCircularCheck && entityId != null) {
            checkCircular(tail, entityModel, entityId, new InputEvent<>(eventTrigger.eventSpec().eventType(), adaptedData));
          }
          return Mono.just(ctx);
        }
    ).flatMap(_ -> eventLog(eventTrigger, data, tail)
            .map(eventLog -> transitionModel(eventLog, eventTrigger.eventSpec().eventType()))
            .map(tuple -> {
              if (tuple.t2() != null)
                return tuple;
              throw new RejectedEvent(
                  eventTrigger.eventSpec().eventType(),
                  tuple.t1(),
                  tuple.t3()
              );
            })
            .retryWhen(RetrySpec.fixedDelay(3, Duration.ofMillis(500))
                .filter(e -> e instanceof RejectedEvent)
                // Avoid the "Thundering Herd" problem
                .jitter(1.0)
                .doAfterRetry(signal -> Debug.log(
                    System.currentTimeMillis() + ": Retried (" + signal.totalRetries() + ") due to " + signal.failure()
                        .getMessage()))
                // Rethrow the exception on exhaustion so it can be handled downstream
                .onRetryExhaustedThrow((_, signal) -> signal.failure())
            )
            .map(tuple -> new InputStep<>(
                null,
                tail,
                triggerEvent,
                tuple.t2(),
                tuple.t3(),
                tuple.t1().lastEventNumber() + 1,
                tuple.t1(),
                tail.inputStep().timestamp(),
                tail.inputStep().correlationId(),
                adaptedData,
                tail.inputStep().stateMachine(),
                tail.inputStep().transaction()
            ))
    );
  }

  public static class CircularChange extends RuntimeException {

    private final EntityModel entityModel;
    private final UUID entityId;

    public CircularChange(InputEvent<?> inputEvent, EntityModel entityModel, UUID entityId) {
      super(String.format(
          "%s on %s/id=%s is circular",
          inputEvent.eventType().name(),
          entityModel.name(),
          entityId.toString()
      ));
      this.entityModel = entityModel;
      this.entityId = entityId;
    }

    public EntityModel entityModel() {
      return entityModel;
    }

    public UUID entityId() {
      return entityId;
    }
  }

  public static class RejectedEvent extends RuntimeException {

    public enum Reason {
      /// No transition for the event from the current state of the entity
      InvalidState,
      /// The event can't be rolled back to the requested event number
      InvalidRollback,
      /// The entity selected by the trigger does not exist
      UnknownEntity
    }

    private final Reason reason;
    private final EventType<?, ?> eventType;
    private final EntityModel entityModel;
    private final UUID entityId;
    private final State currentState;

    private static String prefixMessage(EventType<?, ?> eventType, EntityModel entityModel, UUID entityId) {
      return String.format(
          "[%s] on [%s]/%s rejected",
          eventType != null ? eventType.name() : "<no event type>",
          entityModel != null ? entityModel.name() : "<no entity model>",
          entityId != null ? entityId : "<no entity id>"
      );
    }

    public RejectedEvent(EventType<?, ?> eventType, EventLog eventLog, State currentState) {
      super(String.format(
          "%s for state [%s] (%s)",
          prefixMessage(eventType, eventLog.entityModel(), eventLog.entityId()),
          currentState != null ? currentState.name() : "<no current state>",
          eventLog.events().stream().map(e -> e.type().name()).collect(joining(", "))
      ));
      this.reason = Reason.InvalidState;
      this.eventType = eventType;
      this.entityModel = eventLog.entityModel();
      this.entityId = eventLog.entityId();
      this.currentState = currentState;
    }

    public RejectedEvent(
        EventType<?, ?> eventType,
        EntityModel entityModel,
        UUID entityId,
        State currentState,
        int rollbackTo
    ) {
      super(String.format(
          "%s for state [%s]: Can't rollback to event number %d",
          prefixMessage(eventType, entityModel, entityId),
          currentState != null ? currentState.name() : "<no current state>",
          rollbackTo
      ));
      this.reason = Reason.InvalidRollback;
      this.eventType = eventType;
      this.entityModel = entityModel;
      this.entityId = entityId;
      this.currentState = currentState;
    }

    RejectedEvent(EventType<?, ?> eventType, UnknownEntity unknownEntity) {
      super(String.format(
          "%s: %s",
          prefixMessage(eventType, unknownEntity.entityModel(), unknownEntity.id()),
          unknownEntity.getMessage()
      ));
      this.reason = Reason.UnknownEntity;
      this.eventType = eventType;
      this.entityModel = unknownEntity.entityModel();
      this.entityId = unknownEntity.id();
      this.currentState = null;
    }

    public Reason reason() {
      return reason;
    }

    public EventType<?, ?> eventType() {
      return eventType;
    }

    public EntityModel entityModel() {
      return entityModel;
    }

    public UUID entityId() {
      return entityId;
    }
  }

  private <I, O> Tuple3<EventLog, TransitionModel<I, O>, State> transitionModel(
      EventLog eventLog,
      EventType<I, O> eventType
  ) {
    var traverser = traversers.get(eventLog.entityModel());
    if (traverser == null) throw new IllegalStateException("No traverser for " + eventLog.entityModel().name());
    var currentStateAndTransition = traverser.accept(eventLog, eventType);
    return tuple(eventLog, (TransitionModel<I, O>) currentStateAndTransition.t2(), currentStateAndTransition.t1());
  }

  /// The changes for the listener, in the order they were calculated, which is the reverse of the order they are
  /// collected in, so the accepted events of an entity are in the order of their numbers: the events and the delayed
  /// events and conditional triggers created, not the deletion of the trigger which fired the transaction
  private List<Listener.Change> toListenerFormat(List<Change> changes, Storing storing) {
    return changes.reversed().stream()
        .filter(change -> change.firedTrigger() == null)
        .map(change -> new Listener.Change(
                new Listener.Change.Entity(
                    change.entityModel().name(),
                    change.entityId()
                ),
                change.newEvent() != null ? new Listener.Change.Event(
                    change.newEvent().eventNumber(),
                    change.newEvent().type().name(),
                    change.severity(),
                    change.newEvent().timestamp(),
                    storing.isStored(change)
                ) : (change.delayedEvent() != null ? new Listener.Change.DelayedEvent(
                    change.delayedEvent().eventNumber(),
                    change.delayedEvent().type().name(),
                    change.delayedEvent().after()
                ) : null)
            )
        )
        .toList();
  }

  private Mono<Void> storeChanges(
      ZonedDateTime timestamp,
      String correlationId,
      TransitionStep<?> transitionStep
  ) {
    var storing = Storing.of(transitionStep);
    List<Change> changes = withoutReplacedDelayedEvents(withFiredTrigger(changes(transitionStep), transitionStep, storing), storing);
    Debug.log("Changes to store:\n" + changes.stream().map(Object::toString).collect(joining("\n")));
    return Mono.fromRunnable(() -> requireStorable(changes, storing))
        .thenReturn("")
        .delayUntil(_ -> delayer.apply(changes.stream()
            .filter(change -> change.newEvent() != null)
            .map(change -> correlationId + "-" + change.newEvent().type().id())
            .toList())
        )
        .flatMap(_ -> storage.changeState().execute(
                    timestamp,
                    correlationId,
                    changes.stream()
                        .filter(storing::isStored)
                        .toList()
                )
                .collectList()
                // The listener is told before the events are emitted to the one who triggered them, which fails if
                // that one has stopped listening, e.g. after the first event or a timeout
                // Not for a transaction whose only change is the deletion of the trigger which fired it
                .doOnNext(_ -> {
                  if (listener == null)
                    return;
                  var accepted = toListenerFormat(changes, storing);
                  if (!accepted.isEmpty())
                    listener.changeAccepted(correlationId, accepted);
                })
                .flatMap(x -> Mono.just("")
                    .contextWrite(ctx -> {
                          changes.stream()
                              .filter(change -> change.newEvent() != null)
                              .collect(Collectors.groupingBy(
                                  Change::entityId,
                                  toList()
                              ))
                              .forEach((entityId, collected) -> {
                                var changeList = inEmitOrder(collected);
                                Optional<Many<Event<?>>> responseSink = ctx.getOrEmpty("RS/" + entityId);
                                if (responseSink.isPresent()) {
                                  Debug.log("Found response sink for " + changeList.stream().map(c -> c.entityModel().name() + "/" + c.entityId() + "/" + c.newEvent().typeName()).toList());
                                  changeList.forEach(change -> responseSink.get().tryEmitNext(change.newEvent()).orThrow());
                                }
                              });
                          return ctx;
                        }
                    ).thenReturn(x)
                )
                // Forward outgoing requests (for guaranteed delivery this will be the first attempt)
                .transformDeferredContextual((publisher, ctx) -> publisher
                    .doOnNext(_ -> actionTriggers(transitionStep).forEach(actionTrigger ->
                        triggerAction(actionTrigger, correlationId, transitionStep)
                            .contextWrite(ctx).subscribe()))
                )
                .then()
        );
  }

  private <T> Mono<ProcessResult<?>> triggerAction(ActionTrigger<T> actionTrigger, String correlationId, TransitionStep<?> stage1) {
    return actionTrigger.trigger()
        .flatMap(triggerResult -> onEvent(
                correlationId,
                triggerResult,
                actionTrigger.eventLog(),
                stage1
            )
            .doOnError(e -> Debug.log("onEvent ERROR for " + triggerResult.eventType().name() + " on " + actionTrigger.eventLog().entityModel().name() + ":" + actionTrigger.eventLog().entityId() + ": " + e))
            .retryWhen(RetrySpec.fixedDelay(3, Duration.ofMillis(500))
                .filter(e -> e instanceof EventAlreadyExists)
                // Avoid the "Thundering Herd" problem
                .jitter(1.0)
                .doAfterRetry(signal -> Debug.log(
                    System.currentTimeMillis() + ": Retried (" + signal.totalRetries() + ") due to "
                        + signal.failure().getMessage()))
                // Rethrow the exception on exhaustion so it can be handled downstream
                .onRetryExhaustedThrow((_, signal) -> signal.failure())
            )

        );
  }

  /// The entity models the state machine runs: those it was given, and those their transitions trigger events on
  public List<EntityModel> entityModels() {
    return models.entityModels();
  }

  Traverser traverser(EntityModel entityModel) {
    return traversers.get(entityModel);
  }

}
