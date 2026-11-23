-- =====================================================================
-- V31: AMOUNT TO CLIENT counts toward Quotation and Banked (business
-- answer to Q2 in docs/weekly-register-import-design.md).
--
-- The standard rule is now: the product lines whose counts_toward_* flag
-- is set, plus the transaction's amount_to_client. It is used for typed-in
-- sales (no sheet value) and for the "by rule" figures the import compares
-- with the sheet. A row's own sheet value still wins when there is one (D3).
--
-- Same columns, names, types and order as V15, so the views built on this
-- one keep working.
-- =====================================================================
CREATE OR REPLACE VIEW v_transaction_totals AS
SELECT t.id                                                                   AS transaction_id,
       t.transaction_date,
       t.client_id,
       t.consultant_id,
       coalesce(t.quotation_amount,
                coalesce(sum(l.amount) FILTER (WHERE l.counts_toward_quotation), 0)
                    + coalesce(t.amount_to_client, 0))::NUMERIC(14, 2)          AS quotation_total,
       coalesce(t.banked_amount,
                coalesce(sum(l.amount) FILTER (WHERE l.counts_toward_banked), 0)
                    + coalesce(t.amount_to_client, 0))::NUMERIC(14, 2)          AS banked_total,
       t.reporting_week_id,
       t.day_of_week,
       (coalesce(sum(l.amount) FILTER (WHERE l.counts_toward_quotation), 0)
           + coalesce(t.amount_to_client, 0))::NUMERIC(14, 2)                   AS quotation_by_rule,
       (coalesce(sum(l.amount) FILTER (WHERE l.counts_toward_banked), 0)
           + coalesce(t.amount_to_client, 0))::NUMERIC(14, 2)                   AS banked_by_rule
FROM client_transactions t
         LEFT JOIN client_transaction_lines l ON l.transaction_id = t.id
WHERE t.superseded_by_import_job_id IS NULL
GROUP BY t.id;
