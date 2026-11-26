# Diagrams

The diagram module draws the state diagram of an entity model with PlantUML, an ER diagram of a state machine's
complete model, where the entity models are related through the triggers of their transitions, and a
[sequence diagram](#sequence-diagrams) of the event log of an entity. The ER diagram leaves
out [id](data-model.md#idevent) and [index](data-model.md#indexevent) entities, with the triggers of events on them,
as they take much space and tell little about how the entities relate.

The diagrams in this documentation, of the [card payment template](cardpayments.md), the [apps](apps.md) and the
[HTTP inboxes](http-inbox.md) and [outboxes](http-outbox.md), are generated when the documentation is built, by the
`doc-diagrams` module. It is the only module depending on the diagram module, so PlantUML is never a dependency of the
libraries or the apps.

A code example in this documentation which creates an entity model has the diagram of that model under it. The
example is written out in `DocExamples` in `doc-diagrams`, as the documentation shows it, so the diagram is drawn from
the model the example creates and the example is known to compile. A change to such an example is made in both places.
The examples of the [getting started](getting-started.md) guide are taken from the hello world demo, whose diagrams are
drawn from the demo itself.

A diagram of something other than an entity model, such as how a [rollback](http-inbox.md#rollback) relates the event
logs of several entities, is drawn by hand in the DOT language of Graphviz, in the resources of `doc-diagrams`, and
rendered by `GraphvizFormatter` with `dot`.

A state diagram shows the details of an event when the mouse hovers over its name: its data and the events and actions
it triggers. An event whose transition has a [severity](monitoring.md#severity) above `Info` is named in bold, after a
symbol and in a colour for its severity: ⚠ in amber for `Warning`, ✖ in orange for `Error` and ‼ in red for `Critical`. An
event of a type which is [not stored](entities.md#events-and-entities-not-stored) is named in italics.

To keep a state diagram small, the transitions between the same two states are drawn as one arrow with their events
stacked, and the self transitions of a state as one loop. Choices with the same branches, i.e. the same events to the
same states, are drawn as one choice. Several transitions leading to the same choice from a state are thereby one
arrow: in the diagram of an [outbox request](http-outbox.md#atleastonce), a failed or dropped connection and a timeout
lead to one choice between retrying the request and giving it up.

The diagrams follow the colour scheme of the page. In dark mode, their arrows and the text on them are drawn in light
colours.

## Sequence diagrams

`SequenceDiagramFormatter` draws the event log of an entity as a sequence diagram, with related logs, such as those of
its [inbox](http-inbox.md) and [outbox](http-outbox.md) requests, e.g. read in a test with the [test API](testing.md),
and the entity models of the state machine:

```java
EventLog greeting = api.eventLog(Greeting, greetingId).block();
EventLog request = api.eventLog(GreetingRequest, requestId).block();
new SequenceDiagramFormatter(greeting, List.of(request), Inbox.entityModels()).formatToImage(new File("greeting.svg"));
```

`format()` gives the PlantUML source of the diagram instead.

An event doesn't record what caused it, so the logs are replayed through the transitions of their entity models, like
the state machine does, to find the transition each event took. This is done by `Replay` in the core library, which
doesn't depend on PlantUML. A transition with choices stores the event of the choice it took, so the transition of an
event is the path from the event triggered to the one stored, e.g. from `Request received` to `Request accepted` of an
inbox request, and has the triggers and actions of the whole path.

The events of a transaction have its timestamp, which ties the logs together: an event is drawn as triggered by a
trigger of the event starting its path, on its entity model or on a group of it such as `HttpInbox.Request`, in an
event of the same transaction, nested in that transition. That is all the logs tell. What they can't tell is deduced
from the entity models, without assumptions from elsewhere: an entity of a model which is not stored, such as the
`RequestRouting` of an inbox, never has a log, and an event of the logs no trigger is found for is drawn as triggered
by a transition of such a model, if that is the only transition of one triggering it. Its event has no number, as it
is never stored. So an inbox request is accepted from the routing of its request, and its response goes back to the
same lifeline. Models which are stored are not deduced from, as their logs can be given. The other events arrive from
outside the diagram.

- The other events a transition triggers are synchronous messages to the entity models they are triggered on, as
  they are stored in the same transaction. A conditional trigger, which is stored and fires later, is an asynchronous
  message. Events triggered on [id](data-model.md#idevent) and [index](data-model.md#indexevent) entities are left out,
  as in the ER diagram.
- The actions of a transition, such as forwarding an outbox request, leave the diagram, as they are run after the
  transaction is committed. What they get back arrives from outside in a later transaction.
- A rollback is followed by the triggers of the reverse transitions of the events it rolls back, in the colour of a
  reversal, and the events rolled back are struck through.
- The lifelines are in the order of the first message between two lifelines they take part in, so the diagram reads
  from left to right.

Events are named with the symbols and colours of their [severity](monitoring.md#severity), as in the state diagrams.

The logs don't hold the data the choices of a transition depend on, so where several choices lead to the same event,
the replay can't tell which was taken. It then takes the first one that triggers no event on the entity's own model, as
the next event may as well have been triggered by another entity, e.g. an application completing its inbox request.

A greeting of the [hello world demo](hello-world.md) with its inbox request, in one transaction: the routing of the
request triggers it on the inbox request, which dispatches it to a new greeting, which responds to the request:

<object data="../images/com.github.thxmasj.backend.diagram.sequence.Greeting.svg" type="image/svg+xml"></object>

[Open the Greeting sequence diagram in full size](images/com.github.thxmasj.backend.diagram.sequence.Greeting.svg)

A payment of the [card payment template](cardpayments.md), from the logs of its inbox request, the payment, its
settlement and its two outbox requests, in three transactions after the settlement was opened:

1. The request is accepted, and dispatched to a new payment, which sends an authentication request.
2. The authentication is accepted, and the payment sends an authorisation request to the acquirer and completes the
   inbox request, whose response goes back to the routing.
3. The acquirer approves the authorisation, and the payment credits the merchant in the settlement and notifies the
   merchant of the approval.

<object data="../images/com.github.thxmasj.backend.diagram.sequence.Payment.svg" type="image/svg+xml"></object>

[Open the Payment sequence diagram in full size](images/com.github.thxmasj.backend.diagram.sequence.Payment.svg)

The logs of these examples are written out in `ExampleLogs` in `doc-diagrams`, by the names of their events and the
transactions storing them. The template leaves the inbox requests to the application, so the payment request is
`PaymentRequest` in `DocExamples`, with outbox requests calling back to the payment as an application's would.
