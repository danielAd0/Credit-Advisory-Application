-- =====================================================================
-- V15: recalculated weekly reports (design 7.3)
--
-- What the app shows. Calculated from the imported client rows, never
-- copied from the workbook's Report / Breakdown sheets (decision D1).
-- Every view ignores transactions replaced by a later import of the same
-- week (superseded_by_import_job_id IS NOT NULL).
--
-- Quotation / Banked per transaction: the row's own value from the sheet
-- when there is one (decision D3), otherwise calculated from the product
-- lines and their counts_toward_* flags (manually captured sales).
-- Target reached means quotation >= target (the workbook used >; Q5).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Replace the V6 views so they use the row values and skip superseded rows.
-- Column names and order are unchanged; new columns are added at the end.
-- ---------------------------------------------------------------------
DROP VIEW v_daily_sales;
DROP VIEW v_transaction_totals;

CREATE VIEW v_transaction_totals AS
SELECT t.id                                                                   AS transaction_id,
       t.transaction_date,
       t.client_id,
       t.consultant_id,
       coalesce(t.quotation_amount,
                sum(l.amount) FILTER (WHERE l.counts_toward_quotation), 0)::NUMERIC(14, 2) AS quotation_total,
       coalesce(t.banked_amount,
                sum(l.amount) FILTER (WHERE l.counts_toward_banked), 0)::NUMERIC(14, 2)    AS banked_total,
       t.reporting_week_id,
       t.day_of_week,
       -- What the standard product rule gives, to compare with the sheet's value (D3 / Q2).
       coalesce(sum(l.amount) FILTER (WHERE l.counts_toward_quotation), 0)::NUMERIC(14, 2) AS quotation_by_rule,
       coalesce(sum(l.amount) FILTER (WHERE l.counts_toward_banked), 0)::NUMERIC(14, 2)    AS banked_by_rule
FROM client_transactions t
         LEFT JOIN client_transaction_lines l ON l.transaction_id = t.id
WHERE t.superseded_by_import_job_id IS NULL
GROUP BY t.id;

CREATE VIEW v_daily_sales AS
SELECT tt.transaction_date,
       count(*)                                                       AS transaction_count,
       count(DISTINCT tt.client_id)                                   AS client_count,
       sum(tt.quotation_total)::NUMERIC(14, 2)                        AS quotation_total,
       sum(tt.banked_total)::NUMERIC(14, 2)                           AS banked_total,
       tgt.daily_quotation_target,
       sum(tt.quotation_total) >= tgt.daily_quotation_target          AS target_met
FROM v_transaction_totals tt
         LEFT JOIN LATERAL (
    SELECT s.daily_quotation_target
    FROM sales_targets s
    WHERE s.effective_from <= tt.transaction_date
    ORDER BY s.effective_from DESC
    LIMIT 1) tgt ON TRUE
GROUP BY tt.transaction_date, tgt.daily_quotation_target;

CREATE OR REPLACE VIEW v_consultant_product_daily AS
SELECT t.transaction_date,
       t.consultant_id,
       l.product_id,
       count(*)                      AS line_count,
       sum(l.amount)::NUMERIC(14, 2) AS total_amount
FROM client_transactions t
         JOIN client_transaction_lines l ON l.transaction_id = t.id
WHERE t.superseded_by_import_job_id IS NULL
GROUP BY t.transaction_date, t.consultant_id, l.product_id;

-- ---------------------------------------------------------------------
-- Weekly reports. One row per week and weekday, including days with no sales,
-- so the Monday-to-Sunday grid is always complete.
-- ---------------------------------------------------------------------

-- Banked & quotation report, daily targets, number of clients (requirements 3.1 - 3.3).
CREATE VIEW v_week_daily_summary AS
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
GROUP BY w.id, w.week_starting, d.day_of_week, tgt.daily_quotation_target;

-- Weekly totals and the weekly target.
CREATE VIEW v_week_totals AS
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
GROUP BY w.id, w.week_starting, tgt.weekly_quotation_target;

-- Product performance and the Breakdown page: every product, every day,
-- per consultant (consultant_id NULL = rows without a consultant, D2).
CREATE VIEW v_week_product_daily AS
SELECT t.reporting_week_id,
       t.day_of_week,
       t.consultant_id,
       l.product_id,
       count(*)                      AS line_count,
       sum(l.amount)::NUMERIC(14, 2) AS total_amount
FROM client_transactions t
         JOIN client_transaction_lines l ON l.transaction_id = t.id
WHERE t.reporting_week_id IS NOT NULL
  AND t.superseded_by_import_job_id IS NULL
GROUP BY t.reporting_week_id, t.day_of_week, t.consultant_id, l.product_id;

-- Consultant financial performance (Q1: both quotation and banked).
CREATE VIEW v_week_consultant_daily AS
SELECT tt.reporting_week_id,
       tt.day_of_week,
       tt.consultant_id,
       count(*)                                AS transaction_count,
       count(DISTINCT tt.client_id)            AS client_count,
       sum(tt.quotation_total)::NUMERIC(14, 2) AS quotation_total,
       sum(tt.banked_total)::NUMERIC(14, 2)    AS banked_total
FROM v_transaction_totals tt
WHERE tt.reporting_week_id IS NOT NULL
GROUP BY tt.reporting_week_id, tt.day_of_week, tt.consultant_id;

-- Budget by structure: the budget-list value names the structure; primary
-- and secondary loan amounts are added under the structure each one names.
CREATE VIEW v_week_budget_by_structure AS
SELECT b.reporting_week_id,
       b.day_of_week,
       b.budget_structure,
       count(*)                       AS entry_count,
       sum(b.loan_amount)::NUMERIC(14, 2) AS loan_amount_total
FROM (SELECT t.reporting_week_id, t.day_of_week,
             regexp_replace(btrim(t.primary_structure_budget_list), '\s+', ' ', 'g') AS budget_structure,
             coalesce(t.primary_structure_loan_amount, 0)                           AS loan_amount
      FROM client_transactions t
      WHERE t.reporting_week_id IS NOT NULL AND t.superseded_by_import_job_id IS NULL
        AND t.primary_structure_budget_list IS NOT NULL AND btrim(t.primary_structure_budget_list) <> ''
      UNION ALL
      SELECT t.reporting_week_id, t.day_of_week,
             regexp_replace(btrim(t.secondary_structure_budget_list), '\s+', ' ', 'g'),
             coalesce(t.secondary_structure_loan_amount, 0)
      FROM client_transactions t
      WHERE t.reporting_week_id IS NOT NULL AND t.superseded_by_import_job_id IS NULL
        AND t.secondary_structure_budget_list IS NOT NULL AND btrim(t.secondary_structure_budget_list) <> ''
     ) b
GROUP BY b.reporting_week_id, b.day_of_week, b.budget_structure;
