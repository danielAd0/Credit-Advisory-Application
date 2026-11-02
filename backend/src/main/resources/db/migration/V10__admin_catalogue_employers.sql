-- =====================================================================
-- V10: Admin-managed catalogue (products and employers)
--
-- * Products already exist (V6). Nothing structural changes; admins now
--   maintain them through /api/products. Codes stay immutable because the
--   weekly-register import maps spreadsheet columns to product codes.
-- * Employers get the extra columns from the Mafori Employer Schedule
--   (Company, Employer Type), an active flag and optimistic locking, so an
--   admin can maintain them through /api/employers.
-- * Seeds the 63 distinct employers listed on the Mafori Employer Schedule
--   (October 2026). Names are trimmed and inner spaces collapsed; the four
--   "TONGAAT HULETT STARCH" rows are one employer. Re-running against data
--   that already has an employer is safe: the unique name index catches it
--   and only missing Company / Employer Type values are filled in.
-- =====================================================================

ALTER TABLE employers
    ADD COLUMN company       VARCHAR(100),
    ADD COLUMN employer_type VARCHAR(40),
    ADD COLUMN active        BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN version       BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD CONSTRAINT ck_employers_company_not_blank CHECK (company IS NULL OR btrim(company) <> ''),
    ADD CONSTRAINT ck_employers_type_not_blank CHECK (employer_type IS NULL OR btrim(employer_type) <> '');

CREATE INDEX ix_employers_active ON employers (active);

CREATE TRIGGER trg_employers_updated_at
    BEFORE UPDATE ON employers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO employers (name, company, employer_type)
VALUES
       ('AFRIT',                                      'Mafori', 'Payroll'),
       ('BA-PHALABORWA MUNICIPALITY',                 'Mafori', 'Payroll'),
       ('BAVIAANS MUNICIPALITY',                      'Mafori', 'Payroll'),
       ('BEAUFORT WEST MUNICIPALITY',                 'Mafori', 'Payroll'),
       ('BELA BELA MUNICIPALITY',                     'Mafori', 'Payroll'),
       ('BEMAX INTERNATIONAL CC',                     'Mafori', 'Payroll'),
       ('BILL BUCHANAN',                              'Mafori', 'Payroll'),
       ('BLOEM WATER (SOC) LTD',                      'Mafori', 'Payroll'),
       ('BLOUBERG MUNICIPALITY',                      'Mafori', 'Payroll'),
       ('BROWNS CASH & CARRY',                        'Mafori', 'Payroll'),
       ('CAMDEBOO MUNICIPALITY',                      'Mafori', 'Payroll'),
       ('CAPRICON MUNICIPALITY',                      'Mafori', 'Payroll'),
       ('CENTRAL EDIBLE OIL (PTY) LTD',               'Mafori', 'Payroll'),
       ('CENTRAL KAROO DISTRICT MUNICIPALITY',        'Mafori', 'Payroll'),
       ('COPALCOR',                                   'Mafori', 'Payroll'),
       ('DC ELECTRICAL AFRICA',                       'Mafori', 'Payroll'),
       ('DIHLABENG MUNICIPALITY ROSENDAL',            'Mafori', 'Payroll'),
       ('EPHRAIM MOGALE MUNICIPALITY',                'Mafori', 'Payroll'),
       ('GOLDEN ARROW BUS SERVICES',                  'Mafori', 'Payroll'),
       ('GREATER GIYANI MUNICIPALITY',                'Mafori', 'Payroll'),
       ('GREATER LETABA MUNICIPALITY',                'Mafori', 'Payroll'),
       ('GREATER TUBATSE MUNICIPALITY',               'Mafori', 'Payroll'),
       ('GREATER TZANEEN MUNICIPALITY',               'Mafori', 'Payroll'),
       ('JOHN CRAIG',                                 'Mafori', 'Payroll'),
       ('KANNALAND MUNICIPALITY',                     'Mafori', 'Payroll'),
       ('KLT AUTOMOTIVE & TUBULOR',                   'Mafori', 'Payroll'),
       ('KOPANONG JAGGERSFONTEIN LOCAL MUNICIPALITY', 'Mafori', 'Payroll'),
       ('LEPELLENKUMPI MUNICIPALITY',                 'Mafori', 'Payroll'),
       ('LEPHALALE MUNICIPALITY',                     'Mafori', 'Payroll'),
       ('MAKHADO MUNICIPALITY',                       'Mafori', 'Payroll'),
       ('MAKHUDUTHAMAGA MUNICIPALITY',                'Mafori', 'Payroll'),
       ('MALULEKE SUPERMARKET HOLDING',               'Mafori', 'Payroll'),
       ('MALUTI -A- PHOFUNG WATER',                   'Mafori', 'Payroll'),
       ('MALUTI-A-PHOFUNG HARRISMITH',                'Mafori', 'Payroll'),
       ('MANGAUNG MUNICIPALITY',                      'Mafori', 'Payroll'),
       ('MANTSOPA LOCAL MUNICIPALITY',                'Mafori', 'Payroll'),
       ('MARULENG MUNICIPALITY',                      'Mafori', 'Payroll'),
       ('MASILONYANA MUNICIPALITY',                   'Mafori', 'Payroll'),
       ('MATJHABENG MUNICIPALITY WELKOM',             'Mafori', 'Payroll'),
       ('MCCAIN FOODS SA',                            'Mafori', 'Payroll'),
       ('MODIMOLLE MUNICIPALITY',                     'Mafori', 'Payroll'),
       ('MOGALAKWENA MUNICIPALITY',                   'Mafori', 'Payroll'),
       ('MOLEMOLE MUNICIPALITY',                      'Mafori', 'Payroll'),
       ('MONTHLY SCAW',                               'Mafori', 'Payroll'),
       ('MOOKGOPHONG MUNICIPALITY',                   'Mafori', 'Payroll'),
       ('MOPANI MUNICIPALITY',                        'Mafori', 'Payroll'),
       ('MOQHAKA KROONSTAD',                          'Mafori', 'Payroll'),
       ('MUSINA MUNICIPALITY',                        'Mafori', 'Payroll'),
       ('MUTALE MUNICIPALITY',                        'Mafori', 'Payroll'),
       ('NALA MUNICIPALITY BOTHAVILLE',               'Mafori', 'Payroll'),
       ('NGWATHE EDENVILLE',                          'Mafori', 'Payroll'),
       ('NKETOANA LINDLEY',                           'Mafori', 'Payroll'),
       ('NOLA',                                       'Mafori', 'Payroll'),
       ('OUDTSHOORN MUNICIPALITY',                    'Mafori', 'Payroll'),
       ('PHUMELELA LOCAL MUNICIPALITY VREDE',         'Mafori', 'Payroll'),
       ('POLOKWANE MUNICIPALITY',                     'Mafori', 'Payroll'),
       ('SEKHUKHUNE DISTRICT MUNICIPALITY',           'Mafori', 'Payroll'),
       ('SETSOTO CLOCOLAN',                           'Mafori', 'Payroll'),
       ('THABAZIMBI MUNICIPALITY',                    'Mafori', 'Payroll'),
       ('TOKOLOGO LOCAL MUNICIPALITY DELSVILLE',      'Mafori', 'Payroll'),
       ('TONGAAT HULETT STARCH',                      'Mafori', 'Payroll'),
       ('VHEMBE MUNICIPALITY',                        'Mafori', 'Payroll'),
       ('WATERBERG MUNICIPALITY',                     'Mafori', 'Payroll')

ON CONFLICT ((lower(btrim(name)))) DO UPDATE
    SET company       = coalesce(employers.company, EXCLUDED.company),
        employer_type = coalesce(employers.employer_type, EXCLUDED.employer_type);
