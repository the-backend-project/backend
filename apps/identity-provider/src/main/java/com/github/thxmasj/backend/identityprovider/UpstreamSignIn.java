package com.github.thxmasj.backend.identityprovider;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.Request;
import static com.github.thxmasj.backend.identityprovider.Authorizations.Authorization;
import static com.github.thxmasj.backend.identityprovider.Authorizations.CallbackReceived;
import static com.github.thxmasj.backend.identityprovider.Authorizations.SignInPassedOn;
import static com.github.thxmasj.backend.identityprovider.Authorizations.SignInUnanswerable;
import static com.github.thxmasj.backend.identityprovider.Authorizations.UpstreamCodeRedemptionRequested;
import static com.github.thxmasj.backend.identityprovider.Authorizations.UpstreamSignInFailed;
import static com.github.thxmasj.backend.identityprovider.Authorizations.UpstreamTokenReceived;
import static com.github.thxmasj.backend.identityprovider.Authorizations.UpstreamTokenRequestFailed;
import static com.github.thxmasj.backend.identityprovider.Authorizations.UpstreamUserSignedIn;
import static com.github.thxmasj.backend.identityprovider.Authorizations.UserAuthenticated;
import static com.github.thxmasj.backend.identityprovider.Authorizations.UserAuthenticationFailed;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.http.HttpClient;
import com.github.thxmasj.backend.http.HttpResponse;
import com.github.thxmasj.backend.http.Masking;
import com.github.thxmasj.backend.http.inbox.OpenIdConnectClient;
import com.github.thxmasj.backend.http.inbox.Secrets;
import com.github.thxmasj.backend.http.outbox.AtMostOnce;
import com.github.thxmasj.backend.http.outbox.AtMostOnceDispatcher;
import com.github.thxmasj.backend.http.outbox.DispatchContext;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest;
import com.github.thxmasj.backend.identityprovider.Authorizations.Callback;
import com.github.thxmasj.backend.identityprovider.Authorizations.Failure;
import com.github.thxmasj.backend.identityprovider.Authorizations.Outcome;
import com.github.thxmasj.backend.identityprovider.Authorizations.Redeeming;
import com.github.thxmasj.backend.identityprovider.Authorizations.SignIn;
import com.github.thxmasj.backend.identityprovider.Inbox.Reply;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// The sign-ins with the upstream provider at the callback, an entity model of their own, `Upstream sign-in`, with an
/// entity for each return of a user from the upstream provider.
///
/// A code can be redeemed only once, so it is redeemed by an at-most-once outbox request, which is sent once the
/// redemption is stored, and whose response the outbox tells the sign-in, in the session of the callback. A transition
/// calculated again, e.g. after a concurrent change, doesn't redeem the code again, as nothing is sent until the
/// redemption is stored. The code and the secret of the sign-in are masked in the event log, as are the token request
/// and its response, so the upstream provider's code and tokens never enter it. The ID token is verified against the
/// nonce of the secret, which is stored, as the secret isn't. Only the user signed in, or the failure, is passed on to
/// the authorization.
final class UpstreamSignIn {

  static final UUID Id = UUID.fromString("9f2d6b8e-3a1c-4e57-b9d4-6c0a2e8f1b73");

  enum States implements State {Redeeming, Redeemed}

  /// The code of the upstream provider and the secret its PKCE verifier is derived from, which the token request is
  /// created from, and which are not stored
  record Redemption(String code, String secret) {}

  private final OpenIdConnectClient upstream;
  private final AtMostOnceDispatcher<Redemption, String> tokenRequests;
  private final EntityModel model;

  /// @param upstream  the client of the upstream provider, which creates the token request and verifies the ID token,
  ///                  or null for the model only, whose transitions are not taken
  /// @param forwarder the HTTP client the token requests are sent with
  UpstreamSignIn(OpenIdConnectClient upstream, HttpClient forwarder) {
    this.upstream = upstream;
    this.model = EntityModel.of("Upstream sign-in", Id, this::transitions);
    AtMostOnce<String, Void> tokenRequest = HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.string())
        .responsePayloadType(DataType.string())
        .forwarder(forwarder)
        .inflightTimeout(Duration.ofSeconds(10))
        .processModel(model)
        .onPeerUnavailable(UpstreamTokenRequestFailed)
        .onMissingResponse(UpstreamTokenRequestFailed)
        .onInvalidResponseRejection(UpstreamTokenRequestFailed)
        .onInvalidResponseUnknown(UpstreamTokenRequestFailed)
        .contentParser(m -> Validated.valid(m.body() != null ? new String(m.body(), UTF_8) : ""))
        .onSuccess(UpstreamTokenReceived, HttpResponse::body)
        .onFailure(UpstreamTokenRequestFailed)
        .isAccepted(r -> r.statusCode() == 200)
        .isRejected(r -> r.statusCode() >= 400)
        .isRejectedByInvalidResponse(d -> d.t1().statusCode() >= 400)
        // The code and the PKCE verifier of the request, and the tokens of the response. The client secret of the
        // identity provider at the upstream provider is in the Authorization header, which is always masked.
        .masking(Masking.formFields("code", "code_verifier").andResponseBody())
        .build("UpstreamTokenRequest", UUID.fromString("5a9d3f7b-2e1c-4c86-b0f4-6e2a8d4c1f93"));
    this.tokenRequests = AtMostOnceDispatcher.reactive(tokenRequest, (_, r) -> upstream.tokenRequest(r.code(), r.secret()));
  }

  EntityModel model() {
    return model;
  }

  private Map<State, List<TransitionModel<?, ?>>> transitions() {
    return Map.of(
        Begin, List.of(
            onEvent(CallbackReceived).to(States.Redeeming)
                .assembleInput()
                .when(callback -> callback.upstreamError() != null || callback.upstreamCode() == null).then(
                    passOn(UpstreamSignInFailed, UserAuthenticationFailed, Outcome::failure),
                    callback -> new Outcome(callback.authorization(), null, upstreamFailure(callback.upstreamError()))
                )
                .when(callback -> !OpenIdConnectClient.isSecret(callback.secret())).then(
                    passOn(UpstreamSignInFailed, UserAuthenticationFailed, Outcome::failure),
                    callback -> new Outcome(callback.authorization(), null, new Failure("server_error", "The sign-in has expired, or was started in another browser"))
                )
                .otherwise(
                    onEvent(UpstreamCodeRedemptionRequested).to(States.Redeeming)
                        .assemble(c -> tuple(new Redemption(c.input().upstreamCode(), c.input().secret()), DispatchContext.of(c), c.input()))
                        .trigger(tokenRequests.requestDispatched()).withReactive(d -> tokenRequests.input(d.t2(), d.t1())).on(tokenRequests.outbox()).identifiedBy(newEntityId())
                        .outputWithMask(d -> d.t1().t3(), Redeeming::stored),
                    UpstreamSignIn::redeeming
                )
        ),
        States.Redeeming, List.of(
            onEvent(UpstreamTokenReceived).to(States.Redeemed)
                .assembleReactive(c -> outcome(c.input(), c.log().one(UpstreamCodeRedemptionRequested)))
                .when(o -> o.signIn() != null).then(passOn(UpstreamUserSignedIn, UserAuthenticated, Outcome::signIn), o -> o)
                .when(o -> o.failure() != null).then(passOn(UpstreamSignInFailed, UserAuthenticationFailed, Outcome::failure), o -> o)
                .otherwise(unanswerable(), _ -> null),
            onEvent(UpstreamTokenRequestFailed).to(States.Redeemed)
                .assemble(c -> new Outcome(
                    c.log().one(UpstreamCodeRedemptionRequested).authorization(),
                    null,
                    new Failure("server_error", "The sign-in with the identity provider failed")
                ))
                .otherwise(passOn(UpstreamSignInFailed, UserAuthenticationFailed, Outcome::failure), o -> o)
        ),
        States.Redeemed, List.of()
    );
  }

  private static Redeeming redeeming(Callback callback) {
    return new Redeeming(callback.authorization(), OpenIdConnectClient.nonce(callback.secret()), callback.upstreamCode(), callback.secret());
  }

  /// The failure of a user returning without a code. The error of the upstream provider is passed on only if it is one
  /// of the known ones, which are short.
  private static Failure upstreamFailure(String upstreamError) {
    String error = upstreamError != null && Authorizations.upstreamErrors.contains(upstreamError) ? upstreamError : "server_error";
    return new Failure(error, "The sign-in failed at the identity provider: " + error);
  }

  /// The user of the ID token of the response of the token request, verified against the nonce of the redemption, with
  /// the code generated for the client, or the failure if the ID token is invalid
  private Mono<Outcome> outcome(String tokenResponse, Redeeming redeeming) {
    UUID authorization = redeeming.authorization();
    return upstream.verifyWithNonce(tokenResponse, redeeming.nonce())
        .map(Authorizations::user)
        .map(user -> {
          if (user.subject() == null)
            return new Outcome(authorization, null, null);
          String code = Secrets.generate();
          return new Outcome(authorization, new SignIn(user, Secrets.hash(code), code), null);
        })
        .onErrorResume(e -> {
          System.err.println("Sign-in with the upstream provider failed for authorization " + authorization + ": " + e);
          return Mono.just(new Outcome(authorization, null, new Failure("server_error", "The sign-in with the identity provider failed")));
        });
  }

  /// Passes the outcome on to the authorization, which answers the user. One it can't be passed on to is answered with
  /// an error page.
  private static <T> TransitionModel<Outcome, Void> passOn(EventType<Outcome, Void> type, EventType<T, ?> event, Function<Outcome, T> data) {
    return onEvent(type).to(States.Redeemed)
        .assembleInput()
        .trigger(event).with(data).on(Authorization).identifiedBy(o -> entityId(o.authorization()))
        // isRejected() marks a rejected event as handled
        .when(d -> d.t2().isRejected() || !d.t2().isAccepted()).then(unanswerable(), _ -> null)
        .otherwise(onEvent(SignInPassedOn).to(States.Redeemed).output(), _ -> null);
  }

  private static TransitionModel<Void, Void> unanswerable() {
    return onEvent(SignInUnanswerable).to(States.Redeemed)
        .trigger(Inbox.ReplyCreated).with(_ -> new Reply(400, "{}")).on(Request).identifiedBy(entityIdFromSession())
        .output();
  }

}
