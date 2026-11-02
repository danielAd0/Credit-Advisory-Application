-- =====================================================================
-- V11: force users to choose their own password after an admin set it
--
-- TRUE after an admin creates the account or resets the password. While
-- TRUE the user may only change their password (or sign out). Set and
-- cleared by the application only - never by hand.
-- Existing users are not affected (FALSE).
-- =====================================================================

ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
