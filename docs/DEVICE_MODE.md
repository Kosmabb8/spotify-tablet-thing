# Device mode, backup, ROM decision and rollback

## Decision

| Approach | Finding | Decision |
|---|---|---|
| Stock Android 10 + custom Home + Device Owner + lock task | Android officially supports fully managed Device Owner and lock-task allowlisting. It needs no root or bootloader change and is reversible from the DPC. | **Selected** |
| Stock Android 10 + ADB debloat | Per-user disabling is reversible, but broad package lists are device/region dependent. Performance benefit is secondary to kiosk policy. | **Optional, conservative allowlist only** |
| Custom ROM | TB-X505F is absent from official LineageOS support. Located Lineage/GSI builds are unofficial; reports include permissive SELinux, missing/changed hardware support, boot loops and failed recovery. | **Rejected** |

Lenovo's official product reference identifies the TB-X505F family as a Snapdragon 429 tablet with a 10.1-inch 1280×800 panel, and Lenovo's upgrade matrix records Android 10 as its completed official upgrade. This project therefore optimizes for that exact stock base.

Sources checked July 20, 2026:

- [Lenovo TB-X505F manuals/support](https://pcsupport.lenovo.com/us/en/products/tablets/m-series-tablets/tab-m10-hd/documentation/doc_userguide)
- [Lenovo Tab M10 product specifications](https://psref.lenovo.com/syspool/Sys/PDF/Lenovo_Tablets/Tab_M10/Tab_M10_Spec.html)
- [Lenovo Android upgrade matrix](https://support.lenovo.com/ie/en/solutions/ht501098)
- [LineageOS supported devices](https://wiki.lineageos.org/devices/)
- [AOSP Device Owner test setup](https://source.android.com/docs/devices/admin/testing-setup)
- [AOSP device-management requirements](https://source.android.com/docs/devices/admin/implement)

No firmware image, unlock command, recovery image, custom ROM or flashing tool is included.

## Rootless backup before Device Owner provisioning

Device Owner usually needs a factory reset. A truthful limitation matters: Android 10 does **not** allow ADB to make a complete backup of every app's private data, passkeys, DRM licenses, work profiles or authenticator secrets without root, and rooting would make this process less safe.

Complete the following checklist before resetting:

1. Run `scripts\backup-before-reset.ps1 -IncludeSharedStorage`. It saves exact build properties, installed/disabled package lists, Android settings, Wi-Fi diagnostics and a copy of shared storage.
2. Copy the resulting `backups\TB-X505F-*` folder to another physical disk.
3. In Android Settings, verify Google backup/sync completion for contacts, calendar and supported app data.
4. Export photos, downloads, documents and any microSD content independently; open several copied files on the PC.
5. Export authenticator/2FA accounts using each provider's supported migration process. Store one-time recovery codes offline.
6. Record Wi-Fi SSIDs/passwords, the tablet serial number and the full Android build number shown in Settings.
7. Sign out of sensitive apps and remove any work profile/MDM enrollment through its supported process.
8. Confirm the Spotify Client ID and release-signing keystore are backed up; they are not tablet data.
9. Charge above 60%, use a reliable cable/USB port, and verify the tablet can reboot normally before reset.

## Device Owner rollback

This is the supported rollback and does not alter firmware:

1. Hold the top-right corner for 2.5 seconds and enter the administrator PIN.
2. Choose **Exit dedicated mode** and confirm. The app stops lock task, reenables status bar/keyguard, clears its persistent Home policy and removes itself as Device Owner.
3. Select Lenovo Launcher in Android Home-app settings.
4. Run `scripts\uninstall-and-restore.ps1` to re-enable exactly the packages recorded by the latest debloat run and uninstall the controller.
5. If Android settings remain unusual, reboot. A normal factory reset is the final stock-Android cleanup; it does not require bootloader unlock.

If the PIN is lost while the app is Device Owner, factory reset from stock recovery is the supported escape. This erases user data, which is why the pre-reset backup matters.

## Firmware/custom-ROM safety boundary

This project does not recommend unlocking or flashing. If that decision is revisited later, stop and establish the following recovery capability **before** any unlock or flash:

1. Re-run and externally copy every item in the backup checklist above. Bootloader unlocking itself normally erases userdata.
2. Verify **TB-X505F** independently in Settings, `adb shell getprop ro.product.model`, the chassis label and Lenovo's serial-number lookup. Similar names such as TB-X505L, TB-X505X, TB-X605F, TB-X306F and later M10 generations are not interchangeable.
3. Install Lenovo's current official Rescue and Smart Assistant/Moto Rescue tool from Lenovo Support and let it identify the tablet by serial/model. Obtain recovery firmware only through that official model-matched flow; do not trust a filename from a mirror.
4. Record the full stock build/region. Confirm the rescue tool actually offers TB-X505F recovery before modifying the bootloader.
5. Understand that a custom recovery cannot be considered the only backup: flashing the wrong preloader/boot chain, losing USB enumeration, corrupting partition metadata, anti-rollback mismatch or power loss can make that recovery inaccessible.
6. Keep the official rescue installer/package, drivers, a second cable and the backup on an offline PC. Do not start if Lenovo no longer serves a model-matched recovery.
7. A rollback would use Lenovo's official **Rescue** workflow for the detected TB-X505F to rewrite the complete stock image, then factory-reset and accept stock OTA updates. Restore user files only after the device boots and the exact model/build is verified.

Even with those preparations, permanent hard-brick, data loss, reduced Widevine/DRM level, loss of OTA updates and broken camera/audio/Wi-Fi are possible. A third-party image marked merely `arm64`, `GSI`, `M10`, `X505`, or intended for another hardware revision is not model verification. Never flash it.

## Permanent charger operation

- Use the original-quality regulated 5 V/2 A supply and a sound cable; the TB-X505F's HD model uses Micro-USB.
- Use the lowest comfortable brightness and the built-in inactivity dimmer. Avoid static maximum-brightness screens.
- Keep the tablet ventilated, out of sunlight and away from insulating covers. Charging heat accelerates cell aging.
- If the stock firmware exposes charge-limit/battery-protection mode, enable it. Otherwise consider a reputable scheduled smart plug so the battery is not held at 100% continuously.
- Inspect monthly for swelling, case separation, unusual heat or odor. Disconnect immediately if any appears; a swollen lithium battery is a hardware safety issue.
- The app holds the screen awake only while external power is reported and keeps a Wi-Fi lock while running.
