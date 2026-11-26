package com.github.thxmasj.backend.core;

import com.github.thxmasj.backend.core.EventType.Rollback;
import java.util.List;
import java.util.UUID;

public interface BuiltinEventTypes {

  Rollback EventsRolledBack = EventType.rollback(
      "Events rolled back",
      UUID.fromString("58aa1e1f-e75d-40ba-9e87-ca7fc42e491d")
  );
  EventType<Void, State> StatusLookedUp = EventType.notStored(
      "Status looked up",
      UUID.fromString("324dc75d-e83d-4b9b-8ad9-b3521184def6"),
      DataType.none(),
      DataType.json(State.class)
  );

  List<EventType<?, ?>> ALL = List.of(
      EventsRolledBack,
      StatusLookedUp
  );

}
