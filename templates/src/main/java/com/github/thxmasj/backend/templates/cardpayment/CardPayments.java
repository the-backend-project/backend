package com.github.thxmasj.backend.templates.cardpayment;

import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Tuples.Tuple3;
import com.github.thxmasj.backend.core.Tuples.Tuple4;
import com.github.thxmasj.backend.core.Tuples.Tuple5;
import com.github.thxmasj.backend.core.Tuples.Tuple6;
import com.github.thxmasj.backend.core.Tuples.Tuple7;
import com.github.thxmasj.backend.core.Tuples.Tuple8;
import com.github.thxmasj.backend.http.outbox.Dispatcher;
import com.github.thxmasj.backend.http.outbox.DispatcherWithRollback;
import com.github.thxmasj.backend.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import com.github.thxmasj.backend.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Capture;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.FailedAuthenticationResult;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Merchant;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.PaymentToken;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Refund;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.ReversalData;
import com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.CutOff;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/// The card payment template: the entity types of the merchants, of the payments made to them and of their
/// settlements, with the outbox requests to the acquirer and the merchants. The models are created together, as they
/// trigger events on each other.
///
/// The outbox requests of a payment or a settlement are sent back their outcomes on the payment or the settlement, so
/// each dispatcher is given as a factory of the model it is dispatched from, its process model, which the factory
/// builds the outbox request with (see `processModel` of the outbox request builders).
public final class CardPayments {

  private final EntityType merchant;
  private final EntityType payment;
  private final EntityType settlement;
  private final PaymentTransitions paymentTransitions;
  private final SettlementTransitions settlementTransitions;

  public CardPayments(
      Function<String, PaymentToken> tokenDecrypter,
      Function<EntityType, ? extends Dispatcher<AuthenticationData, ?>> authentication,
      // To acquirer
      Function<EntityType, ? extends DispatcherWithRollback<Tuple4<Authorisation, Merchant, AuthenticationResult, PaymentToken>, ?, Tuple2<ReversalData, Merchant>>> preauthorisation,
      Function<EntityType, ? extends DispatcherWithRollback<Tuple6<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, UUID>, ?, Tuple4<ReversalData, Merchant, AcquirerBatchNumber, UUID>>> authorisation,
      Function<EntityType, ? extends Dispatcher<Tuple8<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, AcquirerResponse, Capture, UUID>, ?>> capture,
      Function<EntityType, ? extends DispatcherWithRollback<Tuple7<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, Refund, UUID>, ?, Tuple4<ReversalData, Merchant, AcquirerBatchNumber, UUID>>> refund,
      Function<EntityType, ? extends Dispatcher<Tuple5<Authorisation, Merchant, AuthenticationResult, Capture, PaymentToken>, ?>> captureRequestedTooLate,
      Function<EntityType, ? extends Dispatcher<Tuple4<Authorisation, Merchant, FailedAuthenticationResult, PaymentToken>, ?>> failedAuthentication,
      Function<EntityType, ? extends Dispatcher<Tuple3<BatchNumber, AcquirerBatchNumber, Merchant>, ?>> reconciliation,
      // To merchant
      Function<EntityType, ? extends Dispatcher<Tuple3<ReversalData, Merchant, BatchNumber>, ?>> rolledBackAuthorisation,
      Function<EntityType, ? extends Dispatcher<Tuple2<ReversalData, Merchant>, ?>> rolledBackPreauthorisation,
      Function<EntityType, ? extends Dispatcher<Tuple3<Authorisation, Merchant, AcquirerResponse>, ?>> approvedPreauthorisation,
      Function<EntityType, ? extends Dispatcher<Tuple2<Authorisation, Merchant>, ?>> failedAuthorisation,
      Function<EntityType, ? extends Dispatcher<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>, ?>> approvedAuthorisation,
      Function<EntityType, ? extends Dispatcher<Tuple3<Authorisation, Merchant, AcquirerResponse>, ?>> declinedAuthorisation,
      Function<EntityType, ? extends Dispatcher<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>, ?>> approvedCapture,
      Function<EntityType, ? extends Dispatcher<Tuple2<Authorisation, Merchant>, ?>> failedRefund,
      Function<EntityType, ? extends Dispatcher<Tuple5<Authorisation, Merchant, AcquirerResponse, BatchNumber, Refund>, ?>> approvedRefund,
      Function<EntityType, ? extends Dispatcher<Tuple3<Authorisation, Merchant, AcquirerResponse>, ?>> declinedRefund,
      Function<EntityType, ? extends Dispatcher<Tuple2<CutOff, ReconciliationValues>, ?>> approvedCutOff
  ) {
    // The transitions are created when the state machine first asks for them, after the models are all created
    this.merchant = EntityType.of("Merchant", UUID.fromString("3ece621b-2c6a-4e5a-895e-6f7b566ec26f"), this::merchantTransitions);
    this.payment = EntityType.of("Payment", UUID.fromString("c56dc62e-24e8-43a7-868f-b87720327ff5"), this::paymentTransitionsOfModel);
    this.settlement = EntityType.of("Settlement", UUID.fromString("fc1d7b4c-92c7-40b3-9f7e-68a84d8d56f1"), this::settlementTransitionsOfModel);
    this.paymentTransitions = new PaymentTransitions(
        tokenDecrypter,
        merchant,
        settlement,
        authentication.apply(payment),
        preauthorisation.apply(payment),
        authorisation.apply(payment),
        capture.apply(payment),
        refund.apply(payment),
        captureRequestedTooLate.apply(payment),
        failedAuthentication.apply(payment),
        rolledBackAuthorisation.apply(payment),
        rolledBackPreauthorisation.apply(payment),
        approvedPreauthorisation.apply(payment),
        failedAuthorisation.apply(payment),
        approvedAuthorisation.apply(payment),
        declinedAuthorisation.apply(payment),
        approvedCapture.apply(payment),
        failedRefund.apply(payment),
        approvedRefund.apply(payment),
        declinedRefund.apply(payment)
    );
    this.settlementTransitions = new SettlementTransitions(
        merchant,
        settlement,
        reconciliation.apply(settlement),
        approvedCutOff.apply(settlement)
    );
  }

  private Map<State, List<TransitionModel<?, ?>>> merchantTransitions() {
    return new MerchantTransitions(settlement).transitions();
  }

  private Map<State, List<TransitionModel<?, ?>>> paymentTransitionsOfModel() {
    return paymentTransitions.transitions();
  }

  private Map<State, List<TransitionModel<?, ?>>> settlementTransitionsOfModel() {
    return settlementTransitions.transitions();
  }

  /// The merchants the payments are made to
  public EntityType merchant() {
    return merchant;
  }

  /// The payments
  public EntityType payment() {
    return payment;
  }

  /// The settlements of the merchants, of the batches of their payments
  public EntityType settlement() {
    return settlement;
  }

  /// The transitions of the payments, with their dispatchers and outbox requests
  public PaymentTransitions paymentTransitions() {
    return paymentTransitions;
  }

  /// The transitions of the settlements, with their dispatchers and outbox requests
  public SettlementTransitions settlementTransitions() {
    return settlementTransitions;
  }

  /// The merchants, payments and settlements, to give the state machine if no inbox triggers events on them. The state
  /// machine also runs the models they trigger events on, e.g. the outbox requests and the ids.
  public List<EntityType> entityTypes() {
    return List.of(merchant, payment, settlement);
  }

}
