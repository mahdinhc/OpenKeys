#!/usr/bin/env bash
# Idempotent: safe to run locally and in CI.
set -euo pipefail
cd "$(dirname "$0")/.."

RN_VERSION="${RN_VERSION:-0.76.9}"
PKG=com.openkeys.keyboard
PKG_DIR=android/app/src/main/java/com/openkeys/keyboard

if [ ! -d android ]; then
  echo ">> Generating React Native $RN_VERSION scaffold"
  TMP="$(mktemp -d)"
  npx --yes @react-native-community/cli@latest init OpenKeys \
      --version "$RN_VERSION" --package-name "$PKG" \
      --skip-install --skip-git-init --directory "$TMP/app"
  cp -rn "$TMP/app/." .          # never overwrites our own files (App.tsx, .gitignore, ...)
  rm -rf "$TMP"
fi

if [ ! -d node_modules ]; then
  echo ">> npm install"
  npm install --no-audit --no-fund
fi

# vector icons for the settings app
[ -d node_modules/react-native-svg ] || npm install --no-audit --no-fund --save-exact react-native-svg@15.9.0

echo ">> Applying native keyboard overlay"
mkdir -p "$PKG_DIR" android/app/src/main/res android/app/src/main/assets
cp -f native/kotlin/*.kt "$PKG_DIR/"
cp -rf native/res/. android/app/src/main/res/
cp -rf native/assets/. android/app/src/main/assets/
mkdir -p android/app/src/main/assets/plugins
cp -f plugins/*.js android/app/src/main/assets/plugins/   # sample plugins offered in the app
python3 scripts/patch_android.py

# Legacy bridge modules are the most robust choice for the settings module.
sed -i 's/^newArchEnabled=.*/newArchEnabled=false/' android/gradle.properties
sed -i 's/^org.gradle.jvmargs=.*/org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g/' android/gradle.properties
chmod +x android/gradlew
echo ">> Done. Build: cd android && ./gradlew assembleRelease"
