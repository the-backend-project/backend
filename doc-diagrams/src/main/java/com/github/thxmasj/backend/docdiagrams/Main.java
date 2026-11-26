package com.github.thxmasj.backend.docdiagrams;

import static com.github.thxmasj.backend.http.inbox.HttpInbox.MessageIds;
import static com.github.thxmasj.backend.docdiagrams.ExampleLogs.at;
import static com.github.thxmasj.backend.docdiagrams.ExampleLogs.log;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.Ids;

import com.github.thxmasj.backend.backoffice.Backoffice;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.diagram.EntityRelationshipDiagramFormatter;
import com.github.thxmasj.backend.diagram.GraphvizFormatter;
import com.github.thxmasj.backend.diagram.StateDiagramFormatter;
import com.github.thxmasj.backend.diagram.SequenceDiagramFormatter;
import com.github.thxmasj.backend.helloworld.Greetings;
import com.github.thxmasj.backend.http.inbox.HttpInbox;
import com.github.thxmasj.backend.identityprovider.Authorizations;
import com.github.thxmasj.backend.identityprovider.Clients;
import com.github.thxmasj.backend.identityprovider.Inbox;
import com.github.thxmasj.backend.identityprovider.Tokens;
import com.github.thxmasj.backend.templates.cardpayment.DummyTransitions;
import com.github.thxmasj.backend.templates.cardpayment.PaymentState;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/// Generates the diagrams of the documentation site into `docs/images`. Run from the repository root.
public class Main {

  private static final String directory = "docs/images/";

  static void main() throws IOException {
    httpOutbox();
    httpInbox();
    sequenceDiagrams();
    diagramExamples();
    cardPayments();
    identityProvider();
    helloWorld();
    backoffice();
  }

  /// The examples of the HTTP outbox page, each drawn under its code
  private static void httpOutbox() throws IOException {
    var name = "com.github.thxmasj.backend.http.outbox";
    var notification = DocExamples.MerchantNotification;
    stateDiagram(name + ".MerchantNotification", notification);
    var authorisation = DocExamples.Authorisation;
    stateDiagram(name + ".Authorisation", authorisation);
  }

  /// The examples of the HTTP inbox page, each drawn under its code, and how a rollback finds the request and the
  /// events it rolls back
  private static void httpInbox() throws IOException {
    var name = "com.github.thxmasj.backend.http.inbox";
    var placeOrder = DocExamples.PlaceOrder;
    stateDiagram(name + ".PlaceOrder", placeOrder);
    var enrolmentResult = DocExamples.EnrolmentResultReceived;
    stateDiagram(name + ".EnrolmentResultReceived", enrolmentResult);
    var withRollback = DocExamples.PlaceOrderWithRollback;
    stateDiagram(name + ".PlaceOrderWithRollback", withRollback);
    stateDiagram(name + ".RequestRouting", HttpInbox.of(List.of(withRollback)).requestRouting());
    stateDiagram(MessageIds);
    graphviz("http-inbox-rollback.dot", name + ".Rollback");
  }

  /// The example of a sequence diagram on the diagrams page, with the entity models of its state machine, from which the
  /// routing of the inbox request, which isn't stored, is deduced: a payment of the card payment template, requested
  /// from the inbox, and authenticated and authorised by outbox requests, in three transactions
  private static void sequenceDiagrams() throws IOException {
    var name = "com.github.thxmasj.backend.diagram.sequence.";
    var models = EntityModel.reachable(DocExamples.PaymentInbox.entityModels());
    sequenceDiagram(
        name + "Payment",
        models,
        log(DocExamples.CardPayments.payment(),
            at(1, "Payment request validated"), at(2, "Authorization requested"), at(3, "Authorization approved")),
        log(DocExamples.PaymentRequest, at(1, "Request accepted"), at(2, "Request completed")),
        log(model(models, "Authentication"), at(1, "Request dispatched"), at(2, "Request accepted")),
        log(model(models, "Authorisation"), at(2, "Request dispatched"), at(3, "Request accepted")),
        // Opened before the payment, by the cut-off of the previous settlement
        log(DocExamples.CardPayments.settlement(), at(0, "Settlement opened"), at(3, "Merchant credited"))
    );
  }

  /// The examples of the state and entity relationship diagrams on the diagrams page, of the bounded context of the
  /// payment of the sequence diagram example: the main path of a payment, from its request to its authorisation, and
  /// the relations of the payments, merchants and settlements, without the inbox and outbox requests
  private static void diagramExamples() throws IOException {
    var name = "com.github.thxmasj.backend.diagram.";
    var cardPayments = DocExamples.CardPayments;
    System.out.println(new StateDiagramFormatter(cardPayments.payment())
        .only(PaymentState.ProcessingAuthentication, PaymentState.ProcessingAuthorisation, PaymentState.Authorised)
        .formatToImage(new File(directory, name + "state.Payment.svg")));
    System.out.println(new EntityRelationshipDiagramFormatter(name + "cardpayment", cardPayments.entityModels())
        .only(cardPayments.merchant(), cardPayments.payment(), cardPayments.settlement())
        .compact()
        .formatToImage(directory));
  }

  private static EntityModel model(List<EntityModel> models, String name) {
    return models.stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
  }

  private static void sequenceDiagram(String fileName, List<? extends EntityModel> models, EventLog log, EventLog... related)
      throws IOException {
    var formatter = new SequenceDiagramFormatter(log, List.of(related), models);
    System.out.println(formatter.formatToImage(new File(directory, fileName + ".svg")));
  }

  private static void cardPayments() throws IOException {
    var name = "com.github.thxmasj.backend.templates.cardpayment";
    var cardPayments = DummyTransitions.cardPayments();
    stateDiagram(name + ".Payment", cardPayments.payment());
    stateDiagram(name + ".Settlement", cardPayments.settlement());
    erDiagram(name, cardPayments.entityModels());
    stateDiagram(Ids);
  }

  private static void identityProvider() throws IOException {
    // The token lifetime is configuration, and doesn't change the model
    var tokenLifetime = Duration.ofMinutes(5);
    var name = "com.github.thxmasj.backend.identityprovider";
    stateDiagram(name + ".Client", Clients.Client);
    stateDiagram(name + ".ClientRegistration", Clients.ClientRegistration);
    stateDiagram(name + ".TokenRequest", Tokens.tokenRequest(tokenLifetime));
    stateDiagram(name + ".Authorization", Authorizations.Authorization);
    erDiagram(name, Inbox.inbox(tokenLifetime).entityModels());
  }

  private static void helloWorld() throws IOException {
    var name = "com.github.thxmasj.backend.helloworld";
    stateDiagram(name + ".Greeting", Greetings.Greeting);
    stateDiagram(name + ".GreetingRequest", Greetings.GreetingRequest);
    erDiagram(name, Greetings.Inbox.entityModels());
  }

  private static void backoffice() throws IOException {
    // The model doesn't depend on the configuration, which is only used when a request is handled
    var config = new Backoffice.Config(URI.create("https://backoffice.example.com"), URI.create("https://login.example.com"), "backoffice", null, "openid", "roles", Set.of("backoffice"), Duration.ofHours(8));
    var backoffice = new Backoffice("Example", config, null, "example", List.of());
    stateDiagram("com.github.thxmasj.backend.backoffice.BackofficeSession", backoffice.sessionModel());
  }

  private static void stateDiagram(EntityModel model) throws IOException {
    System.out.println(new StateDiagramFormatter(model).formatToImage(directory));
  }

  /// For the entity models of the apps, which are created with `EntityModel.of` and so would be named after the
  /// package of the core library
  private static void stateDiagram(String fileName, EntityModel model) throws IOException {
    System.out.println(new StateDiagramFormatter(model).formatToImage(new File(directory, fileName + ".svg")));
  }

  /// A diagram drawn by hand in Graphviz, from a resource next to this class
  private static void graphviz(String resource, String fileName) throws IOException {
    try (var source = Main.class.getResourceAsStream(resource)) {
      if (source == null)
        throw new IOException("No resource " + resource);
      var formatter = new GraphvizFormatter(new String(source.readAllBytes(), StandardCharsets.UTF_8));
      System.out.println(formatter.formatToImage(new File(directory, fileName + ".svg")));
    }
  }

  private static void erDiagram(String name, List<EntityModel> entityModels) throws IOException {
    System.out.println(new EntityRelationshipDiagramFormatter(name, entityModels).compact().formatToImage(directory));
  }

}
