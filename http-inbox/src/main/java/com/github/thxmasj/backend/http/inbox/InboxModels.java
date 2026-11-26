package com.github.thxmasj.backend.http.inbox;

import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.internal.Memoized;
import com.github.thxmasj.backend.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/// The entity types of an inbox. The responses of the requests are checked when the entity types are first asked
/// for, e.g. by the state machine, rather than when the inbox is created, which asks for no transitions, so that the
/// inbox may be a constant declared before the models of the application and what their transitions refer to.
final class InboxModels implements HttpInbox {

  private final EntityType requestRouting;
  private final List<HttpInboxRequest<?>> requests;
  private final Memoized<List<EntityType>> entityTypes;
  private final String correlationIdHeader;
  private final EventTrigger.Immediate<HttpRequest<byte[]>, HttpRequest<byte[]>, ?> trigger;

  private InboxModels(List<HttpInboxRequest<?>> requests) {
    this.requestRouting = new RequestRoutingModel(requests);
    this.requests = List.copyOf(requests);
    this.entityTypes = new Memoized<>(this::checkedEntityTypes, "entity types of the inbox");
    this.correlationIdHeader = "X-Correlation-Id";
    this.trigger = trigger(requestRouting, correlationIdHeader);
  }

  /// The inbox with the entity types of the other, the same instances, so that a state machine running the one runs
  /// the other
  private InboxModels(InboxModels other, String correlationIdHeader) {
    this.requestRouting = other.requestRouting;
    this.requests = other.requests;
    this.entityTypes = other.entityTypes;
    this.correlationIdHeader = correlationIdHeader;
    this.trigger = trigger(requestRouting, correlationIdHeader);
  }

  static InboxModels of(List<HttpInboxRequest<?>> requests) {
    return new InboxModels(requests);
  }

  private static EventTrigger.Immediate<HttpRequest<byte[]>, HttpRequest<byte[]>, ?> trigger(
      EntityType requestRouting,
      String correlationIdHeader
  ) {
    return EventTrigger.trigger(RequestReceived, requestRouting)
        .withCorrelationId(request -> correlationId(request, correlationIdHeader));
  }

  private static String correlationId(HttpRequest<byte[]> request, String correlationIdHeader) {
    String correlationId = request.headerValue(correlationIdHeader);
    if (correlationId == null || correlationId.isBlank())
      return "SYS-" + ThreadLocalRandom.current().nextLong(1000000000000000000L, Long.MAX_VALUE);
    return correlationId;
  }

  @Override
  public HttpInbox correlationIdHeader(String correlationIdHeader) {
    return new InboxModels(this, correlationIdHeader);
  }

  @Override
  public EntityType requestRouting() {
    return requestRouting;
  }

  @Override
  public List<HttpInboxRequest<?>> requests() {
    return requests;
  }

  /// @throws IllegalArgumentException if an inbox request doesn't take a response its processes may give
  @Override
  public List<EntityType> entityTypes() {
    return entityTypes.get();
  }

  @Override
  public EventTrigger.Immediate<HttpRequest<byte[]>, HttpRequest<byte[]>, ?> trigger() {
    return trigger;
  }

  private List<EntityType> checkedEntityTypes() {
    TransitionModels.checkResponses(requests);
    var models = new ArrayList<EntityType>();
    models.add(requestRouting);
    models.addAll(requests);
    models.add(MessageIds);
    models.add(Index);
    return List.copyOf(models);
  }
}
