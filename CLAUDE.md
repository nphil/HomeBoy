# Homebox Catalog iOS — Project Reference

## What this is
Rapid-input iPhone app for cataloguing items for [Homebox](https://hay-kot.github.io/homebox/). Items go to a local on-device queue; the user exports a Homebox-format CSV when ready. A "Push directly to my self-hosted Homebox" option is planned for a later build.

Target: iOS 26, Liquid Glass UI, sideloaded via AltStore (no App Store).

## Repo
`https://github.com/nphil/homebox-catalog-ios` — every push to `main` triggers CI and produces an unsigned IPA.
IPA download: GitHub Releases → "latest" pre-release (overwritten on each main push).

## Build / CI
- **Never run Xcode locally** — use GitHub Actions (`.github/workflows/build.yml`)
- xcodegen reads `project.yml` → generates `HomeboxCatalog.xcodeproj` in CI (never committed)
- Unsigned IPA: `CODE_SIGNING_ALLOWED=NO` — AltStore signs at sideload time
- Runner: `macos-15`, auto-selects latest Xcode (currently Xcode 26 with iOS 26 SDK)
- **Always commit + push after any meaningful change** — CI does the rest

## File map

| File | Purpose |
|---|---|
| `HomeboxCatalogApp.swift` | `@main`, transparent nav appearance, TabView (Add / Items / Settings), `BrandMark` |
| `Theme.swift` | `AppTheme` enum (4 themes), `ThemeManager` ObservableObject, `ThemeBackground` (blurred orbs), `ThemeSwatch`, `Color(hex:)` |
| `Models.swift` | `CatalogItem` struct (Codable), `CatalogStore` ObservableObject — items array, locks array, JSON-on-disk persistence, `recentLocations(level:)` |
| `AddItemView.swift` | Main input form. FocusState chain, submit on last field, sticky location locks, keyboard-toolbar recent chips, haptic success pill |
| `ItemsListView.swift` | List of queued items, tap-to-edit sheet, swipe-to-delete, count badge |
| `SettingsView.swift` | Export CSV (share sheet), Clear queue, Theme picker, Homebox-API stub section, About |
| `Components.swift` | `GlassCard`, `RecentChips`, `LockToggle` |
| `CSVExporter.swift` | Renders Homebox HB.* CSV, RFC 4180 escaping, writes to tmp dir for share sheet |
| `project.yml` | xcodegen spec — deployment target iOS 26, no signing, asset catalog |
| `.github/workflows/build.yml` | macOS-15 runner: install xcodegen → generate project → archive unsigned → zip into IPA → upload to "latest" release |
| `Assets.xcassets/` | App icon (1024×1024 ocean-blue gradient box with checkmark) |

## Architecture rules

- **No custom `.glass` / `.glassProminent` button style definitions** — iOS 26 SDK provides these natively. Adding custom ones causes "ambiguous use of 'glass'" compile error.
- **Atmospheric background**: `ThemeBackground` (orbs on near-black) is applied inside each tab's `NavigationStack` via `ZStack`. Forms/Lists use `.scrollContentBackground(.hidden)` so the background shows through.
- **Theming**: `ThemeManager` is a `@StateObject` in `HomeboxCatalogApp`, injected as `@EnvironmentObject`. `.tint()` is set at root level.
- **Local persistence**: items live in `Documents/catalog-items.json` (plain Codable). Lock state in UserDefaults (`homebox.locks`). Theme in UserDefaults (`homebox.theme`).
- **Focus chain in AddItemView**: `enum Field` + `@FocusState`. Each TextField has `submitLabel(.next)` and `onSubmit { focused = .next }`. Last field uses `.done` and triggers submit.
- **Sticky locations**: `LockToggle` flips `store.locks[i]` (Bool array binding). After successful Add, only unlocked location fields are cleared.
- **Recent values above keyboard**: `ToolbarItemGroup(placement: .keyboard)` renders `RecentChips` for whichever location field is focused. Tap chip to fill.
- **Share sheet**: CSVExporter writes to `FileManager.default.temporaryDirectory`, returns URL, wrapped in `ShareItem(Identifiable)` for sheet presentation. Do NOT add a retroactive `Identifiable` conformance to URL — it conflicts with Foundation's own conformance in newer SDKs.

## Common tasks

### Add a new optional field (e.g. serial number)
1. Add property to `CatalogItem` in `Models.swift`.
2. Add state var + UI to the "More details" disclosure in `AddItemView.swift`.
3. Mirror in `EditItemSheet` inside `ItemsListView.swift`.
4. Add a `HB.serial_number` column to `CSVExporter.headers` and the row generator.

### Add the Homebox direct-push path
1. Add `homebox.serverURL` and `homebox.apiToken` to UserDefaults (via a new section in `SettingsView`).
2. Create `HomeboxClient.swift` with `POST /api/v1/items` (and required location lookup/create endpoints).
3. Add a "Push to Homebox" button next to "Export CSV" in Settings — iterate items, post each, mark synced or remove on success.
4. Locations need an ID lookup: Homebox treats locations as a tree, not strings. Either resolve by name or build a small picker.

### Change the app icon
Replace `Assets.xcassets/AppIcon.appiconset/AppIcon.png` with a new 1024×1024 PNG (kept in `gen_icon.py` workflow notes — current icon is generated, not designed).

### Push and trigger a build
```bash
cd "/Users/nitin/AI Playground/homebox-catalog-ios"
git add <files>
git commit -m "..."
git push origin main
```

## Known gotchas
- `xcodegen` source path is `.` — any new `.swift` file in root is auto-included.
- `SKIP_INSTALL=NO` and `INSTALL_PATH="/Applications"` required in archive command or `.app` won't appear in xcarchive.
- Pushing `.github/workflows/*` requires the `workflow` scope on the `gh` CLI token. If push is rejected, run `gh auth refresh -h github.com -s workflow`.
- `submitLabel(.done)` on the last field doesn't auto-submit — you still need `.onSubmit { submit() }`.
- `LinearGradient` on TabView itself doesn't render behind NavigationStack — apply the background **inside** each tab.
