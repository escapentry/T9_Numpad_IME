# T9 Numpad IME for Android

A one-handed 12-key Android keyboard combining **Gboard's 4-column Numpad ergonomics** with **OG T9 predictive text** and **Multi-tap fallback**.

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

## Enabling the Keyboard on Android
1. After installing the APK, open **Settings > System > Keyboard > On-screen keyboard**.
2. Toggle on **T9 Numpad (One-Handed)**.
3. Open any text field (Messages, Notes, Browser), tap the keyboard switcher icon in the bottom navigation bar, and choose **T9 Numpad**.
