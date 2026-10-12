#!/usr/bin/env python3
"""Apply a native MAX adapter to the actual NimarkoGram Android source tree.

Never downloads/runs the Komet Flutter application. A pinned Kolibri MIT/Apache
Kotlin/JNI core is copied in by CI. Replacements deliberately fail closed when
NimarkoGram upstream changes.
"""
from pathlib import Path
import argparse
import shutil


def replace(root: Path, path: str, before: str, after: str, occurrences: int = 1) -> None:
    target = root / path
    old = target.read_text(encoding="utf8")
    found = old.count(before)
    if found != occurrences:
        raise RuntimeError(f"{path}: expected {occurrences} anchors, found {found}: {before[:130]}")
    target.write_text(old.replace(before, after), encoding="utf8")
    print("Patched", path)


def apply(nimarko: Path, kolibri: Path, overlay: Path) -> None:
    source = nimarko / "TMessagesProj/src/main/java"
    dest = source / "app/nimarkogram/messenger/max"
    dest.mkdir(parents=True, exist_ok=True)
    for file in (overlay / "src/app/nimarkogram/messenger/max").glob("*.kt"):
        shutil.copy2(file, dest / file.name)
    print("Nimarko MAX Kotlin transport and native Android UI:", *[p.name for p in dest.glob("*.kt")])

    kolibri_src = kolibri / "kolibri-kotlin/library/src/main/kotlin/ru/kolibri"
    kolibri_dst = source / "ru/kolibri"
    kolibri_dst.mkdir(parents=True, exist_ok=True)
    for kotlin in kolibri_src.glob("*.kt"):
        shutil.copy2(kotlin, kolibri_dst / kotlin.name)
    print("Imported Kolibri Kotlin/JNI binding from MIT/Apache upstream")

    replace(nimarko, "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java",
        "    protected void onCreate(Bundle savedInstanceState) {\n        isActive = true;",
        """    protected void onCreate(Bundle savedInstanceState) {
        // The MAX build starts the native NimarkoGram-hosted MAX frontend.
        // Do not initialize an unauthenticated Telegram session for MAX users.
        if (getIntent() != null && Intent.ACTION_MAIN.equals(getIntent().getAction())) {
            super.onCreate(savedInstanceState);
            startActivity(new Intent(this,
                    app.nimarkogram.messenger.max.MaxHomeActivity.class));
            finish();
            return;
        }
        isActive = true;""")
    replace(nimarko, "TMessagesProj_AppStandalone/build.gradle",
        "defaultConfig.applicationId = 'app.nimarkogram.messenger'",
        "defaultConfig.applicationId = 'app.nimarkomax.messenger'")
    replace(nimarko, "TMessagesProj/config/release/AndroidManifest_standalone.xml",
        'android:label="@string/AppName"',
        'android:label="NimarkoMAX"')
    replace(nimarko, "TMessagesProj/config/release/AndroidManifest_standalone.xml",
        "        <meta-data android:name=\"com.google.android.maps.v2.API_KEY\"",
        """        <!-- MAX account and chats use Kolibri inside the original Android app. -->
        <activity
            android:name="app.nimarkogram.messenger.max.MaxHomeActivity"
            android:exported="false"
            android:windowSoftInputMode="adjustResize"
            android:theme="@style/Theme.TMessages" />
        <meta-data android:name="com.google.android.maps.v2.API_KEY\"""")
    gradle = nimarko / "TMessagesProj/build.gradle"
    old = gradle.read_text(encoding="utf8")
    anchor = "    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'"
    if old.count(anchor) != 1:
        raise RuntimeError("Could not find Kotlin coroutine dependency in NimarkoGradle")
    # Kolibri Kotlin's suspend façade requires coroutines (already present).
    # JNI .so is copied to the existing app jniLibs directory after Rust build.
    print("Gradle coroutine dependency already present; no Komet libraries needed")
    assert (nimarko / "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java").is_file()
    assert (nimarko / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java").is_file()
    assert (nimarko / "TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins").is_dir()
    print("Verified actual NimarkoGram Telegram UI / advanced plugin sources remain intact")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("nimarkogram", type=Path)
    ap.add_argument("kolibri", type=Path)
    ap.add_argument("overlay", type=Path)
    args = ap.parse_args()
    apply(args.nimarkogram, args.kolibri, args.overlay)
