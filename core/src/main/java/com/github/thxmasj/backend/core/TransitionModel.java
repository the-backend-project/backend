package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.TransitionModelBuilder.log;

import com.github.thxmasj.backend.core.BasicEventType.Rollback.Data;
import com.github.thxmasj.backend.core.TransitionModelBuilder.Filter;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ModelContext;
import com.github.thxmasj.backend.core.Tuples.Tuple4;
import com.github.thxmasj.backend.core.internal.TransitionStep.InputStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.OutputStep;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;

/// The model of a transition of an entity model on an event type, built by [TransitionModelBuilder]. It calculates the
/// steps of the transition from its input step to its output step.
public class TransitionModel<I, O> {

  private final ModelContext<I, O> modelContext;
  private final Function<Mono<InputStep<I>>, Mono<OutputStep<O>>> chain;

  @Override
  public int hashCode() {
    return Objects.hash(modelContext.eventType(), modelContext.to());
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj instanceof TransitionModel<?, ?> other) {
      return Objects.equals(this.modelContext.to(), other.modelContext.to())
          && Objects.equals(this.modelContext.eventType(), other.modelContext.eventType());
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("onEvent(%s).to(%s)", (modelContext.eventType() != null ? modelContext.eventType().name() : "N/A") , modelContext.to() != null ? modelContext.to().name() : "<self>");
  }

  ModelContext<I, O> modelContext() {
    return modelContext;
  }

  Function<Mono<InputStep<I>>, Mono<OutputStep<O>>> chain() {
    return chain;
  }

  TransitionModel(
      ModelContext<I, O> modelContext,
      Function<Mono<InputStep<I>>, Mono<OutputStep<O>>> chain
  ) {
    this.modelContext = modelContext;
    this.chain = chain;
  }

  public TransitionModel<Data, Data> reverseModel() {
    return modelContext.reverseModel();
  }

  TransitionModel<Tuple4<I, EntityModel, EventType<?, ?>, String>, O> rejectModel() {
    return null;
  }

  /// Calculates the transition from its input step, without storing its changes. The input step is internal to the
  /// library, as its package is exported to the library's own modules only, so only the library calls this: the state
  /// machine, and the tests of the transitions of the library's modules.
  public Mono<OutputStep<O>> calculate(InputStep<I> inputStep) {
    log(modelContext, "calculate [" + eventType().name() + "] on [" + inputStep.log().entityModel().name() + ":" + inputStep.log().entityId() + "] (input: " + inputStep.input() + ")");
    return chain.apply(Mono.just(inputStep))
        .doOnError(e -> inputStep.stateMachine().transitionFailed(inputStep, e));
  }

  Mono<OutputStep<O>> calculateReverse(Mono<InputStep<I>> initial) {
    return chain.apply(initial);
  }

  public EventType<I, O> eventType() {
    return modelContext.eventType();
  }

  public State toState() {
    return modelContext.to();
  }

  /// The severity of the event the transition accepts, as declared with
  /// [TransitionModelBuilder.WithToState#severity(Severity)]
  public Severity severity() {
    return modelContext.severity();
  }

  @SafeVarargs
  public static Map<State, List<TransitionModel<?, ?>>> mergeModels(
      Map<State, List<TransitionModel<?, ?>>> ...lists
  ) {
    return Arrays.stream(lists).flatMap(l -> l.entrySet().stream())
        .collect(Collectors.toMap(
            Entry::getKey,
            Entry::getValue,
            (e1, e2) -> Stream.concat(e1.stream(), e2.stream()).toList()
        ));
  }


  public List<Filter<?,?,?>> filters() {
    return modelContext.filters();
  }

  public List<EventTrigger<?, ?, ?>> triggers() {
    return modelContext.triggers();
  }

  /// The types of the delayed events the transition triggers
  List<EventType<?, ?>> delayedEventTypes() {
    return modelContext.delayedEventTypes();
  }

  public List<Action<?>> actions() {
    return modelContext.actions();
  }

  /// The mask of the output of the transition, or null if it isn't masked
  UnaryOperator<O> mask() {
    return modelContext.mask();
  }

  /// A transition with an output, which may be masked when its event is stored
  public static final class WithOutput<I, O> extends TransitionModel<I, O> {

    WithOutput(ModelContext<I, O> modelContext, Function<Mono<InputStep<I>>, Mono<OutputStep<O>>> chain) {
      super(modelContext, chain);
    }

    /// The transition with its output masked when its event is stored, e.g. to keep a secret or bulky data out of the
    /// event log. The event in the transaction has the whole output: the transition's triggers, the one who triggered
    /// the event, and the transitions of the same transaction reading the entity's log see it. The event log stores
    /// the masked output, which is what a later transaction reads of a stored entity, also one of the same session, so
    /// the state of the entity must not depend on what is masked. A transition which chooses another one stores the
    /// event of the chosen transition, with the mask of that one.
    ///
    /// @param mask gives the output as it is stored, or null for no data
    public TransitionModel<I, O> masked(UnaryOperator<O> mask) {
      return new TransitionModel<>(modelContext().withMask(Objects.requireNonNull(mask, "mask is null")), chain());
    }

  }

}
