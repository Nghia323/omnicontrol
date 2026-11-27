#!/usr/bin/env bash
set -euo pipefail

APP_NAME="OmniControlAgent"
BUNDLE_DIR="dist/${APP_NAME}.app"
DMG_PATH="dist/${APP_NAME}-macos-universal.dmg"

rustup target add aarch64-apple-darwin x86_64-apple-darwin
cargo build --release --target aarch64-apple-darwin
cargo build --release --target x86_64-apple-darwin

mkdir -p "${BUNDLE_DIR}/Contents/MacOS" "${BUNDLE_DIR}/Contents/Resources" dist
lipo -create \
  target/aarch64-apple-darwin/release/omnicontrol-desktop-agent \
  target/x86_64-apple-darwin/release/omnicontrol-desktop-agent \
  -output "${BUNDLE_DIR}/Contents/MacOS/omnicontrol-desktop-agent"

cp macos/Info.plist "${BUNDLE_DIR}/Contents/Info.plist"
chmod +x "${BUNDLE_DIR}/Contents/MacOS/omnicontrol-desktop-agent"

codesign --force --deep --options runtime \
  --entitlements macos/Entitlements.plist \
  --sign "-" "${BUNDLE_DIR}"

STAGE_DIR="dist/dmg_stage"
rm -rf "${STAGE_DIR}" "${DMG_PATH}"
mkdir -p "${STAGE_DIR}"
cp -R "${BUNDLE_DIR}" "${STAGE_DIR}/"
ln -s /Applications "${STAGE_DIR}/Applications"

hdiutil create -volname "${APP_NAME}" \
  -srcfolder "${STAGE_DIR}" \
  -ov -format UDZO "${DMG_PATH}"

rm -rf "${STAGE_DIR}"
echo "Đã tạo thành công: ${DMG_PATH}"
