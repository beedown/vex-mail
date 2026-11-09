# VEX Mail

Every inbox in one place, sorted by what needs you.

## Update VEX or set it up

1. Click **Add file**, then **Upload files**. Drag in all the VEX files (not the folder), then click **Commit changes**.
2. Only when Claude gives you a new build file: open `.github/workflows/main.yml`, click the pencil, replace everything with the box at the bottom of this page, and click **Commit changes** twice.
3. Open **Actions** and wait for a green check.
4. On your Android phone, open this repository's **Releases** page, tap **VEX.apk** under the newest build, and install it over the old one. Your mail stays.

Red ✗? Click it, click **apk**, then the step with the ✗, and send a screenshot to Claude.

## Several email addresses

In VEX, open **Settings** and tap **Add email** to connect another address. Tap **Edit** next to any address to give it a name, like Work or School, and a color. Use the inbox button at the top to see one address at a time, and pick which address to send from when you write.

## Automatic updates

Once this version is installed, VEX checks this repository for new builds by itself. When it finds one, it downloads and installs it. The first time, Android asks you to allow VEX to install apps. After that, Android may ask you to tap **Update** for each new version. You can turn this off in VEX under **Settings**, then **App updates**.

## Connect your real email

In VEX, open **Settings** and tap **Connect**. Gmail, Yahoo and iCloud need an **app password** instead of your normal password:

- **Gmail:** turn on 2-Step Verification in your Google account, then create an app password at myaccount.google.com/apppasswords.
- **Yahoo:** create one in your Yahoo account's security settings.
- **iCloud:** create an app-specific password in your Apple account.

Outlook and Hotmail aren't supported yet. Your password is saved only on your phone.

## The build file

```yaml
name: Build the VEX Android app

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  apk:
    runs-on: ubuntu-latest
    steps:
      - name: Get the VEX files
        uses: actions/checkout@v4

      - name: Check the VEX files are in the right place
        run: |
          for f in index.html icon-maskable-512.png vex.keystore MainActivity.java MailBridge.java AndroidManifest.xml app-build.gradle project-build.gradle settings.gradle gradle.properties; do
            if [ ! -f "$f" ]; then
              echo "::error::$f is missing from the top of the repository. Upload all the VEX files, not the folder they are in."
              exit 1
            fi
          done

      - name: Set up Java
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up the Android tools
        run: |
          SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
          echo "ANDROID_HOME=$SDK" >> "$GITHUB_ENV"
          yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true
          "$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" "build-tools;34.0.0" "platform-tools"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.7"

      - name: Put the Android project together
        run: |
          set -e
          A=android/app/src/main
          mkdir -p "$A/java/com/vexmail/app" "$A/assets" "$A/res/drawable-nodpi" "$A/res/mipmap-anydpi-v26"
          cp settings.gradle gradle.properties android/
          cp project-build.gradle android/build.gradle
          cp app-build.gradle android/app/build.gradle
          cp vex.keystore android/app/vex.keystore
          cp AndroidManifest.xml "$A/AndroidManifest.xml"
          cp MainActivity.java MailBridge.java "$A/java/com/vexmail/app/"
          cp index.html "$A/assets/index.html"
          cp icon-maskable-512.png "$A/res/drawable-nodpi/vex_foreground.png"
          cat > "$A/res/mipmap-anydpi-v26/ic_launcher.xml" <<'EOF'
          <?xml version="1.0" encoding="utf-8"?>
          <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
              <background android:drawable="@android:color/black" />
              <foreground android:drawable="@drawable/vex_foreground" />
          </adaptive-icon>
          EOF
          echo "Android project ready"

      - name: Build the APK
        working-directory: android
        run: gradle assembleRelease --no-daemon --stacktrace

      - name: Name the file VEX.apk
        run: cp android/app/build/outputs/apk/release/app-release.apk VEX.apk

      - name: Publish VEX.apk as a download
        uses: softprops/action-gh-release@v2
        with:
          tag_name: build-${{ github.run_number }}
          name: VEX build ${{ github.run_number }}
          body: Open this page on your Android phone, tap VEX.apk below, then open the file to install VEX.
          files: VEX.apk
```
