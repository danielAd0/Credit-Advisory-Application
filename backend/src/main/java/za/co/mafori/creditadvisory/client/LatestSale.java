package za.co.mafori.creditadvisory.client;

import java.time.LocalDate;

/**
 * The client's most recent sale, for the Clients page: its date, payment term
 * and structure (contract section 4). Payment term and structure belong to a
 * sale, not to the client, so a client with no sales yet has none.
 *
 * @param paymentTerm the payment term's name, or null if the sale has none
 * @param structure   the structure's name (e.g. Vital), or null if the sale has
 *                    none
 */
public record LatestSale(LocalDate date, String paymentTerm, String structure) {
}
