package com.github.thxmasj.backend.http.inbox;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.internal.Memoized;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// The `RequestRouting` entity model of an inbox, routing to its requests. Its entities are not stored, but are of the
/// session of the request only (see [EntityModel#stored()]): the inbox requests store the requests and responses. The
/// models of all inboxes have the same id, and are in the group [#RequestRouting].
final class RequestRoutingModel implements EntityModel {

  /// The routing model of any inbox, which an inbox request responds to by triggering [HttpInbox#ResponseCreated] on
  /// it, identified by the session
  static final EntityModelGroup RequestRouting = EntityModelGroup.of(
      "RequestRouting",
      UUID.fromString("7c2e9a41-5d8b-4f36-b0e7-1a4c6f8d2b95"),
      model -> model instanceof RequestRoutingModel
  );

  private static final UUID ID = UUID.fromString("20755705-fc81-4228-a1a7-5e13d6e3c153");

  private final Memoized<Map<State, List<TransitionModel<?, ?>>>> transitions;

  RequestRoutingModel(List<HttpInboxRequest<?>> requests) {
    this.transitions = new Memoized<>(() -> TransitionModels.requestRoutingTransitions(requests), "transitions of RequestRouting");
  }

  @Override
  public String name() {
    return "RequestRouting";
  }

  @Override
  public UUID id() {
    return ID;
  }

  /// The routing is of the session of the request only, as the inbox request it routes to stores the request and the
  /// response, which it reads back to recognise a retry
  @Override
  public boolean stored() {
    return false;
  }

  @Override
  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions.get();
  }

  @Override
  public String toString() {
    return name();
  }
}
