-- =====================================================================
-- V30: the header spellings of the real launch client list
-- (docs/api-client-list-import.md).
--
-- The real file's header row is:
--   FILNO, IDNO, SURNAME, NAME, C_EMPLOYER, WORK_TEL, HOME_TEL, CELL_TEL,
--   C_BRANCHNAME, FILEID
-- V29 already knows NAME, SURNAME, C_EMPLOYER, C_BRANCHNAME and FILEID. Without
-- IDNO the file was rejected outright ("No sheet has a header row with Name,
-- Surname and ID number"), because the ID number is required.
--
-- Stored as the parser normalises headers: upper-case, trimmed, single
-- spaces. Underscores are kept, as in the file.
-- =====================================================================

INSERT INTO client_list_header_aliases (alias, field)
VALUES ('IDNO', 'ID_NUMBER'),
       ('FILNO', 'FILE_NUMBER'),
       ('WORK_TEL', 'WORK_PHONE'),
       ('HOME_TEL', 'HOME_PHONE'),
       ('CELL_TEL', 'CELLPHONE');
