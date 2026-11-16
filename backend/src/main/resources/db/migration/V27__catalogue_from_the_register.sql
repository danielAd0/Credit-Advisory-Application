-- =====================================================================
-- V27: a new install starts with no products and no employers.
--
-- The first weekly-register import fills both from the sheet: every column
-- between QUOTATION and AMOUNT TO CLIENT becomes a product (ProductDiscovery),
-- every EMPLOYER name a new employer at confirm. The admin adds the ones the
-- sheet does not have on the Products and Employers pages.
--
-- V6 (15 products) and V10 (63 employers) cannot be edited without breaking
-- Flyway's checksums on existing databases, so their rows are removed here,
-- and only on a database that has not been used yet: no clients, no sales and
-- no imports. A database already in use keeps its products and employers.
-- Only the seeded rows themselves are removed, so anything an admin added
-- by hand stays.
-- =====================================================================

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM clients)
        OR EXISTS (SELECT 1 FROM client_transactions)
        OR EXISTS (SELECT 1 FROM import_jobs) THEN
        RETURN;
    END IF;

    DELETE FROM products
    WHERE code IN ('COMBO', 'IVC', 'INSURANCE', 'MAFORI_CARD', 'MAFORI_VOUCHER', 'CREDIT_RESCUE',
                   'CREDIT_REPAIR', 'CREDIT_REPAIR_DEBIT_ORDER', 'SPEED_SERVICE', 'CASH_ADVANCE_LITE',
                   'GROCERY', 'HOUSING_ADMIN', 'MAFORI_WELLNESS', 'VOLUNTARY_DISTRIBUTION',
                   'POLICY_REPLACEMENT');

    CREATE TEMPORARY TABLE v27_seeded_employers (name VARCHAR(200)) ON COMMIT DROP;
    INSERT INTO v27_seeded_employers (name)
    VALUES
        ('AFRIT'),
        ('BA-PHALABORWA MUNICIPALITY'),
        ('BAVIAANS MUNICIPALITY'),
        ('BEAUFORT WEST MUNICIPALITY'),
        ('BELA BELA MUNICIPALITY'),
        ('BEMAX INTERNATIONAL CC'),
        ('BILL BUCHANAN'),
        ('BLOEM WATER (SOC) LTD'),
        ('BLOUBERG MUNICIPALITY'),
        ('BROWNS CASH & CARRY'),
        ('CAMDEBOO MUNICIPALITY'),
        ('CAPRICON MUNICIPALITY'),
        ('CENTRAL EDIBLE OIL (PTY) LTD'),
        ('CENTRAL KAROO DISTRICT MUNICIPALITY'),
        ('COPALCOR'),
        ('DC ELECTRICAL AFRICA'),
        ('DIHLABENG MUNICIPALITY ROSENDAL'),
        ('EPHRAIM MOGALE MUNICIPALITY'),
        ('GOLDEN ARROW BUS SERVICES'),
        ('GREATER GIYANI MUNICIPALITY'),
        ('GREATER LETABA MUNICIPALITY'),
        ('GREATER TUBATSE MUNICIPALITY'),
        ('GREATER TZANEEN MUNICIPALITY'),
        ('JOHN CRAIG'),
        ('KANNALAND MUNICIPALITY'),
        ('KLT AUTOMOTIVE & TUBULOR'),
        ('KOPANONG JAGGERSFONTEIN LOCAL MUNICIPALITY'),
        ('LEPELLENKUMPI MUNICIPALITY'),
        ('LEPHALALE MUNICIPALITY'),
        ('MAKHADO MUNICIPALITY'),
        ('MAKHUDUTHAMAGA MUNICIPALITY'),
        ('MALULEKE SUPERMARKET HOLDING'),
        ('MALUTI -A- PHOFUNG WATER'),
        ('MALUTI-A-PHOFUNG HARRISMITH'),
        ('MANGAUNG MUNICIPALITY'),
        ('MANTSOPA LOCAL MUNICIPALITY'),
        ('MARULENG MUNICIPALITY'),
        ('MASILONYANA MUNICIPALITY'),
        ('MATJHABENG MUNICIPALITY WELKOM'),
        ('MCCAIN FOODS SA'),
        ('MODIMOLLE MUNICIPALITY'),
        ('MOGALAKWENA MUNICIPALITY'),
        ('MOLEMOLE MUNICIPALITY'),
        ('MONTHLY SCAW'),
        ('MOOKGOPHONG MUNICIPALITY'),
        ('MOPANI MUNICIPALITY'),
        ('MOQHAKA KROONSTAD'),
        ('MUSINA MUNICIPALITY'),
        ('MUTALE MUNICIPALITY'),
        ('NALA MUNICIPALITY BOTHAVILLE'),
        ('NGWATHE EDENVILLE'),
        ('NKETOANA LINDLEY'),
        ('NOLA'),
        ('OUDTSHOORN MUNICIPALITY'),
        ('PHUMELELA LOCAL MUNICIPALITY VREDE'),
        ('POLOKWANE MUNICIPALITY'),
        ('SEKHUKHUNE DISTRICT MUNICIPALITY'),
        ('SETSOTO CLOCOLAN'),
        ('THABAZIMBI MUNICIPALITY'),
        ('TOKOLOGO LOCAL MUNICIPALITY DELSVILLE'),
        ('TONGAAT HULETT STARCH'),
        ('VHEMBE MUNICIPALITY'),
        ('WATERBERG MUNICIPALITY');

    DELETE FROM employer_aliases
    WHERE employer_id IN (SELECT e.id FROM employers e
                          JOIN v27_seeded_employers s ON lower(btrim(s.name)) = lower(btrim(e.name)));
    DELETE FROM employers e
    USING v27_seeded_employers s
    WHERE lower(btrim(s.name)) = lower(btrim(e.name));
END
$$;
