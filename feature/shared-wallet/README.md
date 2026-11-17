# Workspaces & shared wallets

Lets a user keep several **workspaces** (fully separate wallets, e.g. "Monthly budget"
and "Family vacation") and share any of them with **any number of people**, who all stay in sync.

## Workspaces

- `WorkspaceRegistry` (`shared/data/core`) keeps the list of workspaces and the active one.
- Each workspace has its own Room database file. The default workspace ("Personal") uses the
  legacy `ivywallet.db`, so existing users keep their data untouched.
- Switching workspace restarts the app, so every screen, repository and cache reads the new database.
- A new workspace starts with the settings (base currency) of the active one.

## Sharing

A shared workspace is backed by one JSON file (`*.ivyshared.json`) that every member reads and writes.
By default the file is picked with the Android file picker (Storage Access Framework), so it can live
in **Google Drive**, OneDrive, Dropbox, Nextcloud... and no API keys or OAuth client are needed:
the cloud app syncs the file between phones. To share with someone, share the Drive folder that
contains the file with them; they tap *Join a shared workspace* and pick the same file.

### Sync algorithm (`engine/`, pure Kotlin, unit tested)

Ivy's tables don't have `updatedAt` columns, so the engine tracks changes itself:

1. Snapshot the workspace DB and compare it with the state of the last sync. Rows that changed get a
   new timestamp; deleted rows become tombstones.
2. Merge with the records in the shared file: for each row the newest version wins
   (`updatedAt`, then `deviceId` as tie-breaker). This is a state-based last-writer-wins CRDT,
   so any number of members converge regardless of the order in which they sync.
3. Apply the winners to the local DB, save the merged state locally, and upload it.

Because each device keeps the full merged state locally, a lost upload (two members writing the file
at the same time) never loses or deletes data: it's re-uploaded on the next sync.

Synced: accounts, categories, transactions, budgets, planned payments, loans, loan records, tags.
Not synced on purpose: settings (base currency) and exchange rates.

Sync runs when the app starts, every 15 minutes in the background (WorkManager) and on demand.

### Extending (plugins)

The engine only talks to a `LedgerTransport` (read/write the file). Backends are contributed via a
Hilt `Set<LedgerTransportProvider>`; a plugin (e.g. a Google Drive API integration) can add its own
provider for its own location scheme without touching the engine.
