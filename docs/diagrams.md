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
its [inbox](http-inbox.md) and [outbox](http-outbox.md) requests, e.g. read in a test with the [test API](testing.md):

```java
EventLog greeting = api.eventLog(Greeting, greetingId).block();
EventLog request = api.eventLog(GreetingRequest, requestId).block();
new SequenceDiagramFormatter(greeting, List.of(request)).formatToImage(new File("greeting.svg"));
```

`format()` gives the PlantUML source of the diagram instead.

An event doesn't record what caused it, so the logs are replayed through the transitions of their entity models, like
the state machine does, to find the transition each event took. This is done by `Replay` in the core library, which
doesn't depend on PlantUML. The transitions' triggers are the messages of the diagram:

- Each entity whose log is drawn has a lifeline. The events of a transaction have its timestamp, so an event is drawn
  as triggered by a trigger of its type on its entity model, or on a group of it such as `HttpInbox.Request`, in an
  event of the same transaction, nested in that transition. The other events arrive from outside the diagram, as
  nothing in the logs triggered them.
- The other events a transition triggers are synchronous messages to the entity models they are triggered on, as
  they are stored in the same transaction. A conditional trigger, which is stored and fires later, is an asynchronous
  message.
- The actions of a transition leave the diagram, as they are run after the transaction is committed.
- An event of a transition with choices has the triggers and actions of the choices that led to it, like the inbox
  request below, whose dispatch triggers the greeting.
- A rollback is followed by the triggers of the reverse transitions of the events it rolls back, in the colour of a
  reversal, and the events rolled back are struck through.
- The lifelines are in the order they are first drawn, so the diagram reads from left to right.

Events are named with the symbols and colours of their [severity](monitoring.md#severity), as in the state diagrams.

The logs don't hold the data the choices of a transition depend on, so where several choices lead to the same event,
the replay can't tell which was taken. It then takes the first one that triggers no event on the entity's own model, as
the next event may as well have been triggered by another entity, e.g. an application completing its inbox request.

A greeting of the [hello world demo](hello-world.md) with its inbox request, in one transaction: the request is accepted and
dispatched to a new greeting, which responds to the request:

<object data="../images/com.github.thxmasj.backend.diagram.sequence.Greeting.svg" type="image/svg+xml"></object>

[Open the Greeting sequence diagram in full size](images/com.github.thxmasj.backend.diagram.sequence.Greeting.svg)

The log of an [`AtMostOnce` authorisation](http-outbox.md#atmostonce) alone, in three transactions: its response
never came, and the payment rolls it back by a reversal:

<object data="../images/com.github.thxmasj.backend.diagram.sequence.Authorisation.svg" type="image/svg+xml"></object>

[Open the Authorisation sequence diagram in full size](images/com.github.thxmasj.backend.diagram.sequence.Authorisation.svg)

The logs of these examples are written out in `ExampleLogs` in `doc-diagrams`, by the names of their events and the
transactions storing them.
