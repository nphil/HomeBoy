# Homebox Catalog (iOS)

Rapid-input iPhone app for cataloguing items for [Homebox](https://hay-kot.github.io/homebox/). Items go into a local queue, then you export a Homebox-format CSV (and, later, push directly to a self-hosted Homebox instance).

Target: iOS 26 · Liquid Glass UI · sideloaded via AltStore (no App Store).

## Build

- Every push to `main` triggers `.github/workflows/build.yml` on a macOS runner.
- xcodegen reads `project.yml` → generates `HomeboxCatalog.xcodeproj` (never committed).
- Unsigned IPA is uploaded to the GitHub Releases page as the `latest` pre-release.
- Sign it with AltStore on first install.

## Sideload

1. Wait for CI to finish (~5–10 min after pushing).
2. Download `HomeboxCatalog.ipa` from the `latest` release.
3. AirDrop or share-sheet it to AltStore on your iPhone.
4. AltStore signs it with your Apple ID and installs it.

## Design notes

- **Local queue first.** Items live on the device until you choose to export. This means you can catalogue in basements, garages, and storage units without wifi.
- **Sticky locations.** Long-press the lock chip on any location field to make that value persist after you add an item. Catalogue a whole shelf without retyping the location.
- **Keyboard-first.** Return key on the last field adds the item. Recent values appear above the keyboard for one-tap fill.
- **Native Liquid Glass.** Uses iOS 26 `.glass`, `.glassProminent`, `.ultraThinMaterial`. No custom button styles — the system ones are correct.

## Future

- Direct push to a self-hosted Homebox instance (server URL + API token in settings).
- Photo attachments (requires API path).
