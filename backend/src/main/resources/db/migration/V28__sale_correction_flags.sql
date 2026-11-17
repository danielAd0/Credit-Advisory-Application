-- =====================================================================
-- V28: a manager flags a typed-in sale for correction (docs/api-sales.md).
--
-- Only the consultant who owns a sale may change it, so a manager who spots a
-- mistake leaves a note on the sale instead. The consultant sees it, fixes the
-- sale, and saving the change clears the note. A manager can also withdraw it.
--
-- One note per sale, kept on the sale itself: the audit log keeps the history
-- (SALE_FLAGGED, SALE_FLAG_WITHDRAWN, and SALE_EDITED with flagCleared).
-- Only typed-in sales can be flagged: an imported one is fixed in the
-- spreadsheet.
-- =====================================================================

ALTER TABLE client_transactions
    ADD COLUMN flag_note          VARCHAR(1000),
    ADD COLUMN flagged_by_user_id BIGINT REFERENCES users (id),
    ADD COLUMN flagged_at         TIMESTAMPTZ,
    -- All three together, or none.
    ADD CONSTRAINT ck_transactions_flag_complete
        CHECK ((flag_note IS NULL AND flagged_by_user_id IS NULL AND flagged_at IS NULL)
            OR (flag_note IS NOT NULL AND flagged_by_user_id IS NOT NULL AND flagged_at IS NOT NULL)),
    ADD CONSTRAINT ck_transactions_flag_not_blank
        CHECK (flag_note IS NULL OR btrim(flag_note) <> ''),
    ADD CONSTRAINT ck_transactions_flag_typed_in
        CHECK (flag_note IS NULL OR import_job_id IS NULL);

-- "Needs correction only" on the Sales history page.
CREATE INDEX ix_transactions_flagged
    ON client_transactions (transaction_date)
    WHERE flag_note IS NOT NULL;
