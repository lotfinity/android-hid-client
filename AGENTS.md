# Agent Notes

- Scope this repository to the Android HID client app only.
- Keep C2Q-specific behavior aligned with the `c2q-nethunter-companion` Magisk module. The app should call the module command for USB HID activation and restore instead of rewriting ConfigFS directly.
- Prefix local shell commands with `rtk` when working in this environment.
- Use `./gradlew --no-daemon assembleDebug` as the default validation build for app changes.
- Do not commit generated APKs from `dist/` unless a release artifact is explicitly requested.
- Keep ROM work, module work, and Android app work separated in commits and changelog entries.
