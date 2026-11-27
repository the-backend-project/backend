package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.TransitionModelBuilder.log;
import static java.util.Collections.unmodifiableList;

import com.github.thxmasj.backend.core.EventType.Rollback.Data;
import com.github.thxmasj.backend.core.TransitionModelBuilder.Filter;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ModelContext;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Accepted;
import com.github.thxmasj.backend.core.Tuples.Tuple4;
import com.github.thxmasj.backend.core.internal.EventTypes;
import com.github.thxmasj.backend.core.internal.TransitionStep.ChoiceStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.InputStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.OutputStep;
import java.util.ArrayList;
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

/// The model of a transition of an entity type on an event type, built by [TransitionModelBuilder]. It calculates the
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

  TransitionModel<Tuple4<I, EntityType, EventType<?, ?>, String>, O> rejectModel() {
    return null;
  }

  /// Calculates the transition from its input step, without storing its changes. The input step is internal to the
  /// library, as its package is exported to the library's own modules only, so only the library calls this: the state
  /// machine, and the tests of the transitions of the library's modules.
  public Mono<OutputStep<O>> calculate(InputStep<I> inputStep) {
    // Without the input, which may be a secret, e.g. the credentials of a request
    log(modelContext, "calculate [" + eventType().name() + "] on [" + inputStep.log().entityType().name() + ":" + inputStep.log().entityId() + "]");
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

  /// The transitions whose events the transition may store: its own if it has no choices, or else those it may choose,
  /// which are all its outcomes, as a transition with choices always ends in `otherwise`, and its own event is never
  /// stored
  List<TransitionModel<?, ?>> leaves() {
    ArrayList<TransitionModel<?, ?>> leaves = new ArrayList<>();
    if (filters().isEmpty()) {
      // transition is a leaf
      leaves.add(this);
    } else {
      filters().stream().map(f -> f.alternative().model()).forEach(alternativeModel -> leaves.addAll(alternativeModel.leaves()));
    }
    return unmodifiableList(leaves);
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

  /// Whether the transition's event has no data, as it ends in `output()` without a data adapter
  boolean withoutData() {
    return modelContext.withoutData();
  }

  /// The output step with its event marshalled, after its mask, if it is stored. The output step of a transition makes
  /// it, so that a failure of masking or marshalling the event is one of the transition, the event is marshalled once,
  /// and the step the transaction records is the one with the event as it is stored. An event which isn't stored is
  /// never marshalled, nor is the event of a transition which chose another one, which isn't stored either.
  static <O> OutputStep<O> marshalledIfStored(OutputStep<O> step) {
    if (!(step.previous() instanceof ChoiceStep<?>)
        && step.stepOutput() instanceof Accepted<O>(Event<O> event, EntityType entityType, Severity severity)
        && EventTypes.stored(event.type())
        && !step.inputStep().log().isSessionOnlyWith(event))
      return new OutputStep<>(step.previous(), new Accepted<>(event.marshalled(), entityType, severity));
    return step;
  }

  /// A transition with an output, which may be masked when its event is stored
  public static final class WithOutput<I, O> extends TransitionModel<I, O> {

    /// The steps of the transition with its output masked by a mask, or not if it is null
    private final Function<UnaryOperator<O>, Function<Mono<InputStep<I>>, Mono<OutputStep<O>>>> masking;

    WithOutput(ModelContext<I, O> modelContext, Function<UnaryOperator<O>, Function<Mono<InputStep<I>>, Mono<OutputStep<O>>>> masking) {
      super(modelContext, masking.apply(null));
      this.masking = masking;
    }

    /// The transition with its output masked when its event is stored, e.g. to keep a secret or bulky data out of the
    /// event log. The event in the transaction has the whole output: the transition's triggers, the one who triggered
    /// the event, and the transitions of the same transaction reading the entity's log see it. The event log stores
    /// the masked output, which is what a later transaction reads of a stored entity, also one of the same session, so
    /// the state of the entity must not depend on what is masked. A transition which chooses another one stores the
    /// event of the chosen transition, with the mask of that one. The event is masked in the transition's output
    /// step, the one the transaction records.
    ///
    /// @param mask gives the output as it is stored, which must not be null
    public TransitionModel<I, O> masked(UnaryOperator<O> mask) {
      Objects.requireNonNull(mask, "mask is null");
      return new TransitionModel<>(modelContext(), masking.apply(mask));
    }

  }


}
