# OmniControl — Cross-Platform E2EE Remote Control

Gói mã nguồn và cấu hình đóng gói tự động cho Windows (`.msi`), macOS (`.dmg`), Android (`.apk` / `.aab`) và Signaling Hub (Go + Redis).

## Cấu trúc thư mục
- `.github/workflows/release-builds.yml`: Pipeline GitHub Actions tự động đóng gói cho Windows, macOS và Android khi đẩy tag `v*`.
- `desktop-agent/`: Cấu hình Rust Agent, Windows WiX Service (`wix/main.wxs`), macOS Bundle (`macos/Info.plist`, `macos/Entitlements.plist`), DXGI GPU Duplication (`src/dxgi_capture.rs`) và xử lý Desktop/UAC (`src/windows_service.rs`).
- `android-client/`: Cấu hình Gradle Release, ProGuard rules, ghép nối QR Ed25519 ngoại tuyến, Adaptive Bitrate và LAN Discovery.
- `signaling-server/`: Định tuyến Signaling đa node qua Redis Pub/Sub và cấp phát TURN credentials ngắn hạn.
- `scripts/package-macos.sh`: Script đóng gói macOS Universal `.dmg`.
