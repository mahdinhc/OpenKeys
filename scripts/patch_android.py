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
