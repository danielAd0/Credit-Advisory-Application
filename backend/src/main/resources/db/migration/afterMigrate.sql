-- =====================================================================
-- After every Flyway migrate: the APPLICATION's database account gets the
-- rights it needs for data, and nothing more (docs/it-handover.md section 3).
--
-- Production uses two accounts:
--   ca_owner - owns the tables. Flyway uses it at start-up to migrate.
--   ca_app   - what the running application uses. It can read and write
--              rows, but it cannot create, change or drop a table, switch
--              off a trigger, or edit or delete the audit log.
-- So someone who breaks into the running application cannot wipe the
-- tables, rewrite history or switch off the append-only protections.
--
-- ${approle} is the application's account (DB_USERNAME in production). It
-- is empty on a developer laptop and in the tests, where one account does
-- everything: then this does nothing.
--
-- Runs after EVERY migrate (not once, like a V migration), so tables added
-- by later migrations get the same rights without anyone remembering.
-- =====================================================================
DO
$$
    DECLARE
        app TEXT := btrim('${approle}');
    BEGIN
        IF app = '' OR app = current_user THEN
            RETURN;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
            RAISE EXCEPTION 'The application database account "%" does not exist (DB_USERNAME). Create it first: docs/it-handover.md section 3.', app;
        END IF;

        -- Rows: read and write. Never the tables themselves.
        EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', app);
        EXECUTE format('REVOKE CREATE ON SCHEMA public FROM %I', app);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', app);
        EXECUTE format('REVOKE TRUNCATE, REFERENCES, TRIGGER ON ALL TABLES IN SCHEMA public FROM %I', app);
        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', app);
        EXECUTE format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO %I', app);

        -- Flyway's own record: the application never needs it.
        EXECUTE format('REVOKE ALL ON TABLE flyway_schema_history FROM %I', app);

        -- Append-only tables (their triggers refuse these too; this is the second lock).
        EXECUTE format('REVOKE UPDATE, DELETE ON TABLE audit_logs FROM %I', app);
        EXECUTE format('REVOKE UPDATE, DELETE ON TABLE client_assignment_history FROM %I', app);
        EXECUTE format('REVOKE UPDATE, DELETE ON TABLE case_notes FROM %I', app);
        EXECUTE format('REVOKE DELETE ON TABLE credit_cases FROM %I', app);
        EXECUTE format('REVOKE UPDATE ON TABLE weekly_report_figures FROM %I', app);
    END
$$;
