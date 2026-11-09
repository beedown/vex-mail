# VEX Mail

Every inbox in one place, sorted by what needs you.

## Update VEX or set it up

1. Click **Add file**, then **Upload files**. Drag in all the VEX files (not the folder), then click **Commit changes**.
2. Only when Claude gives you a new build file: open `.github/workflows/main.yml`, click the pencil, replace everything with the box at the bottom of this page, and click **Commit changes** twice.
3. Open **Actions** and wait for a green check.
4. On your Android phone, open this repository's **Releases** page, tap **VEX.apk** under the newest build, and install it over the old one. Your mail stays.

Red ✗? Click it, click **apk**, then the step with the ✗, and send a screenshot to Claude.

## Smart calendar

Open the **Calendar** tab. Tap **Import** to paste your school timetable (English or Dutch, or a copied table) or to add an iCal link from Google Calendar, Smartschool or Outlook, which stays up to date. Under **Plan**, add what you want to do, like gaming 5 times a week or 4 hours of study before an exam, and VEX fits it into your free time. You can also type things like "gaming 2h tonight" in the box at the top.

## VEX for PC

Every build makes two releases: **VEX for Android** (with VEX.apk) and **VEX for PC** (with VEX-Setup.exe). On your PC, open the newest **VEX for PC** release on the **Releases** page, download VEX-Setup.exe and open it. If Windows shows "Windows protected your PC", click **More info**, then **Run anyway**. VEX installs, opens, and adds itself to your Start menu and desktop. Connect your email in **Settings**; you can use the same app password as on your phone. The PC version updates itself too.

## Several email addresses

In VEX, open **Settings** and tap **Add email** to connect another address. Tap **Edit** next to any address to give it a name, like Work or School, and a color. Use the inbox button at the top to see one address at a time, and pick which address to send from when you write.

## Automatic updates

Once this version is installed, VEX checks this repository for new builds by itself. When it finds one, it downloads and installs it. The first time, Android asks you to allow VEX to install apps. After that, Android may ask you to tap **Update** for each new version. You can turn this off in VEX under **Settings**, then **App updates**.

## Connect your real email

In VEX, open **Settings** and tap **Connect**. Gmail, Yahoo and iCloud need an **app password** instead of your normal password:

- **Gmail:** turn on 2-Step Verification in your Google account, then create an app password at myaccount.google.com/apppasswords.
- **Yahoo:** create one in your Yahoo account's security settings.
- **iCloud:** create an app-specific password in your Apple account.

Hotmail and Outlook can't sign in with a password anymore (Microsoft's rule). Forward your Hotmail to your Gmail to read it in VEX; the Hotmail tab in Connect shows how. Your password is saved only on your device.

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
