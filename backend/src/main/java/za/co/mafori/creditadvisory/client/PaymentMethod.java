package za.co.mafori.creditadvisory.client;

/**
 * How a client pays when it is not a deduction from their salary. Stored in
 * clients.payment_method (V20); null means not recorded.
 *
 * <p>
 * The launch client list has "DEBIT ORDER ..." where the employer should be:
 * those clients get no employer and DEBIT_ORDER here. A new method is one more
 * value here and in the database check ck_clients_payment_method.
 */
public enum PaymentMethod {
    DEBIT_ORDER
}
