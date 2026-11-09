CREATE SEQUENCE consultant_code_seq START WITH 1 MINVALUE 1 NO CYCLE;

SELECT setval('consultant_code_seq',
              COALESCE((SELECT max(substring(consultant_code FROM '^C([0-9]{1,9})$')::bigint) FROM consultants), 0) + 1,
              false);
