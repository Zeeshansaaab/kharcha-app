# Building the Kharcha APK

This project is a native Kotlin Android app. You can build and install the APK locally or use GitHub Actions to build it automatically.

## Option 1: Build the APK in Android Studio

### 1. Open the project

Open the project folder in **Android Studio**.

Wait for Gradle sync to finish. If Android Studio asks to install a required SDK or JDK version, install the recommended version.

### 2. Build the debug APK

From the Android Studio menu:

**Build > Build Bundle(s) / APK(s) > Build APK(s)**

Once the build finishes, Android Studio will show a notification with a link to the generated APK.

The APK will usually be located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 3. Install it on your Android phone

Transfer `app-debug.apk` to your phone and open it.

Android may ask you to allow installation from the app you used to open the APK, such as Files or Chrome. Enable **Allow from this source**, then install the app.

Because Kharcha requests `READ_SMS`, Google Play Protect may show a warning for a sideloaded build. Review the warning and only install builds you created or trust.

---

## Option 2: Build from the command line

Make sure you have the Android SDK and JDK required by the project installed.

From the project root:

```bash
./gradlew assembleDebug
```

On Windows:

```bash
gradlew.bat assembleDebug
```

The generated APK will be available at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

You can also install it directly to a connected Android device:

```bash
./gradlew installDebug
```

Make sure **Developer Options** and **USB Debugging** are enabled on the device.

---

## Option 3: Build the APK with GitHub Actions

You can use GitHub Actions to build the APK without installing Android Studio locally.

### 1. Push the project to GitHub

From the project folder:

```bash
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin git@github.com:YOUR_USERNAME/kharcha.git
git push -u origin main
```

Replace `YOUR_USERNAME` with your GitHub username.

### 2. Add the GitHub Actions workflow

Make sure the project contains this file:

```text
.github/workflows/build-apk.yml
```

Push the workflow to GitHub.

### 3. Download the APK

Go to your GitHub repository and open:

**Actions > Build APK > Latest workflow run**

After the build completes:

**Artifacts > kharcha-debug**

Download and unzip the artifact. Inside, you will find the debug APK.

Transfer it to your Android phone and install it.

---

## Building a Release APK

For testing and personal use, the debug APK is enough:

```bash
./gradlew assembleDebug
```

For distribution, build a signed release APK:

```bash
./gradlew assembleRelease
```

A release build must be signed with a keystore. The final APK is typically located at:

```text
app/build/outputs/apk/release/app-release.apk
```

If you plan to publish the app on the Google Play Store, you will usually generate a signed Android App Bundle instead:

```bash
./gradlew bundleRelease
```

The generated `.aab` file will be inside:

```text
app/build/outputs/bundle/release/
```

## Quick Command

If you just want the fastest way to generate an APK:

```bash
./gradlew assembleDebug
```

Then get the APK from:

```text
app/build/outputs/apk/debug/app-debug.apk
```
