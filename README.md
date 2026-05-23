# Homebox Catalog (iOS)

Rapid-input iPhone app for cataloguing items directly into a self-hosted [Homebox](https://homebox.software/) v0.25.x instance. 

Target: iOS 26 · Liquid Glass UI · sideloaded via AltStore (no App Store).

## Features

- **Direct API Integration**: Connects straight to your Homebox server (v0.25.x). All items, locations, and tags are fetched and updated live.
- **Full CRUD Support**: Create, read, update, and delete items, locations, and tags directly from the app.
- **Photo Attachments**: Capture photos using the camera or select from the photo library, automatically downscaled and uploaded.
- **Sticky Fields**: "Keep location" and "Keep tags" toggles preserve your context across submissions, making rapid cataloguing easy.
- **Bulk Editing**: Select multiple items at once to move them or change their tags.
- **Theming**: Choose from 30 themes ported from the Homebox web app.

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
