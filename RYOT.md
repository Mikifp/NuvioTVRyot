# Ryot integration for NuvioTV

This fork adds [Ryot](https://github.com/IgnisDa/ryot) as a fourth tracking provider next to
Trakt, Simkl and MDBList. It talks to your own Ryot server over its GraphQL API.

## What it does

| In Nuvio | In Ryot |
|---|---|
| Playing a movie or episode | An in-progress entry, updated on pause/stop; marked completed at 90%+ |
| Marking something watched / unwatched | Completed history entry added / removed |
| Continue Watching and Next Up | Built from your Ryot in-progress entries and watched episodes |
| Watched badges on posters and episodes | From your Ryot history |
| Library (when Ryot is the library source) | Your Ryot collections as lists (Watchlist first); add/remove from the + menu |

Details worth knowing:

- Movies and TV shows only. Ryot's anime, books, games etc. are ignored.
- Titles are matched by TMDB id (Ryot's default source for movies and shows). IMDb ids are converted through
  TMDB, the same way Nuvio already does elsewhere.
- Playback under 2% isn't recorded, mirroring Ryot's own Jellyfin/Plex/Kodi integrations, so zapping
  through sources doesn't pollute your history.
- Removing an item from Continue Watching only hides it in Nuvio (until you watch it again). It never deletes
  anything in Ryot.
- Ryot's auto-managed collections ("In Progress", "Completed", "Reminders") aren't shown as lists.
- Sign-in with username and password keeps a non-expiring Ryot token for that Nuvio profile, never your password.
  Accounts with two-factor or OIDC login can paste an API token instead
  (Ryot: Settings → Integrations → Create API token).
- Each Nuvio profile can connect its own Ryot account.

## Building the APK (no Android Studio needed)

1. Fork `NuvioMedia/NuvioTV` on GitHub and push this branch (`ryot-integration`) to your fork.
2. GitHub Actions runs **Build NuvioTV with Ryot** automatically. When it finishes, open the run and download
   the APK from **Artifacts**.
3. Sideload it on the TV (e.g. with *Downloader* or `adb install`). It installs as a separate app
   (`com.nuviodebug.com`) next to the official Nuvio.

### Stable signing (recommended)

Without these secrets every build gets a new signing key, so you must uninstall the previous build first (losing
its settings). Create a key once — any machine with Java, or a GitHub Codespace, works:

```sh
keytool -genkeypair -keystore ryot.jks -alias nuvio-ryot -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Nuvio Ryot build" -storepass 'CHOOSE-A-PASSWORD' -keypass 'CHOOSE-A-PASSWORD'
base64 -w0 ryot.jks   # macOS: base64 -i ryot.jks
```

In your fork: **Settings → Secrets and variables → Actions → New repository secret**

- `RYOT_KEYSTORE_BASE64` — the base64 output
- `RYOT_KEYSTORE_PASSWORD` — the password you chose

Keep `ryot.jks` somewhere safe; losing it means reinstalling once.

### Limitation of self-built forks

Nuvio's own account service (sign-in, cloud sync of addons and profiles) uses keys that only the official build
has, so it won't work in this build. Install addons locally on the TV. Trakt/Simkl/MDBList device login may
also need their own client ids in `local.properties` (see `local.example.properties`).

## Using it

1. **Settings → Tracking → Ryot**: enter your server address (e.g. `ryot.example.com` or `192.168.1.10:8000`)
   and sign in.
2. Under **Sources**, pick Ryot for *Watch progress* and/or *Library*.
3. The account dialog shows the last sync and has **Sync now** for a full resync. Nuvio otherwise syncs
   incrementally every 10 minutes when you browse, and does a full resync daily.

## Code map

- `app/src/main/java/com/nuvio/tv/data/ryot/`
  - `RyotApi.kt` — GraphQL client and the Ryot operations used
  - `RyotModels.kt` — snapshot model and its on-disk format
  - `RyotSync.kt` — full and incremental sync
  - `RyotWrites.kt` — title resolution, scrobbling, history marks, optimistic updates
  - `RyotProjection.kt` — snapshot → Continue Watching, Next Up, watched state, library
  - `RyotRepository.kt` — per-profile state, persistence, account connect/disconnect
  - `RyotAuthStore.kt` — encrypted per-profile credentials, OkHttp transport
  - `RyotTrackingProviders.kt` — adapters to Nuvio's tracking interfaces
- `app/src/main/java/com/nuvio/tv/core/di/RyotModule.kt` — Hilt wiring
- `app/src/main/java/com/nuvio/tv/ui/screens/settings/RyotSettings.kt` — settings dialog
- Tests: `app/src/test/java/com/nuvio/tv/data/ryot/`
