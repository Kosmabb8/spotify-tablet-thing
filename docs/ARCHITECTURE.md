# Architecture

## System boundary

Android 10 remains the operating system. The controller is simultaneously:

- a native Kotlin Android application;
- an alternative Home launcher;
- a Device Policy Controller when provisioned as Device Owner; and
- a lock-task allowlisted kiosk activity.

The tablet never streams Spotify audio. It controls the official Spotify client on Windows through Spotify's Web API and Spotify Connect state.

```text
Tablet UI → Spotify Accounts/Web API → Spotify Connect → Windows Spotify client → PC audio
```

No Windows companion, local-network server, root process, custom ROM or firmware component is involved.

## Application components

- `MainActivity` owns lifecycle, adaptive polling, background work, image caching, navigation, administrator actions and system-power behavior.
- `CarThingView` is a custom 1280×800 rendering and touch surface for Now Playing, Library, Collection detail, Search, Up Next, Devices, Lyrics and PIN screens.
- `SpotifyAuth` implements Authorization Code with PKCE and a temporary callback listener bound only to `127.0.0.1:25566`.
- `SecureTokenStore` encrypts access and refresh tokens with AES-GCM using an Android Keystore key.
- `SpotifyApi` performs authenticated Web API calls, parses responses, honors `429 Retry-After`, and paginates playlist/album content.
- `LyricsApi` optionally queries LRCLIB after an explicit administrator opt-in.
- `DeviceModeManager` applies/removes Device Owner, lock-task, status-bar, keyguard and persistent Home policies.
- `BootReceiver`, `CrashRestarter` and `RestartReceiver` return the controller after boot or an unexpected process failure.

## Playback state

`GET /me/player` supplies the current track, playback flags, target device and active `context.uri`. The UI polls every three seconds while playing, every eight seconds while paused, and more slowly while inactive/blackened.

The app advances progress locally between responses. Spotify device IDs can rotate, so the saved Windows device name is used to resolve a fresh ID periodically.

## Playlist browsing

Library and Search return `MediaItem` containers. Selecting a playlist or album loads its items on a background thread into a separate `CollectionView`; it does not overwrite the Library/Search result state.

The Collection screen draws:

- container artwork and metadata;
- a full-context Play button;
- numbered, paginated track rows; and
- a Back action to the originating screen.

Playlist and album endpoints are fetched in 50-item API pages, up to a defensive maximum of 1,000 display items.

## Context-preserving selection

Starting a single URI with an `uris` array creates a one-track playback context and can destroy the visible queue. Collection and Up Next selections therefore use:

```json
{
  "context_uri": "spotify:playlist:EXAMPLE",
  "offset": {
    "uri": "spotify:track:EXAMPLE"
  }
}
```

The active context comes from `GET /me/player`; `GET /me/player/queue` does not provide one. Context-preserving selection is allowed only for a track in a playlist or album. If no compatible context exists, the app reports the limitation instead of falling back to a destructive one-track request.

## Rendering and touch

`CarThingView` draws against a fixed logical 1280×800 coordinate system and scales to the physical view. Background gradients are cached until accent artwork changes. Touch regions are rebuilt each frame and invoke the `SurfaceActions` interface implemented by `MainActivity`.

Collection, list and grid views paginate locally. Artwork is loaded only for visible rows, held in bounded in-memory caches, and never written to project files.

## Security and privacy

- OAuth uses PKCE and never embeds a Client Secret.
- Tokens are encrypted at rest and app-data backup is disabled.
- The OAuth listener exists only during sign-in and accepts loopback traffic only.
- No tokens, authorization codes, account names or device identifiers are logged.
- Lyrics are off by default. Enabling them sends track, artist, album and duration metadata to LRCLIB over HTTPS.
- Release signing values are accepted only from prompts, process environment variables or GitHub encrypted secrets.

## Dedicated-device recovery

Device Owner mode is removed from the PIN-protected administrator menu before uninstalling. The included PowerShell scripts verify TB-X505F, avoid firmware changes, record optional package disables, and restore those packages during removal.
