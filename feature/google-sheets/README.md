# Google Sheets sync

Two-way sync between Ivy Wallet and a tab of the user's **own** Google Sheet, for people who
already track their budget in Sheets and want to move to the mobile app.

## How it works

1. The user pastes the sheet link, picks a tab and confirms a column mapping
   (auto-guessed from Spanish/English headers: date, amount, type, account, category, ...).
2. **Sheet → Ivy**: rows not synced before are imported through the existing CSV importer
   (`CSVImporterV2`), so accounts/categories are matched or created the same way as a CSV import.
   Values are normalized first (`SheetValueNormalizer`): `1.234,50`, `(12.00)`, `5/3/26`, serial dates...
3. **Ivy → Sheet**: settled transactions created in Ivy after the sheet was linked are appended
   to the same tab, written in the sheet's format (`SheetRowWriter`): same date order,
   signed amounts or a type column with the user's own words.
4. A ledger (`GoogleSheetsSyncStore`) keeps row fingerprints and transaction ids, so every sync
   only moves new data and rows written by Ivy are never imported back.

Not synced yet: edits and deletions (on either side) and automatic background sync.

## Code layout

| Package | What |
|---|---|
| `sync/` | Pure Kotlin, no Android: mapping, normalization, fingerprints, row writer. Unit tested. |
| `remote/` | `SheetsApi` + REST implementation (Sheets API v4 via Ktor). |
| `auth/` | OAuth via Google Identity Services `AuthorizationClient`. |
| `data/` | DataStore persistence of the link, mapping and sync ledger. |
| `GoogleSheetsSync` | Single entry point (`preview`, `link`, `sync`, `unlink`), easy to wrap as a sync plugin. |
| `ui/` | Settings → "Google Sheets sync" screen. |

## Google Cloud setup (required to run it)

The app ships no client id or secret. Google matches the app by package name + signing SHA-1.

1. In [Google Cloud Console](https://console.cloud.google.com/) create (or pick) a project.
2. *APIs & Services → Library*: enable **Google Sheets API**.
3. *APIs & Services → OAuth consent screen*: user type **External**, add the scope
   `https://www.googleapis.com/auth/spreadsheets`, and while the app is in *Testing*
   add every Google account that will use it under **Test users**.
4. *APIs & Services → Credentials → Create credentials → OAuth client ID → Android*, once per build:
   - debug: package `com.ivy.wallet.debug`, SHA-1 of the repo's `debug.jks`:
     `96:27:30:45:9B:10:6B:8A:A8:FB:22:54:30:DE:B6:A9:7C:55:96:99`
   - release: package `com.ivy.wallet`, SHA-1 of the release keystore (or of the
     Play App Signing key if distributed through Google Play).

Publishing to everyone requires Google's verification of the (sensitive) `spreadsheets` scope.
