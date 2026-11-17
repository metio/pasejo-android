<!--
SPDX-FileCopyrightText: The pasejo Authors
SPDX-License-Identifier: 0BSD
-->

# pasejo for Android

An Android app for stores on a [pasejo](https://github.com/metio/pasejo) server. It signs in through the system browser and enrolls the phone as a device that an admin approves. It then lists stores and entries, reveals secrets, shows TOTP codes, and fills logins in apps and browsers through Android's autofill framework. It reads only: editing secrets and approving devices are not implemented yet. The interface is translated into the same eight languages as the pasejo CLI.

The app needs Android 13 (API 33) or later and a secure lock screen. Its design is `design/mobile.md` in the pasejo repository.

## Security

- **Device keys.**
  - On phones whose key store supports Curve25519 (KeyMint 2), the signing key (Ed25519) and the age identity (X25519) are generated and used inside the trusted execution environment.
  - Other phones generate both in memory and seal them under key-store keys. The age identity is sealed to a key that needs a biometric or the screen lock for every use.
  - The app refuses to enroll when the key store keeps any of these keys in software, and keys are never backed up.
- **Server answers.** The core verifies every log, index and object against keys pinned on first use: the organization fingerprint and the server's checkpoint key. A server that withholds, swaps or rolls back data is detected.
- **Unlocking.** Store keys stay in memory for five minutes after an unlock, or less when managed configuration says so. They are dropped when the screen turns off. Secret values are fetched per reveal, so the server records every read.
- **Offline reads.** Only in organizations that allow them (`pasejo org settings --offline-reads true`). Each value the phone fetched is then also encrypted to the phone's own age key and kept in the device state. Without network, unlocking opens that copy with the same authentication; reads from it are reported to the server on the next online unlock. The copy is deleted when the organization stops allowing offline reads, when the device is removed, and on sign-out. A phone that stays offline cannot learn of the first two and reports nothing.
- **Autofill.**
  - The app trusts a web domain only when a browser from Google's list of privileged browsers reports it, matched by package name and signing certificate.
  - An app receives the credentials of a domain whose `/.well-known/assetlinks.json` names the app. The domains checked are the ones its package name spells and the other URLs of entries whose `url` names `androidapp://<package>`, at most eight per fill.
  - An app also receives entries whose `url` names `androidapp://<package>` and whose `android-cert` field holds the app's certificate fingerprint.
  - The chosen credential is released only when it still matches.
- **Screens.** They are excluded from screenshots and the recents list. Copied passwords are marked sensitive and cleared after 45 seconds.

## Managed configuration

A device policy controller can set `server_url`, `organization`, `genesis_hash`, `checkpoint_key`, `max_unlock_seconds` and `allow_copy` (`app/src/main/res/xml/app_restrictions.xml`). Set values lock the matching fields of the set-up screen.

## Building

The app compiles `pasejo-mobile` from a pasejo checkout next to this one (`../pasejo`, or `-Ppasejo.dir=<path>`). The toolchain:

- The JDK, Gradle and Rust come from [breto](https://github.com/metio/breto): run `eval "$(breto env)"`. The project has no Gradle wrapper; `.breto.edn` names the Gradle it builds with.
- The Android SDK with `platforms;android-36` and NDK `30.0.16248370`, named in `local.properties` as `sdk.dir=<path>`.
- The Rust targets `aarch64-linux-android` and `x86_64-linux-android`.

```sh
gradle assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
gradle testDebugUnitTest          # JVM tests, with the core and pasejo-server built for this computer
gradle connectedDebugAndroidTest  # instrumented tests on a running emulator or phone
```

The Gradle build cross-compiles the core for `arm64-v8a` and `x86_64` and generates the Kotlin bindings with UniFFI. The instrumented tests create keys without the authentication requirement, so they run on an emulator without a lock screen. The autofill tests make pasejo the system's autofill service, and the managed-configuration tests make the test APK device owner, which works only on a device without accounts, such as a fresh emulator; both restore the device afterwards. Emulators keep keys in software, so the test that requires secure hardware runs only on a phone. CI (`.github/workflows/verify.yml`) builds against the `main` branch of metio/pasejo and runs both suites, the instrumented one on an API 36 emulator.

## License

0BSD, see `LICENSES/0BSD.txt`.
