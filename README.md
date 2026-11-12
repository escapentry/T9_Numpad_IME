# T9 Numpad IME for Android

A one-handed 12-key Android keyboard combining **Gboard's 4-column Numpad ergonomics** with **OG T9 predictive text**, **Oxford core English vocabulary**, **Spell-to-Add dictionary learning**, **3-page Symbols**, and **collapsible IME Settings**.

## Option 1: Build an APK in the Cloud (No Android Studio Needed)
This project includes `.github/workflows/build-apk.yml`.
1. Unzip `T9NumpadIME-Android.zip` and push the contents to a new GitHub repository.
2. Open the repository's **Actions** tab on GitHub.
3. Click the **Build Android APK** workflow run and download **T9NumpadIME-debug-apk** (`app-debug.apk`).
4. Transfer or download `app-debug.apk` on your Android phone and tap to install (enable **Install unknown apps** if prompted).

## Option 2: Build an APK Locally with Android Studio
1. Open the unzipped `T9NumpadIME` folder in **Android Studio**.
2. Wait for Gradle Sync to finish.
3. Click **Build > Build Bundle(s) / APK(s) > Build APK(s)** (or run `gradle assembleDebug`).
4. The installable APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Enabling & Configuring the Keyboard
1. Open the **T9 Numpad IME** app icon on your phone (it launches `SettingsActivity`).
2. Tap **1. Enable IME**, toggle on **T9 Numpad (One-Handed)**, then tap **2. Select IME**.
3. Expand any setting card (**Appearance & Themes**, **Keyboard Height**, **Sound & Haptic Feedback** with volume and strength sliders, **Typing & Correction**, **Personal Dictionary**) — your preferences and active input mode (`T9` / `ABC` / `123`) persist across every text field.
