# Plugins

Plugins are optional features that users can turn on and off from
**Settings → Plugins**. Each plugin is a Gradle module compiled into the app
(no code is downloaded at runtime, which Google Play doesn't allow).

## Modules

| Module | What it contains |
|---|---|
| `:feature:plugins:api` | The contracts plugins implement: `IvyPlugin`, `SyncPlugin`, `PluginPreferences` and the reusable `SnapshotSyncEngine`. |
| `:feature:plugins:impl` | The plugin registry (`PluginManager`), the Plugins screen and the background sync worker. |
| `:feature:plugins:google-drive` | The first plugin: back up and sync the wallet with Google Drive. |

## Writing a plugin

1. Create a module under `feature/plugins/` that depends on `:feature:plugins:api`.
2. Implement `IvyPlugin`. For a plugin that syncs data with an external
   service, implement `SyncPlugin` instead, so it gets run in the background.
3. Register it in the plugin registry:
   ```kotlin
   @Module
   @InstallIn(SingletonComponent::class)
   interface MyPluginModule {
       @Binds
       @IntoSet
       fun bindPlugin(plugin: MyPlugin): IvyPlugin
   }
   ```
4. Add the module to `settings.gradle.kts` and as a dependency of `:app`.

`PluginMetadata.id` is used as a storage key: never change it after release.
Plugins are disabled by default; `PluginManager` stores the enabled state.
`IvyPlugin.SettingsContent()` is shown under the plugin on the Plugins screen
while it's enabled, so put account linking and manual actions there.

### Sync plugins

`PluginManager` schedules `PluginSyncWorker` every 6 hours (with network)
while at least one sync plugin is enabled, and runs every enabled and
connected `SyncPlugin`.

Plugins that store the whole wallet as a single file can reuse
`SnapshotSyncEngine` by implementing `RemoteSnapshotStore`. Two-way sync
merges the remote copy into the device when another device changed it, then
uploads the result. Merging is an upsert by id (the same as restoring a
backup), so deletions are not propagated yet: something deleted on one device
can come back from another device. Plugins that need row-level sync (e.g.
a shared wallet or a spreadsheet) should implement `SyncPlugin.sync()` directly.

## Google Drive plugin setup

The plugin uses Google Identity Services (`AuthorizationClient`) with the
`drive.appdata` scope, so Ivy Wallet can only see its own hidden folder in the
user's Drive. The wallet is stored there as `ivy-wallet-sync.json`.

Google only issues tokens to apps registered in a Google Cloud project:

1. In the [Google Cloud console](https://console.cloud.google.com/), create a
   project (or use an existing one) and enable the **Google Drive API**.
2. Configure the **OAuth consent screen** (Google Auth Platform → Branding /
   Audience). While the app is in *Testing*, add the Google accounts that will
   use it as test users. `drive.appdata` is a non-sensitive scope, so publishing
   the app doesn't require Google's verification.
3. Add the scope `https://www.googleapis.com/auth/drive.appdata` under
   **Data access**.
4. Create an **OAuth client ID** of type **Android** for each package name and
   signing key that will run the app, using the key's SHA-1
   (`./gradlew signingReport` or `keytool -list -v -keystore <keystore>`):
   - `com.ivy.wallet.debug` with the debug key (`debug.jks` in this repo).
   - `com.ivy.wallet` with the release / Play App Signing key.

No client ID or secret goes into the code: Google matches the app by package
name and certificate. If the client is missing, connecting fails with a
`DEVELOPER_ERROR` (code 10).
