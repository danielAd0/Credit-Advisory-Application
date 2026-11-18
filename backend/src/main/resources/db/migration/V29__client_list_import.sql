-- =====================================================================
-- V29: the first load of the full client list (docs/api-client-list-import.md)
--
-- An admin uploads the launch client list once, before the first weekly
-- register import: preview, check, confirm, like the weekly register. The
-- import_jobs / import_rows / import_errors tables from V3 already know the
-- type 'CLIENT_LIST'; this adds what the launch list brings on top of V3.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Column headers the client list may use. A separate table from
-- import_header_aliases (V13): that one is keyed by the alias alone, and the
-- same header ("NAME", "EMAIL") can mean something else in the two files.
-- A new spelling in a future file is one row here, no code change.
-- ---------------------------------------------------------------------
CREATE TABLE client_list_header_aliases
(
    alias VARCHAR(100) PRIMARY KEY, -- upper-case, single-spaced, as the parser normalises headers
    field VARCHAR(32) NOT NULL,

    CONSTRAINT ck_client_list_alias_format CHECK (alias = upper(alias) AND alias = btrim(alias)
        AND alias !~ '\s{2,}'),
    CONSTRAINT ck_client_list_alias_field CHECK (field IN ('NAME', 'SURNAME', 'ID_NUMBER', 'CONSULTANT',
        'CONSULTANT_EMAIL', 'CLIENT_EMAIL', 'CELLPHONE', 'WORK_PHONE', 'HOME_PHONE', 'EMPLOYER',
        'PRODUCT_QUOTATION', 'FILE_NUMBER', 'FILE_ID'))
);

INSERT INTO client_list_header_aliases (alias, field)
VALUES ('NAME', 'NAME'),
       ('NAMES', 'NAME'),
       ('FIRST NAME', 'NAME'),
       ('FIRST NAMES', 'NAME'),
       ('FIRSTNAME', 'NAME'),
       ('C_NAME', 'NAME'),
       ('C_FIRSTNAME', 'NAME'),
       ('SURNAME', 'SURNAME'),
       ('LAST NAME', 'SURNAME'),
       ('LASTNAME', 'SURNAME'),
       ('C_SURNAME', 'SURNAME'),
       ('ID NUMBER', 'ID_NUMBER'),
       ('ID NO', 'ID_NUMBER'),
       ('ID NO.', 'ID_NUMBER'),
       ('IDNUMBER', 'ID_NUMBER'),
       ('ID_NUMBER', 'ID_NUMBER'),
       ('ID', 'ID_NUMBER'),
       ('IDENTITY NUMBER', 'ID_NUMBER'),
       ('C_IDNUMBER', 'ID_NUMBER'),
       ('C_IDNO', 'ID_NUMBER'),
       ('CONSULTANT', 'CONSULTANT'),
       ('CONSULTANT NAME', 'CONSULTANT'),
       ('C_CONSULTANT', 'CONSULTANT'),
       ('CONSULTANT EMAIL', 'CONSULTANT_EMAIL'),
       ('CONSULTANT E-MAIL', 'CONSULTANT_EMAIL'),
       ('CONSULTANT EMAIL ADDRESS', 'CONSULTANT_EMAIL'),
       ('CONSULTANTS EMAIL', 'CONSULTANT_EMAIL'),
       ('CONSULTANT''S EMAIL', 'CONSULTANT_EMAIL'),
       -- A plain "Email" is the client's own address (docs, section 2).
       ('EMAIL', 'CLIENT_EMAIL'),
       ('E-MAIL', 'CLIENT_EMAIL'),
       ('EMAIL ADDRESS', 'CLIENT_EMAIL'),
       ('CLIENT EMAIL', 'CLIENT_EMAIL'),
       ('C_EMAIL', 'CLIENT_EMAIL'),
       ('CELL', 'CELLPHONE'),
       ('CELLPHONE', 'CELLPHONE'),
       ('CELL PHONE', 'CELLPHONE'),
       ('CELL NUMBER', 'CELLPHONE'),
       ('CELL NO', 'CELLPHONE'),
       ('CELLPHONE NUMBER', 'CELLPHONE'),
       ('MOBILE', 'CELLPHONE'),
       ('PHONE', 'CELLPHONE'),
       ('C_CELL', 'CELLPHONE'),
       ('C_CELLNO', 'CELLPHONE'),
       ('WORK', 'WORK_PHONE'),
       ('WORK PHONE', 'WORK_PHONE'),
       ('WORK NUMBER', 'WORK_PHONE'),
       ('WORK TEL', 'WORK_PHONE'),
       ('TEL (W)', 'WORK_PHONE'),
       ('TEL W', 'WORK_PHONE'),
       ('C_WORKTEL', 'WORK_PHONE'),
       ('C_TELW', 'WORK_PHONE'),
       ('HOME', 'HOME_PHONE'),
       ('HOME PHONE', 'HOME_PHONE'),
       ('HOME NUMBER', 'HOME_PHONE'),
       ('HOME TEL', 'HOME_PHONE'),
       ('TEL (H)', 'HOME_PHONE'),
       ('TEL H', 'HOME_PHONE'),
       ('C_HOMETEL', 'HOME_PHONE'),
       ('C_TELH', 'HOME_PHONE'),
       ('EMPLOYER', 'EMPLOYER'),
       ('EMPLOYER NAME', 'EMPLOYER'),
       ('C_EMPLOYER', 'EMPLOYER'),
       ('C_BRANCHNAME', 'PRODUCT_QUOTATION'),
       ('BRANCH', 'PRODUCT_QUOTATION'),
       ('BRANCH NAME', 'PRODUCT_QUOTATION'),
       ('PRODUCT QUOTATION', 'PRODUCT_QUOTATION'),
       ('FILE NUMBER', 'FILE_NUMBER'),
       ('FILE NO', 'FILE_NUMBER'),
       ('FILE NO.', 'FILE_NUMBER'),
       ('FILENUMBER', 'FILE_NUMBER'),
       ('C_FILENO', 'FILE_NUMBER'),
       ('FILE ID', 'FILE_ID'),
       ('FILEID', 'FILE_ID'),
       ('C_FILEID', 'FILE_ID');

-- ---------------------------------------------------------------------
-- Staged rows: the launch list's extra client fields. Personal data, so
-- they are emptied with the names and ID numbers when the job finishes.
-- ---------------------------------------------------------------------
ALTER TABLE import_rows
    ADD COLUMN work_phone             VARCHAR(20),
    ADD COLUMN home_phone             VARCHAR(20),
    ADD COLUMN product_quotation_name VARCHAR(100),
    ADD COLUMN file_number            VARCHAR(20),
    ADD COLUMN file_id                VARCHAR(20),
    ADD COLUMN payment_method         VARCHAR(20),
    ADD CONSTRAINT ck_import_rows_payment_method CHECK (payment_method IS NULL OR payment_method IN ('DEBIT_ORDER'));

CREATE INDEX ix_import_rows_job_row ON import_rows (import_job_id, row_number);

-- Which header the preview read as which field, shown again with the summary.
-- Headers only (never cell values).
ALTER TABLE import_jobs
    ADD COLUMN column_mapping JSONB,
    ADD CONSTRAINT ck_import_jobs_column_mapping CHECK (column_mapping IS NULL OR jsonb_typeof(column_mapping) = 'array');
