# Beginner installation: Lenovo TB-X505F + Windows 10/11

This guide assumes you have never used ADB. Read the whole guide once before changing the tablet. The recommended path keeps stock Android 10, requires no root, and does not unlock or flash the bootloader.

## Before you begin

You need:

- A Lenovo **TB-X505F** running the official Android 10 firmware
- A Windows 10 or Windows 11 PC with an administrator account
- A reliable data-capable Micro-USB cable (some charging cables carry no data)
- Wi-Fi internet access for both devices
- The Spotify desktop app on Windows, signed in to the account you will control
- Spotify Premium and a Spotify Developer application Client ID
- The controller APK from this repository's latest GitHub Release, or an APK you build from source
- A backup location on another drive if you later enable full Device Owner mode

The Windows Spotify app is the playback endpoint. No separate Windows companion service is required, no incoming firewall port is opened, and the tablet does not need Spotify installed.

> **DATA-ERASE WARNING:** The optional full Device Owner procedure requires a factory reset in most cases. A factory reset erases tablet apps and user data. Complete the backup checklist before that section.

> **NO BOOTLOADER OR FLASHING:** None of these steps unlocks the bootloader, flashes firmware, installs a custom recovery, or uses a custom ROM. Do not follow model-generic firmware instructions from elsewhere.

## 1. Download the files

1. On the PC, open this repository's **Releases** page.
2. Download the newest file named like `spotify-car-controller-tb-x505f-v1.0.1.apk` and its `SHA256SUMS.txt` file. If no signed release exists yet, build from source using the README instead of downloading an APK from an unofficial mirror.
3. Download the current **SDK Platform-Tools for Windows** from [Google's official page](https://developer.android.com/tools/releases/platform-tools). Accept Google's license and save the ZIP.
4. Install the [official Spotify app for Windows](https://www.spotify.com/download/windows/) if it is not already installed.
5. Download this repository as a ZIP from GitHub and extract it. The safe PowerShell setup scripts are in its `scripts` folder.

Optional checksum verification in PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 .\spotify-car-controller-tb-x505f-v1.0.1.apk
Get-Content .\SHA256SUMS.txt
```

The two SHA-256 values must match. Stop if they do not.

## 2. Install ADB and, only if needed, a Lenovo USB driver

1. Right-click the downloaded Platform-Tools ZIP, choose **Extract All**, and extract it to `C:\adb`. The file `C:\adb\platform-tools\adb.exe` should exist.
2. Open PowerShell: press Start, type `PowerShell`, and open **Windows PowerShell** or **Terminal**.
3. For this PowerShell window only, add ADB to the command path:

   ```powershell
   $env:Path = 'C:\adb\platform-tools;' + $env:Path
   adb version
   ```

4. Windows normally installs an MTP/ADB driver after the tablet is connected. If Device Manager shows an unknown Android device, use the [Android OEM driver instructions](https://developer.android.com/studio/run/oem-usb) and follow their Lenovo Support link for the official current Lenovo driver. Do not install unsigned driver bundles from download mirrors.

## 3. Enable Developer Options and USB debugging

On the tablet:

1. Open **Settings**.
2. Open **System → About tablet**. Lenovo's wording may be **About tablet → Build number**.
3. Tap **Build number** seven times. Enter the tablet PIN if requested. Android reports that you are now a developer.
4. Go back to **Settings → System → Advanced → Developer options**.
5. Turn on **USB debugging** and accept the warning.

Leave the tablet unlocked and awake.

## 4. Connect the tablet at the correct time

Connect the tablet only after Platform-Tools is extracted and USB debugging is on:

1. Connect the tablet directly to the PC with the data-capable cable. Avoid an unpowered USB hub.
2. Pull down the tablet notification shade and tap the USB notification.
3. Select **File transfer / Android Auto** or **File Transfer (MTP)**. Do not choose charge-only.
4. When **Allow USB debugging?** appears, compare the prompt with the PC you are using, select **Always allow from this computer**, and tap **Allow**.

If the authorization prompt does not appear, unplug and reconnect once. You can also choose **Revoke USB debugging authorizations** in Developer options, then reconnect.

## 5. Verify the exact device before making changes

In the same PowerShell window, run:

```powershell
adb devices -l
adb shell getprop ro.product.model
```

The first command must show exactly one device whose state is `device`, not `unauthorized` or `offline`. The second command must report `TB-X505F`. Stop if it reports TB-X505L, TB-X505X, TB-X605F, TB-X306F, or anything else.

All included scripts repeat this model check and stop without changing the device if it is not a TB-X505F. If more than one ADB device is connected, disconnect the others or pass `-Serial` followed by the identifier shown by `adb devices`.

## 6. Install the APK

In File Explorer, open the extracted repository folder. Click the address bar, type `powershell`, and press Enter. Then run:

```powershell
.\scripts\install-apk.ps1 -Apk 'C:\path\to\spotify-car-controller-tb-x505f-v1.0.1.apk'
.\scripts\grant-supported-permissions.ps1
.\scripts\enable-autostart.ps1
```

Replace the example APK path with the real downloaded path. The permission script validates ordinary network, Wi-Fi, wake-lock, and boot permissions; it does not silently grant contacts, files, microphone, camera, or location access.

For a safe pilot, press the tablet Home button and choose **Car Thing Controller → Always**. The custom interface is now the Home screen. Pilot mode is reversible and does not erase data, but it is not as tamper-resistant as Device Owner mode.

## 7. Configure the Spotify Developer application

On the PC:

1. Sign in to the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) with the Premium account that will own this personal controller.
2. Select **Create app** and give it any private descriptive name.
3. Select the **Web API** when Spotify asks which API is used.
4. Add this redirect URI exactly:

   ```text
   http://127.0.0.1:25566/callback
   ```

5. Save the settings and copy the **Client ID**.
6. Do **not** copy or enter the Client Secret. The Android app uses Authorization Code with PKCE, which is designed for public mobile clients.
7. If a different Spotify account will use the tablet, add that account under the app's authorized users. Spotify Development Mode currently limits personal apps to a small authorized-user list and requires Premium for player control.

## 8. Connect the Spotify account

1. On the tablet, hold the top-right clock area for about 2.5 seconds.
2. Create the administrator PIN. Store it safely; the app stores only a salted password hash.
3. Open **Spotify Client ID** and enter the Client ID from the dashboard.
4. Choose **Spotify account: connect**.
5. Complete Spotify sign-in and authorization in the browser. The redirect returns locally to the app; `127.0.0.1` is the tablet itself, not a public or LAN address.

Tokens are encrypted in Android's Keystore-backed storage. They are not written to the project, `.env.example`, ADB scripts, or Windows.

## 9. Configure Windows playback (no companion installation)

There is no project-specific Windows companion to install:

1. Open the official Spotify desktop app on Windows.
2. Sign into the same Spotify account and play, then pause, one track so the PC becomes an active Spotify Connect device.
3. Keep Spotify running and the PC online.
4. On the tablet, open **Devices** and select the Windows PC by its displayed Spotify device name.
5. Test play/pause and next-track controls. Audio must remain on the Windows PC.

The controller remembers the PC's display name and refreshes its temporary Spotify device ID when necessary.

## 10. Pair and customize the tablet

From the hidden administrator menu you can:

- choose or open Android Wi-Fi settings;
- set brightness and inactivity dimming;
- reconnect the Spotify account;
- switch playback to a listed Spotify device manually;
- show or hide the clock; and
- exit dedicated mode safely.

Reboot once while still in pilot mode. Confirm that Wi-Fi reconnects and the controller returns as Home before proceeding.

## 11. Enable full dedicated kiosk mode

Only do this after the pilot works.

> **ERASES DATA:** Factory reset removes installed applications, local files, saved accounts, and settings. Rootless ADB cannot completely back up every Android app's private data, passkeys, DRM licenses, or authenticator secrets.

1. Reconnect ADB and create a rootless diagnostic/shared-storage copy:

   ```powershell
   .\scripts\backup-before-reset.ps1 -IncludeSharedStorage
   ```

2. Copy the new `backups\TB-X505F-*` folder to another physical drive. Separately verify photos/documents, cloud sync, authenticator migration, recovery codes, and any microSD data as described in [Device mode and rollback](DEVICE_MODE.md).
3. On the tablet, use **Settings → System → Reset options → Erase all data (factory reset)**.
4. During Android setup, connect to Wi-Fi but skip every Google/Lenovo account, work profile, and screen-lock enrollment. Device Owner cannot normally be added if accounts already exist.
5. Re-enable Developer Options and USB debugging, reconnect USB in File Transfer mode, and authorize the PC again.
6. Verify `adb devices -l` and `adb shell getprop ro.product.model` again.
7. Reinstall and provision:

   ```powershell
   .\scripts\install-apk.ps1 -Apk 'C:\path\to\spotify-car-controller-tb-x505f-v1.0.1.apk'
   .\scripts\grant-supported-permissions.ps1
   .\scripts\configure-device-owner.ps1
   .\scripts\enable-autostart.ps1
   ```

8. The Device Owner script explains the change and requires you to type uppercase `YES` before applying it.
9. Re-enter the admin PIN, Spotify Client ID/account, brightness, and Wi-Fi. Playback follows Spotify's active device unless you manually select another device.
10. Reboot at least twice. Confirm direct launch, hidden system bars, lock-task confinement, Wi-Fi reconnection, charging-only screen wake, and crash recovery.

Device Owner and lock-task policy are Android-supported management features. They do not root or reflash the tablet, but returning to Device Owner after removing it normally requires another factory reset.

## 12. Optional conservative debloat

Debloating is not required. Only after kiosk mode works across several reboots, you may run:

```powershell
.\scripts\debloat-optional.ps1
```

The script lists a small fixed set of detected, user-facing optional apps, requires uppercase `YES`, uses reversible `pm disable-user --user 0`, and records exactly what it changed. It deliberately excludes System UI, Settings, Wi-Fi, Package Installer, WebView, Chrome, Play services, Play Store, Lenovo launcher/update/recovery, and ambiguous Lenovo services.

Restore the recorded list at any time:

```powershell
.\scripts\restore-disabled-packages.ps1
```

## 13. Update the application later

1. Download the newer signed APK and checksum from this repository's Releases page.
2. Verify its SHA-256 value.
3. Connect and authorize the same tablet, then run:

   ```powershell
   .\scripts\install-apk.ps1 -Apk 'C:\path\to\new-version.apk'
   ```

4. Reboot and verify the version and controls.

`adb install -r` preserves the app's data only when the new APK has the same application ID and is signed by the same release key. Never uninstall first when updating. If Android reports a signature mismatch, stop and obtain the official release; bypassing it by uninstalling would erase controller settings and tokens.

## 14. Exit kiosk mode and restore normal Android

1. Hold the top-right area for 2.5 seconds, enter the admin PIN, choose **Exit dedicated mode**, and confirm.
2. The app stops lock task, restores status/keyguard policy, clears its persistent Home policy, removes its own Device Owner role, and opens Android Home-app settings.
3. Select **Lenovo Launcher** as the Home app.
4. Connect ADB and run:

   ```powershell
   .\scripts\uninstall-and-restore.ps1
   ```

The script restores packages recorded by the most recent optional debloat run and uninstalls the controller. It refuses to proceed while the controller is still Device Owner.

If the administrator PIN is lost while Device Owner is active, the supported last-resort escape is a stock recovery factory reset. That erases user data but does not require firmware flashing.

## Troubleshooting ADB

- **`adb` is not recognized:** repeat the temporary `$env:Path` command or open PowerShell in `C:\adb\platform-tools`.
- **No device is listed:** unlock the tablet, use a data cable, choose File Transfer, try another direct USB port, and inspect Device Manager.
- **`unauthorized`:** unlock the tablet and accept the RSA prompt. Revoke USB debugging authorizations and reconnect if necessary.
- **`offline`:** run `adb kill-server`, unplug/reconnect, then run `adb devices -l` again.
- **Wrong model:** stop. The scripts intentionally support only TB-X505F.
- **Spotify PC is absent:** open Spotify on Windows, sign into the same account, play/pause once, and refresh Devices on the tablet.
- **Spotify redirect error:** the dashboard URI must be exactly `http://127.0.0.1:25566/callback`.

For app-specific errors, see [Troubleshooting](TROUBLESHOOTING.md). For backup limits, charger safety, ROM research, and rollback, see [Device mode and rollback](DEVICE_MODE.md).
