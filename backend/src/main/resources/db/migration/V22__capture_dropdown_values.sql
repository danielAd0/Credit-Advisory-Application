-- =====================================================================
-- V22: the values of the Consultant actions page dropdowns.
--
-- Payment terms: Once off payment (V6) and Within 12 months.
-- Structures:    VITAL CREDIT and MAFORI CREDIT, spelled as in the weekly
--                register (docs/weekly-register-import-design.md), so a
--                typed-in sale and an imported one share the same row.
--
-- V20 seeded the structure as 'Vital', which the register never uses: the
-- first import would have created 'VITAL CREDIT' beside it. It is renamed,
-- keeping its id, so sales already pointing at it stay correct.
--
-- Structures get an active flag like payment terms, so one can be taken off
-- the dropdown without touching the sales that use it.
-- =====================================================================

ALTER TABLE structures
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

-- Rename 'Vital', unless an import already created 'VITAL CREDIT'.
UPDATE structures
SET name = 'VITAL CREDIT'
WHERE lower(btrim(name)) = 'vital'
  AND NOT EXISTS (SELECT 1 FROM structures WHERE lower(btrim(name)) = 'vital credit');

-- If both existed, the old seed only leaves the dropdown; its sales keep it.
UPDATE structures
SET active = FALSE
WHERE lower(btrim(name)) = 'vital';

INSERT INTO structures (name)
VALUES ('VITAL CREDIT'),
       ('MAFORI CREDIT')
ON CONFLICT ((lower(btrim(name)))) DO NOTHING;

-- The code is the one the import makes from this label
-- (WeeklyImportStore.paymentTermCode), so an import finds this row.
INSERT INTO payment_terms (code, name)
VALUES ('WITHIN_12_MONTHS', 'Within 12 months')
ON CONFLICT (code) DO NOTHING;
