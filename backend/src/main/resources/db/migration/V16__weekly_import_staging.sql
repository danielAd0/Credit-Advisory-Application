ALTER TABLE import_rows
    ADD COLUMN payment_term_name VARCHAR(100);

CREATE INDEX ix_import_rows_job_day ON import_rows (import_job_id, day_of_week, row_number);