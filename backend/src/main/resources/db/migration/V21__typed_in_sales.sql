-- =====================================================================
-- V21: sales typed into the app (the Consultant actions page).
--
-- Consultants type a sale in on the day it happens, so managers see it
-- straight away. The same sale is also in that week's spreadsheet, and the
-- spreadsheet wins (business decision, 10 November): when the week's
-- register is imported, it replaces the typed-in sales of that week, the
-- same way a re-import replaces an earlier import. Replaced rows are kept,
-- marked with superseded_by_import_job_id, and every report view already
-- leaves them out (V15).
--
-- A typed-in sale has no import_job_id. Its week is found from its date.
-- =====================================================================

-- V13 only allowed imported rows to be replaced. Typed-in rows can be too
-- now, but never by the import they came from.
ALTER TABLE client_transactions
    DROP CONSTRAINT ck_transactions_superseded_imported,
    ADD CONSTRAINT ck_transactions_superseded_by_other_import
        CHECK (superseded_by_import_job_id IS NULL OR superseded_by_import_job_id IS DISTINCT FROM import_job_id),
    -- The Idempotency-Key the page sends with each sale: a double click or a
    -- retry after a lost answer saves the sale once.
    ADD COLUMN capture_key UUID,
    ADD CONSTRAINT ck_transactions_capture_key_typed_in
        CHECK (capture_key IS NULL OR import_job_id IS NULL);

-- One sale per key per user.
CREATE UNIQUE INDEX uq_transactions_capture_key
    ON client_transactions (created_by_user_id, capture_key)
    WHERE capture_key IS NOT NULL;

-- The import preview counts, and the confirm replaces, a week's live typed-in sales.
CREATE INDEX ix_transactions_typed_in_week
    ON client_transactions (reporting_week_id)
    WHERE import_job_id IS NULL AND superseded_by_import_job_id IS NULL;

-- V17 limited the weekly report views to imported weeks. A week that only has
-- typed-in sales so far is shown too, so managers see the week as it happens.
-- Same columns as V17; only the WHERE changes.
CREATE OR REPLACE VIEW v_week_daily_summary AS
SELECT w.id                                                         AS reporting_week_id,
       w.week_starting,
       d.day_of_week::SMALLINT                                      AS day_of_week,
       (w.week_starting + (d.day_of_week - 1))                      AS transaction_date,
       count(tt.transaction_id)                                     AS transaction_count,
       count(DISTINCT tt.client_id)                                 AS client_count,
       count(tt.transaction_id) FILTER (WHERE tt.consultant_id IS NULL) AS unattributed_count,
       coalesce(sum(tt.quotation_total), 0)::NUMERIC(14, 2)         AS quotation_total,
       coalesce(sum(tt.banked_total), 0)::NUMERIC(14, 2)            AS banked_total,
       tgt.daily_quotation_target,
       coalesce(sum(tt.quotation_total), 0) >= tgt.daily_quotation_target AS target_met
FROM reporting_weeks w
         CROSS JOIN generate_series(1, 7) AS d(day_of_week)
         LEFT JOIN v_transaction_totals tt
                   ON tt.reporting_week_id = w.id AND tt.day_of_week = d.day_of_week
         LEFT JOIN LATERAL (
    SELECT s.daily_quotation_target
    FROM sales_targets s
    WHERE s.effective_from <= w.week_starting + (d.day_of_week - 1)
    ORDER BY s.effective_from DESC
    LIMIT 1) tgt ON TRUE
WHERE (w.current_import_job_id IS NOT NULL
    OR EXISTS (SELECT 1
               FROM client_transactions x
               WHERE x.reporting_week_id = w.id
                 AND x.import_job_id IS NULL
                 AND x.superseded_by_import_job_id IS NULL))
GROUP BY w.id, w.week_starting, d.day_of_week, tgt.daily_quotation_target;

CREATE OR REPLACE VIEW v_week_totals AS
SELECT w.id                                                AS reporting_week_id,
       w.week_starting,
       count(tt.transaction_id)                            AS transaction_count,
       count(DISTINCT tt.client_id)                        AS client_count,
       count(DISTINCT tt.consultant_id)                    AS consultant_count,
       count(tt.transaction_id) FILTER (WHERE tt.consultant_id IS NULL) AS unattributed_count,
       coalesce(sum(tt.quotation_total), 0)::NUMERIC(14, 2) AS quotation_total,
       coalesce(sum(tt.banked_total), 0)::NUMERIC(14, 2)    AS banked_total,
       tgt.weekly_quotation_target,
       coalesce(sum(tt.quotation_total), 0) >= tgt.weekly_quotation_target AS target_met
FROM reporting_weeks w
         LEFT JOIN v_transaction_totals tt ON tt.reporting_week_id = w.id
         LEFT JOIN LATERAL (
    SELECT s.weekly_quotation_target
    FROM sales_targets s
    WHERE s.effective_from <= w.week_starting
    ORDER BY s.effective_from DESC
    LIMIT 1) tgt ON TRUE
WHERE (w.current_import_job_id IS NOT NULL
    OR EXISTS (SELECT 1
               FROM client_transactions x
               WHERE x.reporting_week_id = w.id
                 AND x.import_job_id IS NULL
                 AND x.superseded_by_import_job_id IS NULL))
GROUP BY w.id, w.week_starting, tgt.weekly_quotation_target;
