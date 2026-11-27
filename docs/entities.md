# Entities and events

## Entities

An entity type has a set of states, and transitions between them on event types. An entity is an instance of an
entity type, identified by a UUID. Its events are stored in its event log, and its current state is the state its
events lead to from the initial state `Begin`. The event logs are the source of truth; see the
[data model](data-model.md).

Every entity is created by a transition from `Begin`, as its state is found by replaying its events from there. The
state machine therefore refuses, when it is created, an entity type which has transitions but none from `Begin`, a
state with transitions which no transition from `Begin` leads to, as no entity could ever be in it, and a transition
from `Begin` which stays in `Begin`, as an entity in it couldn't be told from one which doesn't exist. Id and index
entities, which have no states, are the exception: all their events are taken from `Begin`.

The transitions of an entity type are declared with `TransitionModelBuilder`. A transition is taken on an event of a
type, assembles data from the event and the entity's event log, and can trigger events on other entities. The data of
the events it reads is [shared](transition-models.md#event-data-is-shared-so-nothing-may-change-it), so it must not
change it:

```java
onEvent(GreetingRequested).to(States.Greeted)
    .assemble(c -> c.log().entityId().toString())
    .trigger(GreetingResponseCreated).with(id -> id).on(Request).identifiedBy(entityIdFromSession())
    .output()
```

An entity type carries its transitions, by the state they are taken from. `EntityType.of(name, id, transitions)`
takes a method creating them, which is called when they are first asked for, so the transitions can refer to the model
itself and to other models declared the same way, e.g. constants which refer to each other:

```java
EntityType Greeting = EntityType.of("Greeting", UUID.fromString("..."), Greetings::transitions);
```

They are first asked for when the state machine is created, by which time the constants are all created. A trigger
on a model which isn't created yet, and so is null, fails as the transitions are created. The state machine reports
the refusals of every model it runs together, each once and named after its model, so that the models are fixed in one
go. A model whose transitions can't be created, e.g. as such a trigger fails, is reported with them, and doesn't hide
the refusals of the other models.

The state machine takes the transitions of each entity type from the model. It is given the entity types nothing
triggers events on, e.g. those of an [inbox](http-inbox.md), and runs them with the models their transitions trigger
events on, and so on, each once. A group triggered on adds no models, as its models are those of the group which are
run. When the state machine is created, it checks that every model it runs has an id of its own.

A model whose transitions depend on configuration, e.g. a lifetime, or the [outbox requests](http-outbox.md) it sends,
is created from the configuration rather than declared as a constant. That instance is the one triggered on, and
thereby run, so it is passed to the code triggering events on it, e.g. the inbox requests processing on it. Created
again, it is another instance with the same id, which may differ, e.g. be created from other configuration. The state
machine and the test API reject it, and only take the instance they run, which e.g. a test finds among the
`entityTypes()` of the state machine.

The entity an event is triggered on is selected by an `EntitySelector`: by its entity id, as a new entity, by an id it
has, as the last one of a group it is indexed in, or as the last one referencing an id by an index event. The ids and
index keys of entities are events of their own, of id entity types (`IdEntityType`) and index entity types
(`IndexEntityType`). An id is unique among the ids of its type, so an event claiming an id that is taken is rejected.

## Naming event types

An event in an event log is something that has happened, so an event type is named in the past tense: the subject
the event is about, followed by a past participle, e.g. `Registration accepted`, `Merchant suspended` or
`Response received`. The name is in sentence case, and the constant holding the event type has the same words in
upper camel case:

```java
EventType<Registration, Registration> RegistrationRejected = EventType.of(
    "Registration rejected",
    UUID.fromString("1a7e4c9d-2f63-4b05-8e9a-6d3c0b8f1e72"),
    DataType.json(Registration.class)
);
```

The subject keeps an event type apart from the state it leads to, which is a bare participle or adjective, e.g.
`TokenIssued` leads to `Issued`. A few kinds of event types follow a common pattern:

| Kind                                | Pattern                          | Example                                   |
|-------------------------------------|----------------------------------|-------------------------------------------|
| A request, e.g. from the HTTP inbox | `<subject> requested`            | `Token requested`, `Greeting requested`   |
| A request found to be invalid       | `<problem> detected`             | `Unknown merchant detected`               |
| An id of an id entity type         | `<id> claimed`                   | `Client id claimed`, `Message id claimed` |
| A key of an index entity type      | `<subject> referenced`/`indexed` | `Process referenced`, `Batch indexed`     |
| An entity read, not stored          | `<subject> looked up`            | `Merchant looked up`, `Status looked up`  |

Event types are identified by their id, not by their name, so an event type can be renamed without migrating the
events stored.

## Processing an event

An event enters the state machine through `StateMachine.onEvent`, from the HTTP inbox or from the application itself.
The state machine reads the event log of the selected entity, finds the transition from its current state on the
event's type, and follows the triggers of the transition from entity to entity. The new events of all the entities
involved are stored in one transaction, together with the triggers to be fired later. If another instance has
appended an event to one of the entities in the meantime, the transaction fails on the event number, and the event is
retried on the new event logs.

A transition has four kinds of triggers:

* **Event triggers** trigger an event on another entity, in the same transaction.
* **Delayed triggers** (`.after(delay)`) trigger an event on the entity after a delay, unless it has received another
  event first. They are stored in the database and fired by a worker of every state machine instance. Timeouts and
  retries are built from them. An entity has at most one pending delayed trigger, which the next event stored on it
  replaces, so a transition can't have two, which the state machine checks when it is created, and a transaction which
  would leave two on an entity with the same event number fails.
* **Conditional triggers** trigger an event when the last event of every referenced entity is one of the given types,
  e.g. when all the requests of a batch are completed. They are also fired by a worker.
* **Actions** are side effects outside the database, such as sending an HTTP request. They are run after the
  transaction is committed, and their outcome is fed back to the entity as a new event. The outcome is the next event
  after the one which triggered the action, like a delayed trigger: if another event is stored on the entity first,
  such as the timeout of an [outbox request](http-outbox.md), the outcome is dropped. It is not retried on the new event
  log, where it could be taken for the outcome of a later action, e.g. the response of a retried request. It is only
  retried if the transaction failed on the event number of another entity it triggers an event on, which may be the
  id or index entity of its own entity, with the same entity id.

Since side effects only happen after the events causing them are stored, an entity always knows what it has started.
If the instance stops before an action completes, a delayed trigger stored with the action, such as the timeout of an
outbox request, moves the entity on.

[Transitions, transactions and sessions](sessions.md) describes how an event is processed in transitions, stored in a
transaction, and followed by the transactions of the outcomes of its actions, in one session.

## Events and entities not stored

Every event is stored by default, which makes the event log the record of what happened. Some events are better left
out of it: one with a credential, e.g. a session cookie or a code, one with bulky data derived from other entities,
e.g. a rendered page, or one of no interest, e.g. a request for a static file. They can still be transitions, which are
tested, drawn in the state diagrams and told the [monitoring](monitoring.md), without being stored:

* **An event type which is not stored**, an `EventType.NotStored` from `EventType.notStored(name, id, ...)`, takes part in its transition like
  any other: it is calculated, may trigger events on other entities, which are stored if they are of stored types, is
  given to the one who triggered it and is told the listener. It is not written to the event log, so the state of an
  entity must not depend on it: on an entity which is stored, its transitions stay in their state (`toSelf()`), which
  the state machine checks when it is created. The next event stored on the entity takes its event number. A delayed
  event fires unless another event is stored on its entity first: it is numbered after the event of its transition,
  or of the transition chosen, if that is stored, and with its number if it isn't, and it replaces the delayed event
  pending on the entity. Its transition can't have a conditional trigger, which refers to the event.
  A delayed or conditional trigger may fire an event which isn't stored: the trigger is deleted with the changes of
  the event, as it is when the event is stored. An event reading an entity, for another entity to assemble from, is one, named `<subject> looked up`.
* **An entity of the session only** is created by an event which is not stored, i.e. one from `Begin`, or is of an
  entity type which is not stored, an `EntityType.NotStored`, e.g. one of `EntityType.notStored(name, id, transitions)`. None of its
  events are stored, whatever their types, so its transitions may go to any state, also on events not stored. The
  [session](sessions.md#sessions) finds it among the events of its transactions, but no other session does, so it can't have
  delayed or conditional triggers, which fire in a session of their own: the change fails if it has one. Nor can its
  transitions trigger events on an id or index model (`IdEntityType`, `IndexEntityType`), which would point to an
  entity which doesn't exist, and claim an id for good. The state machine refuses both when it is created. An inbox
  request which is [not stored](http-inbox.md#requests-not-stored) is one, and isn't indexed by the entity of the
  application.
* **A masked event** is stored with its output masked: `.masked(mask)` after `.output(dataAdapter)` gives the output
  as it is stored, which must not be null. A transition without an output has nothing to mask, so `.output()` without
  a data adapter, and a transition which chooses another one, can't be masked. The event in the transaction has the whole output, which its triggers, the one who triggered
  it and the transitions of the same transaction see, while the event log has the masked one. A later transaction, also
  one of the same [session](sessions.md#sessions), reads a stored entity from the event log, masked. The [inbox](http-inbox.md#masking) masks the credentials of the requests it stores this way.
  The data of a delayed or conditional trigger is not masked: it is the input of the event the trigger fires later, in
  a session of its own, so it is stored whole until then. Keep secrets out of it, and pass what the later transition
  needs by a reference, e.g. an id or a hash, rather than the secret itself, which no later session can have.

```java
// A page rendered from other entities, which is not stored
EventType<View, Page> PageViewed = EventType.notStored("Page viewed", UUID.fromString("..."), DataType.json(View.class), DataType.json(Page.class));

// A secret kept in the browser, of which the event log only has the hash
onEvent(SignInStarted).to(States.SigningIn)
    .assembleInput()
    .output(start -> start)
    .masked(Start::withoutSecret)
```

What isn't stored can't be read back, so it must be something the entity doesn't need later: the state it is in, a
value a later transition reads from its log, or a request a retry is compared with. A rollback rolls back stored events
only. Keep in the event log what the record needs, e.g. the hash of a secret and the claims of a user signed in, and
leave out the secret, the token and the page.
