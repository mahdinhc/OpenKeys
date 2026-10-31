import pathlib, sys

main = pathlib.Path("android/app/src/main")

# ---- AndroidManifest.xml -------------------------------------------------
mf = main / "AndroidManifest.xml"
s = mf.read_text(encoding="utf-8")

if "OpenKeysIME" not in s:
    s = s.replace("<application",
        '<uses-permission android:name="android.permission.VIBRATE"/>\n    <application', 1)
    comp = '''
        <service
            android:name="com.openkeys.keyboard.OpenKeysIME"
            android:label="@string/ime_name"
            android:exported="true"
            android:permission="android.permission.BIND_INPUT_METHOD">
            <intent-filter>
                <action android:name="android.view.InputMethod"/>
            </intent-filter>
            <meta-data android:name="android.view.im" android:resource="@xml/method"/>
        </service>
        <activity
            android:name="com.openkeys.keyboard.VoiceActivity"
            android:theme="@android:style/Theme.Translucent.NoTitleBar"
            android:excludeFromRecents="true"
            android:taskAffinity=""
            android:exported="false"/>
'''
    s = s.replace("</application>", comp + "    </application>", 1)

if "android.support.FILE_PROVIDER_PATHS" not in s:
    prov = '''
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.clips"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/clip_paths"/>
        </provider>
'''
    s = s.replace("</application>", prov + "    </application>", 1)

mf.write_text(s, encoding="utf-8")

# ---- MainApplication.kt --------------------------------------------------
files = list(main.glob("java/**/MainApplication.kt"))
if not files:
    sys.exit("MainApplication.kt not found")
p = files[0]
t = p.read_text(encoding="utf-8")
if "KeyboardPackage" not in t:
    marker = "// add(MyReactNativePackage())"
    if marker not in t:
        sys.exit("Could not patch MainApplication.kt (template changed?) - add KeyboardPackage() manually")
    p.write_text(t.replace(marker, "add(com.openkeys.keyboard.KeyboardPackage())"), encoding="utf-8")
print("patched manifest + MainApplication")

# ---- Plugin system extras (idempotent) ------------------------------------
import re
s = mf.read_text(encoding="utf-8")
changed = False
if "android.permission.INTERNET" not in s:
    s = s.replace("<application", '<uses-permission android:name="android.permission.INTERNET"/>\n    <application', 1)
    changed = True
# plugins may fetch plain http:// URLs: force cleartext on (the RN template may already set it via a placeholder)
if "usesCleartextTraffic" in s:
    s2 = re.sub(r'android:usesCleartextTraffic="[^"]*"', 'android:usesCleartextTraffic="true"', s)
    changed = changed or s2 != s
    s = s2
else:
    s = s.replace("<application", '<application android:usesCleartextTraffic="true"', 1)
    changed = True
if changed:
    mf.write_text(s, encoding="utf-8")

gr = pathlib.Path("android/app/build.gradle")
g = gr.read_text(encoding="utf-8")
if "quickjs-android" not in g:
    g2 = re.sub(r"dependencies\s*\{", 'dependencies {\n    implementation("app.cash.quickjs:quickjs-android:0.9.2")', g, count=1)
    if g2 == g:
        sys.exit("Could not add the QuickJS dependency to android/app/build.gradle - add it manually")
    gr.write_text(g2, encoding="utf-8")
print("patched plugin runtime dependency + permissions")
