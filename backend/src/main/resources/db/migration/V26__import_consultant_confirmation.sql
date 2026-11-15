-- Consultant confirmation step of the weekly register import (docs/api-imports.md section 11).
--
-- Consultants are no longer matched by hand. The preview lists every consultant found in the
-- workbook (existing, new, or in conflict); the admin confirms that list, and only then can the
-- import be confirmed. Confirm registers the new consultants and links every client and sale to
-- them, in one transaction.
--
-- consultants_fingerprint is a SHA-256 of the list the admin confirmed. If the list changes after
-- that (someone adds a consultant, an alias is removed), the fingerprint no longer matches and the
-- admin must confirm the list again.

ALTER TABLE import_jobs
    ADD COLUMN consultants_confirmed_at         TIMESTAMPTZ,
    ADD COLUMN consultants_confirmed_by_user_id BIGINT REFERENCES users (id),
    ADD COLUMN consultants_fingerprint          CHAR(64),
    -- The import summary, kept so it can be shown again after confirm.
    ADD COLUMN consultants_identified           INT,
    ADD COLUMN consultants_registered           INT,
    ADD COLUMN consultants_matched              INT,
    ADD COLUMN clients_linked                   INT,
    ADD COLUMN transactions_linked              INT,
    ADD COLUMN transactions_unattributed        INT,
    ADD COLUMN rows_needing_attention           INT;

ALTER TABLE import_jobs
    ADD CONSTRAINT ck_import_jobs_consultants_confirmed
        CHECK ((consultants_confirmed_at IS NULL) = (consultants_fingerprint IS NULL)
            AND (consultants_confirmed_at IS NULL) = (consultants_confirmed_by_user_id IS NULL));

ALTER TABLE import_jobs
    ADD CONSTRAINT ck_import_jobs_consultants_fingerprint
        CHECK (consultants_fingerprint IS NULL OR consultants_fingerprint ~ '^[0-9a-f]{64}$');
