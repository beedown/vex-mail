# Building VEX (for beedown)

This page is for whoever maintains VEX. People who just want the app should read the [README](README.md).

## How it works

GitHub builds both apps by itself every time files are uploaded to this repository:

- **VEX for Android**: a release with `VEX.apk`, marked Latest.
- **VEX for PC**: a release with `VEX-Setup.exe`.

The phone and PC apps check these releases for updates by themselves.

## The files

| File | What it is |
| --- | --- |
| `index.html` | The whole VEX app (shared by Android and PC) |
| `MainActivity.java`, `MailBridge.java`, `Mail.java`, `MailCheckJob.java`, `ReminderReceiver.java`, `AttachmentProvider.java`, `VexWidget.java`, `widget-layout.xml`, `widget-info.xml`, `AndroidManifest.xml`, `app-build.gradle`, `project-build.gradle`, `settings.gradle`, `gradle.properties`, `vex.keystore` | The Android app |
| `microsoft-client-id.txt` (optional) | Turns on Sign in with Microsoft for Hotmail and Outlook (see below) |
| `pc-main.js`, `pc-preload.js`, `pc-package.json` | The Windows app |
| `icon-*.png`, `apple-touch-icon.png`, `manifest.webmanifest`, `sw.js` | Icons and the web version |
| `README.md`, `PRIVACY.md`, `BUILDING.md` | These pages |
| `.github/workflows/main.yml` | The build file below |

Keep `vex.keystore` the same forever: Android only accepts updates signed with the same key.

## Updating VEX

1. Click **Add file**, then **Upload files**, drag in the new files (not their folder), and click **Commit changes**.
2. Only when the build file changes: open `.github/workflows/main.yml`, click the pencil, replace everything with the new version, and click **Commit changes** twice.
3. Open **Actions** and wait for the green check (about 10 minutes).

A red ✗ means a build failed. Click it, click the part with the ✗ (android, pc or release), then the step with the ✗, and copy the error.

## Turn on Sign in with Microsoft (one time)

Microsoft only lets registered apps sign in to Hotmail and Outlook, so VEX needs to be registered once. It's free, but Microsoft asks for an Azure account, and signing up for one asks for a card to check who you are (you're not charged). A parent can do this part.

1. Sign up for a free Azure account, then open portal.azure.com.
2. Search for **App registrations** and click **New registration**.
3. Name it **VEX**. Under supported account types, pick the option that includes **personal Microsoft accounts**. Under Redirect URI, choose **Public client/native (mobile & desktop)** and enter `vexmail://auth`. Click **Register**.
4. Open **Authentication**. Under the mobile and desktop platform, add a second redirect URI: `http://localhost`. Click **Save**.
5. Open **API permissions**, click **Add a permission**, then **Microsoft Graph**, then **Delegated permissions**. Tick **IMAP.AccessAsUser.All**, **SMTP.Send**, **offline_access**, **openid** and **email**, and add them.
6. Open **Overview** and copy the **Application (client) ID**.
7. In this repository, click **Add file**, then **Create new file**. Name it `microsoft-client-id.txt`, paste the ID as the only text, and commit.

The next build has a **Sign in with Microsoft** button on the Hotmail tab, for everyone who uses VEX.

## Optional: the web version

Open **Settings**, then **Pages**. Under **Branch**, pick `main` and `/ (root)`, then click **Save**. The web version can show samples and the calendar, but it can't connect real email.

## The build file

```yaml
name: Build VEX for Android and PC

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  android:
    runs-on: ubuntu-latest
    steps:
      - name: Get the VEX files
        uses: actions/checkout@v4

      - name: Check the VEX files are in the right place
        run: |
          for f in index.html icon-maskable-512.png vex.keystore MainActivity.java MailBridge.java Mail.java MailCheckJob.java ReminderReceiver.java AttachmentProvider.java VexWidget.java widget-layout.xml widget-info.xml AndroidManifest.xml app-build.gradle project-build.gradle settings.gradle gradle.properties; do
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
          if [ -f microsoft-client-id.txt ]; then
            ID=$(tr -d ' \r\n\t' < microsoft-client-id.txt)
            if echo "$ID" | grep -Eq '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'; then
              sed -i "s/__MS_CLIENT_ID__/$ID/g" index.html
              echo "Sign in with Microsoft is on"
            else
              echo "::warning::microsoft-client-id.txt doesn't contain a Client ID, so Sign in with Microsoft stays off"
            fi
          fi
          A=android/app/src/main
          mkdir -p "$A/java/com/vexmail/app" "$A/assets" "$A/res/drawable-nodpi" "$A/res/mipmap-anydpi-v26" "$A/res/layout" "$A/res/xml"
          cp settings.gradle gradle.properties android/
          cp project-build.gradle android/build.gradle
          cp app-build.gradle android/app/build.gradle
          cp vex.keystore android/app/vex.keystore
          cp AndroidManifest.xml "$A/AndroidManifest.xml"
          cp *.java "$A/java/com/vexmail/app/"
          cp widget-layout.xml "$A/res/layout/vex_widget.xml"
          cp widget-info.xml "$A/res/xml/vex_widget_info.xml"
          cp index.html "$A/assets/index.html"
          cp icon-maskable-512.png "$A/res/drawable-nodpi/vex_foreground.png"
          cat > "$A/res/mipmap-anydpi-v26/ic_launcher.xml" <<'EOF'
          <?xml version="1.0" encoding="utf-8"?>
          <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
              <background android:drawable="@android:color/black" />
              <foreground android:drawable="@drawable/vex_foreground" />
          </adaptive-icon>
          EOF

      - name: Build the APK
        working-directory: android
        run: gradle assembleRelease --no-daemon --stacktrace

      - name: Keep VEX.apk for the release
        run: cp android/app/build/outputs/apk/release/app-release.apk VEX.apk

      - uses: actions/upload-artifact@v4
        with:
          name: android
          path: VEX.apk

  pc:
    runs-on: windows-latest
    steps:
      - name: Get the VEX files
        uses: actions/checkout@v4

      - name: Set up Node
        uses: actions/setup-node@v4
        with:
          node-version: "20"

      - name: Put the PC app together
        shell: bash
        run: |
          set -e
          for f in index.html icon-512.png pc-main.js pc-preload.js pc-package.json; do
            if [ ! -f "$f" ]; then echo "::error::$f is missing from the top of the repository."; exit 1; fi
          done
          if [ -f microsoft-client-id.txt ]; then
            ID=$(tr -d ' \r\n\t' < microsoft-client-id.txt)
            if echo "$ID" | grep -Eq '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'; then
              sed -i "s/__MS_CLIENT_ID__/$ID/g" index.html
              echo "Sign in with Microsoft is on"
            else
              echo "::warning::microsoft-client-id.txt doesn't contain a Client ID, so Sign in with Microsoft stays off"
            fi
          fi
          mkdir -p pc-app
          cp index.html pc-app/index.html
          cp icon-512.png pc-app/icon.png
          cp pc-main.js pc-app/main.js
          cp pc-preload.js pc-app/preload.js
          cp pc-package.json pc-app/package.json
          echo "{\"build\": ${GITHUB_RUN_NUMBER}}" > pc-app/build-number.json

      - name: Build VEX-Setup.exe
        working-directory: pc-app
        shell: bash
        run: |
          npm install --no-audit --no-fund
          npx electron-builder --win nsis --publish never -c.extraMetadata.version=1.${GITHUB_RUN_NUMBER}.0

      - name: Keep VEX-Setup.exe for the release
        shell: bash
        run: cp pc-app/dist/VEX-Setup.exe VEX-Setup.exe

      - uses: actions/upload-artifact@v4
        with:
          name: pc
          path: VEX-Setup.exe

  release:
    needs: [android, pc]
    if: ${{ always() && (needs.android.result == 'success' || needs.pc.result == 'success') }}
    runs-on: ubuntu-latest
    steps:
      - uses: actions/download-artifact@v4
        with:
          path: out
          merge-multiple: true

      - name: Publish VEX for PC
        if: ${{ needs.pc.result == 'success' }}
        uses: softprops/action-gh-release@v2
        with:
          tag_name: pc-${{ github.run_number }}
          name: VEX for PC ${{ github.run_number }}
          body: "For Windows: download VEX-Setup.exe and open it."
          files: out/VEX-Setup.exe
          make_latest: false

      - name: Publish VEX for Android
        if: ${{ needs.android.result == 'success' }}
        uses: softprops/action-gh-release@v2
        with:
          tag_name: android-${{ github.run_number }}
          name: VEX for Android ${{ github.run_number }}
          body: "For your Android phone: open this page on your phone, tap VEX.apk, then open the file to install VEX."
          files: out/VEX.apk
          make_latest: true
```
