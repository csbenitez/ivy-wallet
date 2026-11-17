# Google Sheets sync

Two-way sync between Ivy Wallet and a tab of the user's **own** Google Sheet, for people who
already track their budget in Sheets and want to move to the mobile app.

## How it works

1. The user chooses their spreadsheet in the Google Picker, picks a tab and confirms a column mapping
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
| `auth/` | OAuth via Google Identity Services `AuthorizationClient`, scope `drive.file`. |
| `picker/` | Google Picker page (shown in a WebView) used to choose the spreadsheet. |
| `data/` | DataStore persistence of the link, mapping and sync ledger. |
| `GoogleSheetsSync` | Single entry point (`preview`, `link`, `sync`, `unlink`), easy to wrap as a sync plugin. |
| `ui/` | Settings → "Google Sheets sync" screen. |

## Permissions

The app requests only `https://www.googleapis.com/auth/drive.file`, a **non-sensitive** scope:
it can read and write just the spreadsheets the user chooses in the Google Picker (and files
the app creates), never the rest of their Drive. No Google app verification is required.
Because access is granted per file, the sheet must be chosen in the Picker; pasting a link
to a sheet the app was never given returns "not found".

## Google Cloud setup (required to run it)

All steps happen in **one** Google Cloud project, because a file picked in the Picker
is granted to that project's OAuth clients.

1. In [Google Cloud Console](https://console.cloud.google.com/) create (or pick) a project and note its
   **project number** (Dashboard → Project info; digits only, not the project id).
2. *APIs & Services → Library*: enable **Google Sheets API**, **Google Drive API** and **Google Picker API**.
3. *Google Auth Platform / OAuth consent screen*: user type **External**, app name, support email,
   and add the scope `https://www.googleapis.com/auth/drive.file`. Publishing the app
   ("In production") does not need verification for this scope; while in *Testing*, add the
   Google accounts that will use it as **Test users**.
4. *Credentials → Create credentials → OAuth client ID → Android*, once per build:
   - debug: package `com.ivy.wallet.debug`, SHA-1 of the repo's `debug.jks`:
     `96:27:30:45:9B:10:6B:8A:A8:FB:22:54:30:DE:B6:A9:7C:55:96:99`
   - release: package `com.ivy.wallet`, SHA-1 of the release keystore (or of the
     Play App Signing key if distributed through Google Play).
5. *Credentials → Create credentials → API key*. Edit it and under *API restrictions* allow only
   **Google Picker API** (leave *Application restrictions* as "None": the Picker runs in a WebView).
6. Give both values to the build, e.g. in `~/.gradle/gradle.properties` (never commit them):

   ```properties
   ivy.googlePickerApiKey=AIza...
   ivy.googleCloudProjectNumber=123456789012
   ```

   or as environment variables `GOOGLE_PICKER_API_KEY` and `GOOGLE_CLOUD_PROJECT_NUMBER` (e.g. CI secrets).
