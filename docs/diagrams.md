# Diagrams

The diagram module draws three kinds of diagrams:

- [State diagrams](#state-diagrams-of-entity-types) of the state machines of the entity types, the
  [entity models](entities.md) in the code: what can happen to an entity.
- [Entity relationship diagrams](#entity-relationship-diagrams-of-bounded-contexts) of the
  [bounded contexts](principles.md#domain-driven-design), i.e. the complete models of the state machines: how their
  entity types relate.
- [Sequence diagrams](#sequence-diagrams-of-event-logs) of the event logs of actual entities: what did happen to an
  entity, and to the entities it triggered events on, transaction by transaction.

The state and entity relationship diagrams are drawn from the transitions alone, so they are never out of date.

The examples on this page are all of one bounded context, the payments, merchants and settlements of the
[card payment template](cardpayments.md): the state diagram of a payment, how the three relate, and the event logs of
an actual payment.

The diagrams follow the colour scheme of the page. In dark mode, their arrows and the text on them are drawn in light
colours. Events whose transitions have a [severity](monitoring.md#severity) above `Info` are named in bold, after a
symbol and in a colour for their severity: ⚠ in amber for `Warning`, ✖ in orange for `Error` and ‼ in red for
`Critical`. `formatToImage(..)` writes a diagram as SVG, and `format()` gives its source text.

The diagrams in this documentation are generated when it is built, by the `doc-diagrams` module. It is the only module
depending on the diagram module, so the diagram module and its dependencies are never dependencies of the libraries or
the apps. A code example in this documentation which creates an entity type has the state diagram of that entity type
under it. The example is written out in `DocExamples` in `doc-diagrams`, as the documentation shows it, so the diagram
is drawn from the model the example creates and the example is known to compile. A change to such an example is made in
both places. The examples of the [getting started](getting-started.md) guide are taken from the hello world demo, and
the diagrams of the [card payment template](cardpayments.md) and the [apps](apps.md) are drawn from the template and the
apps themselves. A diagram of something else, such as how a [rollback](http-inbox.md#rollback) relates the event logs of
several entities, is drawn by hand in the DOT language of Graphviz, in the resources of `doc-diagrams`, and rendered by
`GraphvizFormatter` with `dot`.

## State diagrams of entity types

`StateDiagramFormatter` draws the state diagram of an entity type. `only(..)` draws a part of it, the given states and
the transitions to them, for an entity type too large to show in full, such as the payment of the card payment
template, whose main path is:

```java
new StateDiagramFormatter(cardPayments.payment())
    .only(ProcessingAuthentication, ProcessingAuthorisation, Authorised)
    .formatToImage(new File("payment.svg"));
```

<object data="../images/com.github.thxmasj.backend.diagram.state.Payment.svg" type="image/svg+xml"></object>

[Open the state diagram of the main path of a payment in full size](images/com.github.thxmasj.backend.diagram.state.Payment.svg)

A state diagram shows the details of an event when the mouse hovers over its name: its data and the events and actions
it triggers. An event of a type which is [not stored](entities.md#events-and-entities-not-stored) is named in italics.

To keep a state diagram small, the transitions between the same two states are drawn as one arrow with their events
stacked, and the self transitions of a state as one loop. Choices with the same branches, i.e. the same events to the
same states, are drawn as one choice. Several transitions leading to the same choice from a state are thereby one
arrow: in the diagram of an [outbox request](http-outbox.md#atleastonce), a failed or dropped connection and a timeout
lead to one choice between retrying the request and giving it up. A choice drawn with `only(..)` has the alternatives
leading to the states given.

## Entity relationship diagrams of bounded contexts

`EntityRelationshipDiagramFormatter` draws the entity relationship diagram of a bounded context, from the entity types
given to its state machine and those their transitions trigger events on. `only(..)` draws the entity types of the
domain, such as the payments, merchants and settlements of the card payment template, without the inbox and outbox
requests, which relate to most of them:

```java
new EntityRelationshipDiagramFormatter("cardpayment", cardPayments.entityModels())
    .only(cardPayments.merchant(), cardPayments.payment(), cardPayments.settlement())
    .compact()
    .formatToImage("diagrams");
```

<object data="../images/com.github.thxmasj.backend.diagram.cardpayment.er.svg" type="image/svg+xml"></object>

[Open the entity relationship diagram of the card payments in full size](images/com.github.thxmasj.backend.diagram.cardpayment.er.svg)

The entity types are related through the triggers of their transitions: an arrow from one entity type to another
tells that an event on the first triggers an event on the other, e.g. an approved authorisation of a payment credits
the merchant in its settlement. Each entity type lists the event types it accepts, unless the diagram is `compact()`,
which only shows the event types on the relations. A trigger on a group, such as `HttpInbox.Request`, relates to each
entity type of the group, and triggers of reversals are red. The diagram leaves out [id](data-model.md#idevent) and
[index](data-model.md#indexevent) entities, with the triggers of events on them, as they take much space and tell
little about how the entities relate.

## Sequence diagrams of event logs

`SequenceDiagramFormatter` draws the event log of an entity as a sequence diagram, with related logs, such as those of
its [inbox](http-inbox.md) and [outbox](http-outbox.md) requests, e.g. read in a test with the [test API](testing.md),
and the entity types of the state machine:

```java
EventLog payment = api.eventLog(cardPayments.payment(), paymentId).block();
List<EventLog> related = List.of(paymentRequest, authentication, authorisation, settlement);
new SequenceDiagramFormatter(payment, related, inbox.entityModels()).formatToImage(new File("payment.svg"));
```

### How the logs are replayed

An event doesn't record what caused it, so the logs are replayed through the transitions of their entity types, like
the state machine does, to find the transition each event took. This is done by `Replay` in the core library, which
doesn't depend on the diagram module. A transition with choices stores the event of the choice it took, so the transition of an
event is the path from the event triggered to the one stored, e.g. from `Request received` to `Request accepted` of an
inbox request, and has the triggers and actions of the whole path.

The events of a transaction have its timestamp, which ties the logs together: an event is taken to be triggered by a
trigger of the event starting its path, on its entity type or on a group of it such as `HttpInbox.Request`, in an
event of the same transaction.

That is all the logs tell. What they can't tell is deduced from the entity types, without assumptions from elsewhere:

- An entity of a model which is not stored, such as the `RequestRouting` of an inbox, never has a log. An event of the
  logs no trigger is found for is taken to be triggered by a transition of such a model, if that is the only
  transition of one triggering it. So an inbox request is accepted from the routing of its request, and its response
  goes back to the routing. Models which are stored are not deduced from, as their logs can be given.
- The state machine gives the outcome of an action, such as the response to an outbox request, back to the entity
  which ran it, in a transaction of its own, but which event that is isn't declared. An event no transition of the
  models triggers, neither at once, conditionally nor delayed, can only be the outcome of an action or be given from
  outside. It is taken to be the outcome of the action of the latest earlier transaction of its entity whose outcome
  isn't found yet.

The logs don't hold the data the choices of a transition depend on, so where several choices lead to the same event,
the replay can't tell which was taken. It then takes the first one that triggers no event on the entity's own model, as
the next event may as well have been triggered by another entity, e.g. an application completing its inbox request.

### What is drawn

- The steps of each transaction are drawn in a box, named with its number in the diagram and its timestamp. Everything
  in a box was stored together, or nothing would have been.
- Each entity whose log is drawn has a lifeline, and so has each model of the deduced entities. An event is numbered
  as in its log. A deduced event has no number, as it is never stored, and is named in italics.
- An event triggered by an event of the replay is a synchronous message from that event's lifeline, nested in its
  transition, as the two are stored in the same transaction. The other events arrive from outside the diagram.
- The other events a transition triggers are synchronous messages to the entity types they are triggered on. A
  conditional trigger, which is stored and fires later, is an asynchronous message. Events triggered on id and index
  entities are left out, as in the entity relationship diagram.
- An action is an asynchronous message to a lifeline of its own, for each entity and action, after the box of its
  transaction, as it is run after the transaction is committed. Its outcome comes back from that lifeline in a later
  transaction.
- A rollback is followed by the triggers of the reverse transitions of the events it rolls back, in the colour of a
  reversal, and the events rolled back are struck through.
- The lifelines are in the order of the first message between two lifelines they take part in, so the diagram reads
  from left to right.

### Example

A payment of the card payment template, from the logs of its inbox request, the payment, its settlement and its two
outbox requests, along the main path of the state diagram above:

1. The settlement is opened, before the payment, by the cut-off of the previous one, whose log isn't given.
2. The request is accepted, and dispatched to a new payment, which sends an authentication request.
3. The authentication is accepted, and the payment sends an authorisation request to the acquirer and completes the
   inbox request, whose response goes back to the routing.
4. The acquirer approves the authorisation, and the payment credits the merchant in the settlement and notifies the
   merchant of the approval.

<object data="../images/com.github.thxmasj.backend.diagram.sequence.Payment.svg" type="image/svg+xml"></object>

[Open the Payment sequence diagram in full size](images/com.github.thxmasj.backend.diagram.sequence.Payment.svg)

The logs of this example are written out in `ExampleLogs` in `doc-diagrams`, by the names of their events and the
transactions storing them. The template leaves the inbox requests to the application, so the payment request is
`PaymentRequest` in `DocExamples`, with outbox requests calling back to the payment as an application's would.
