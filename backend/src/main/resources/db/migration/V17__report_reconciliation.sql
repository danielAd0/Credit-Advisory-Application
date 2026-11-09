
INSERT INTO import_header_aliases (alias, canonical, kind)
VALUES ('COMB0', 'COMBO', 'PRODUCT')
ON CONFLICT (alias) DO NOTHING;

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
WHERE w.current_import_job_id IS NOT NULL
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
WHERE w.current_import_job_id IS NOT NULL
GROUP BY w.id, w.week_starting, tgt.weekly_quotation_target;
