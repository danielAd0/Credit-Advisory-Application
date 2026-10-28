
ALTER TABLE clients
    ADD COLUMN employer_id BIGINT REFERENCES employers (id);

CREATE INDEX ix_clients_employer ON clients (employer_id);

UPDATE clients c
SET employer_id = latest.employer_id
FROM (SELECT DISTINCT ON (t.client_id) t.client_id, t.employer_id
      FROM client_transactions t
      WHERE t.employer_id IS NOT NULL
      ORDER BY t.client_id, t.transaction_date DESC, t.id DESC) latest
WHERE latest.client_id = c.id
  AND c.employer_id IS NULL;


CREATE VIEW v_client_overview AS
SELECT c.id                              AS client_id,
       c.first_name,
       c.last_name,
       '*********' || c.id_number_last4  AS id_number_masked,
       e.name                            AS employer,
       con.consultant_code,
       con.full_name                     AS consultant_name,
       con.status                        AS consultant_status,
       c.email,
       c.phone,
       c.created_at
FROM clients c
         LEFT JOIN employers e ON e.id = c.employer_id
         LEFT JOIN consultants con ON con.id = c.current_consultant_id;
