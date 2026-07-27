# Troubleshooting

## Device Owner command fails

- `Not allowed to set the device owner`: the tablet was provisioned or has an account. Back up, factory-reset, skip every account and retry. Removing accounts later is not always sufficient on OEM builds.
- `Unknown admin`: install the APK first and confirm package `dev.carthingspotify.controller` exists. Do not use an APK whose application ID was changed.
- The scripts refuse the device: run `adb shell getprop ro.product.model`. They intentionally accept only TB-X505F identity, never X505L/X505X or another M10 generation.
- Only one attached tablet should be used, or pass `-Serial <adb-serial>` to each script.

## Spotify sign-in fails

- Redirect mismatch: dashboard URI must be exactly `http://127.0.0.1:25566/callback`; `localhost`, HTTPS, a missing path or another port is different.
- Invalid client: enter the public Client ID, not the Client Secret.
- Browser never returns: keep Chrome enabled, retry from the admin menu, and wait for the green Connected page. The loopback listener closes after three minutes.
- User not authorized: add that Spotify account to the Development Mode app's authorized-user list.
- Premium error: the Development Mode app owner and player-control user need active Premium under Spotify's 2026 rules.
- A failed login does not expose a public port. The callback socket binds only to `127.0.0.1`.

## PC/device does not appear

1. Confirm tablet and PC have internet access. They need not accept inbound LAN traffic because both talk to Spotify.
2. Open the official Spotify Windows client, sign into the same account and play/pause one song.
3. Open Devices on the tablet and select the PC. Device IDs can rotate; the app rematches the saved PC name.
4. Disable Spotify Offline Mode on the PC. Wake it fully after sleep.
5. If two devices share the same name, rename the Windows PC/Spotify device so selection is unambiguous.

## Controls return 403, 404 or 429

- `403`: Premium or an OAuth scope is missing. Disconnect and connect again to grant the current scopes.
- `404`: Spotify has no active player or the remembered device is asleep. Start playback once on Windows.
- `429`: the app displays the wait and honors Spotify's `Retry-After`; do not repeatedly reconnect or tap controls during it.

## Playlist contents are empty

Spotify's February 2026 Development Mode changes limit playlist contents to playlists the user owns or collaborates on. A followed playlist may still appear and play as a Spotify context but cannot be expanded through the available endpoint. Album contents are unaffected.

## Playlist opens but a track will not start

- Confirm the playlist or album is still visible in the detail header and the Windows Spotify client is active.
- A playlist track starts with `context_uri` plus `offset.uri`; this preserves the complete context instead of replacing it with one song.
- Local/unavailable tracks are filtered. Spotify may also reject a relinked item that is unavailable in the account's market.
- Very large collections are capped at 1,000 displayed items to avoid excessive requests and memory use.

## Up Next selection is refused

Spotify's queue response contains track objects but no arbitrary queue index or playlist context. The controller uses the active playlist/album context from the playback-state response. If playback came from an individual song, artist radio, or a manually assembled queue, the controller cannot jump to an arbitrary row without replacing the queue and therefore refuses the action. Open a playlist or album first.

## Lyrics are unavailable

Lyrics are disabled by default because enabling them sends track, artist, album and duration metadata to the independent LRCLIB service. Enable **Lyrics service** in the PIN-protected administrator menu after reviewing the disclosure. LRCLIB may have no match or may return imperfect timing; Spotify credentials are never sent.

## System bars or Android desktop appear

- Without Device Owner, immersive mode is best-effort. Complete `configure-device-owner.ps1` for lock task and status-bar policy.
- If the controller is Device Owner, open the admin menu and tap **Dedicated mode: active** to reapply policy, then reboot.
- After an intentional kiosk exit, choose Lenovo Launcher as the default Home app.

## Wi-Fi disconnects

The app requests a high-performance Wi-Fi lock while alive but Android cannot reconnect to a network whose credentials or access point are invalid. Use the PIN menu's Wi-Fi settings, forget/rejoin the network, disable captive portals and assign stable DHCP infrastructure if possible.

## Screen sleeps while charging

Check that Android reports the charger (`adb shell dumpsys battery`) and that the supply/cable are sound. The app sets `FLAG_KEEP_SCREEN_ON` only while plugged/charging/full. On battery, normal sleep is intentional.

## Crash or blank UI

The crash alarm and Home role should reopen the Activity. For diagnostics:

```powershell
adb logcat -c
adb logcat ActivityManager:E AndroidRuntime:E CarThing:V '*:S'
```

Reinstalling with `install-apk.ps1` preserves app data/tokens when the signing key is unchanged. Do not clear package data unless you are prepared to reconnect Spotify and recreate the PIN.

## Restore packages or uninstall

```powershell
.\scripts\restore-disabled-packages.ps1
.\scripts\uninstall-and-restore.ps1
```

If uninstall reports that the app is still Device Owner, use the tablet's PIN-protected **Exit dedicated mode** first. This is an intentional safety requirement.
