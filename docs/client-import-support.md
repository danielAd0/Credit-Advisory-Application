# ClientImportSupport - the client half of every import

Owner: Ntiyiso. Used by: the import code (Sifiso). Package: `za.co.mafori.creditadvisory.client.importing`.

The import reads the file and stores rows. **`ClientImportSupport` is the only code an import uses to
create or change clients**, so the ID-number rules (validation, keyed hash, encryption), the assignment
history and the audit log stay in one place. The import never calls `IdNumberProtector`,
`ClientRepository` or `ClientAssignmentHistory` itself.

## 1. Preview: `classify(ImportedClient)`

Call it once per parsed row. It **writes nothing and audits nothing**.

```java
ClientImportPreview p = clientImport.classify(
        ImportedClient.fromRegister(row.idNumber(), row.firstName(), row.lastName()));
// client lists with email / phone columns:
// new ImportedClient(idNumber, firstName, lastName, email, phone)
```

| `p.action()` | Meaning                                                                                                                                        | Store in `import_rows.action`       |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| `NEW`        | no client has this ID number: confirm creates one                                                                                              | `NEW` (and `creates_client = true`) |
| `UPDATE`     | same person, same names; the file adds or changes email / phone                                                                                | `UPDATE`                            |
| `UNCHANGED`  | same person, nothing to change                                                                                                                 | `UNCHANGED`                         |
| `CONFLICT`   | a client has this ID number but **different names** (a typo in the ID could point at someone else). Never applied without a manager's decision | `CONFLICT`                          |
| `INVALID`    | cannot be imported; `p.invalidReason()` is safe to show (no ID number)                                                                         | `INVALID`                           |

Names are compared ignoring case and extra spaces: `"THANDI "` and `"Thandi"` are the same.
`p.differingFields()` lists the field **names** that differ (`firstName`, `lastName`, `email`, `phone`) for the preview screen.

Store these in `import_rows` (all already cleaned / normalised):

| From the preview                                          | Column                                          |
| --------------------------------------------------------- | ----------------------------------------------- |
| `p.idNumber().lookupHash()`                               | `id_number_hash`                                |
| `p.idNumber().encrypted()`                                | `id_number_encrypted`                           |
| `p.idNumber().last4()`                                    | `id_number_last4`                               |
| `p.firstName()`, `p.lastName()`, `p.email()`, `p.phone()` | `first_name`, `last_name`, `email`, `phone`     |
| `p.existingClientId()`, `p.existingClientVersion()`       | `existing_client_id`, `existing_client_version` |

Never store or log the plain ID number. `toString()` of every type here prints the masked number only.

## 2. Confirm: `apply(StagedClient, consultantId, decision, importJobId, actingUserId)`

Read each stored row back and call `apply` **inside the confirm transaction**:

```java
StagedClient staged = new StagedClient(action,
        new ProtectedIdNumber(idNumberHash, idNumberEncrypted, idNumberLast4),
        firstName, lastName, email, phone, existingClientId, existingClientVersion);

ClientImportResult r = clientImport.apply(staged, consultantIdOrNull, managerDecisionOrNull, job.getId(), caller.userId());
if (r.outcome().hasClient()) {
    // attach the transaction to r.clientId()
}
```

| `r.outcome()` | Meaning                                                                        | `import_rows.outcome` |
| ------------- | ------------------------------------------------------------------------------ | --------------------- |
| `CREATED`     | new client created; `r.assigned()` says whether the consultant was assigned    | `APPLIED`             |
| `UPDATED`     | names or contact details changed                                               | `APPLIED`             |
| `LINKED`      | existing client, nothing needed changing                                       | `APPLIED`             |
| `SKIPPED`     | a `CONFLICT` the manager chose to skip                                         | `SKIPPED`             |
| `STALE`       | the client changed after the preview; nothing was changed, `r.note()` explains | `STALE`               |

Rules:

- **Consultants:** `consultantId` is only used when the row **creates** the client. An import never moves an
  existing client (that is a transfer). If the consultant does not exist or is not ACTIVE, the client is
  created **unassigned** and `r.note()` says so.
- **A client who appears twice in one file:** both rows are `NEW` at preview. The first creates the client;
  the second finds it and returns `LINKED` with the same `clientId` (or `STALE` if the names differ).
- **`CONFLICT` rows:** pass the manager's `ConflictDecision`. `null` or `SKIP` changes nothing;
  `KEEP_EXISTING_NAMES` links the row to the client as it is; `USE_IMPORTED_NAMES` takes the file's names.
- **Empty email / phone** in the file never clear a stored value.
- `INVALID` rows cannot be turned into a `StagedClient` (it throws).

What it records (`importJobId` must be an existing `import_jobs.id`: the columns below are foreign keys):

- every client it creates gets `clients.created_by_import_job_id = importJobId` (V3)

| Event                            | Assignment history                                                            | Audit (metadata never has names or ID numbers)                        |
| -------------------------------- | ----------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| client created with a consultant | `IMPORT`, reason `Imported from workbook (import job N)`, `import_job_id = N` | `CLIENT_CREATED` with `consultantId`, `source: IMPORT`, `importJobId` |
| client created unassigned        | none                                                                          | `CLIENT_CREATED` with `consultantId: null`, `source`, `importJobId`   |
| client updated                   | none                                                                          | `CLIENT_UPDATED` with `fields`, `source`, `importJobId`               |

## 3. Who may call it

`ClientImportSupport` does not check roles. The import endpoints that call it must be limited to
MANAGER and ADMIN (`@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")`).
