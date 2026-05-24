# HomeBoy (Homebox Catalog iOS) — Project Reference

## What this is
Rapid-input iPhone app for cataloguing items into a self-hosted [Homebox](https://homebox.software/) instance (v0.25.x). Connects directly to the Homebox v1 REST API — every Add posts to the server, every list view reflects what's actually there. Display name is "HomeBoy"; repo + bundle id keep the original "homebox-catalog" naming so AltStore reinstalls stay in-place.

Target: iOS 26, Liquid Glass UI, sideloaded via AltStore (no App Store).

## Repo
`https://github.com/nphil/homebox-catalog-ios` — every push to `main` triggers CI and produces an unsigned IPA on the "latest" pre-release.

## Build / CI
- **Never run Xcode locally** — use GitHub Actions (`.github/workflows/build.yml`).
- xcodegen reads `project.yml` → generates `HomeboxCatalog.xcodeproj` in CI (never committed).
- Unsigned IPA, `CODE_SIGNING_ALLOWED=NO` — AltStore signs at sideload.
- Runner: `macos-15`, latest Xcode (currently 26 with iOS 26 SDK).
- **Always commit + push after any meaningful change** — CI does the rest.
- Pushing `.github/workflows/*` needs `workflow` scope on the `gh` token. Fix with `gh auth refresh -h github.com -s workflow`.

## Homebox API surface used (v0.25.x)
All endpoints under `${serverURL}/api/v1/`. Bearer token in `Authorization` header (raw token — no `Bearer ` prefix).

| Endpoint | Why |
|---|---|
| `POST /users/login` (form-encoded) | Sign in. Returns `{ token, expiresAt }`. |
| `GET /locations` | Flat list of LocationOutCount — no parent info. We don't use this; we use `/tree` instead. |
| `GET /locations/tree?withItems=false` | Nested TreeItem array. Flattened DFS into `FlatLocation[]` (with ancestor chain) for the picker and Locations tab. |
| `POST /locations` (JSON) | `{ name, parentId?, description }`. Used by CreateLocationSheet. |
| `GET /items?page=1&pageSize=1000` | Paginated ItemSummary list. Items tab uses this. |
| `POST /items` (JSON) | Create item: `{ name, quantity, description, locationId, parentId?, tagIds }`. Returns ItemOut; we decode just `id`. |
| `POST /items/{id}/attachments` (multipart/form-data) | Upload a photo. Fields: `file`, `name` (required), `primary` (bool). `type` is auto-detected from filename extension. |

**Important**: the `/v1/entities` endpoints described on homebox.software are unreleased main-branch APIs and do **not** exist in v0.25.x. Always cross-check against the routes.go file at the target release tag.

## File map

| File | Purpose |
|---|---|
| `HomeboxCatalogApp.swift` | `@main`. Injects `HomeboxStore` + `ThemeManager`, transparent nav appearance. Conditionally routes to `OnboardingView` (if unauthenticated) or the 5-tab `TabView` (if authenticated). `BrandMark` shows "HomeBoy". |
| `OnboardingView.swift` | Full-screen welcome and login view. Server URL field + sign-in form. Takes over root until authenticated. |
| `Theme.swift` | `AppTheme` (30 themes ported from Homebox's `assets/css/main.css`), `ThemeManager`, `Color(h:s:l:)` HSL helper, `ThemeSwatch` previewing actual bg + primary + accent. No orb background — each view uses `theme.current.backgroundColor` as a solid `.background(...)`. |
| `Models.swift` | `HomeboxStore` ObservableObject — auth state, server URL, in-memory `locationsFlat: [FlatLocation]` (DFS-flattened tree with ancestor chain). Helpers: `pathString(forLocationId:)`. |
| `HomeboxClient.swift` | Async/await HTTP client for v0.25.x. Codable models: `HBItem`, `HBLocation`, `HBTreeItem` (final class, recursive), `HBItemCreate`, `HBLocationCreate`, plus `HBLoginResponse`/`HBItemListResponse`. `uploadAttachment` builds multipart/form-data by hand. |
| `Keychain.swift` | Minimal `SecItem` wrapper for the bearer token (`AccessibleAfterFirstUnlockThisDeviceOnly`). |
| `PhotoSource.swift` | `CameraSheet` (UIViewControllerRepresentable over UIImagePickerController) + `downscale(_:maxDimension:)` helper that keeps JPEGs under a few hundred KB. |
| `AddItemView.swift` | Main form. Name → Qty pill → location picker → photo card (Camera/Library buttons or attached-thumbnail with Remove/Replace) → optional description → submit. On submit: POST `/v1/items`, then if a photo is set POST `/v1/items/{id}/attachments` (primary=true). Uses `PhotosPicker` (iOS 16+) for library + `CameraSheet` for capture. |
| `LocationPickerSheet.swift` | Sheet over `store.locationsFlat` with depth indentation, indent ticks, search, pull-to-refresh. Tap to pick; "Clear" in toolbar to deselect. Reused by AddItemView and CreateLocationSheet. |
| `LocationsTabView.swift` | Dedicated Locations tab — indented tree, search, plus-button toolbar opens `CreateLocationSheet` (name + optional parent + description; refreshes the store on success). |
| `ItemsListView.swift` | `GET /v1/items`, sorted newest-first, search (hybrid semantic word+sentence embedding) + pull-to-refresh. Breadcrumb prefers the full path from the cached tree, falls back to the item's immediate location name. |
| `SettingsView.swift` | Signed-in summary: user, server, cached-location count, refresh, sign-out. Theme picker is a 5-column `LazyVGrid` of swatches. About. |
| `Components.swift` | `GlassCard`, `QuantityControl` (custom -/+ pill — replaces broken `Stepper.labelsHidden()`). |
| `project.yml` | xcodegen spec — iOS 26 deployment, no signing, asset catalog. Info.plist props: `NSAllowsArbitraryLoads`, `NSCameraUsageDescription`, `NSPhotoLibraryUsageDescription`. `CFBundleDisplayName: HomeBoy`. |
| `.github/workflows/build.yml` | macOS-15 runner: install xcodegen → generate → archive unsigned → zip IPA → publish to "latest" release. |
| `Assets.xcassets/` | App icon (1024×1024 isometric cardboard box with green inventory label on a warm peach gradient — homage to Homebox's PWA icon). |

## Architecture rules

- **No custom `.glass` / `.glassProminent` button-style definitions** — iOS 26 SDK provides these natively. Custom ones cause "ambiguous use of 'glass'" errors.
- **Background pattern**: apply `.background(theme.current.backgroundColor.ignoresSafeArea())` as a modifier on the ScrollView / List / Form — **do not** wrap in a `ZStack { background; ScrollView }`. The ZStack sizes to its largest child; a background with `.ignoresSafeArea()` extends beyond the safe area, which corrupts the ScrollView's effective width and shifts content off-screen.
- **Tokens go in Keychain**, server URL and username in UserDefaults. Never persist passwords.
- **`POST /v1/items` requires `locationId`** — Add button is disabled until a location is picked. Items can technically be nested under other items via `parentId`, but we never set it.
- **Attachments are multipart/form-data**, not JSON. `HomeboxClient.uploadAttachment` constructs the body by hand; the request runs through the same `request()` helper so it picks up the auth token automatically. Type (photo vs attachment) is inferred from filename extension server-side — that's why uploads use `photo-<epoch>.jpg`.
- **`Stepper(...).labelsHidden()` is broken on iOS 26** — it reserves layout space for the hidden label and blows out the row. Use `QuantityControl` instead.
- **Recursive Codable**: `HBTreeItem` is a `final class` (not struct) because Codable structs can't be self-referential. Conform via `Hashable` on `id`.

## Common tasks

### Add a new optional field (e.g. serial number)
1. Add it to `HBItemCreate` in `HomeboxClient.swift`.
2. Add a `@State` var + UI in `AddItemView.swift` (probably in a "More" disclosure).
3. Include it in the `payload` constructed in `submit()`.

### Wire a new endpoint
Add a method on `HomeboxClient`. Reuse the private `request(path:method:body:contentType:query:)` helper — it sets the auth header and handles 401/transport errors uniformly.

### Force re-login on token expiry
Currently the app surfaces a "Not signed in" error on 401 and the user re-logs in via Settings. To auto-refresh, wire `POST /v1/users/refresh` and call it on `HBError.unauthorized` in a retry loop inside `HomeboxClient.request`.

### Change the app icon
Replace `Assets.xcassets/AppIcon.appiconset/AppIcon.png` with a new 1024×1024 PNG.

### Push and trigger a build
```bash
cd "/Users/nitin/AI Playground/homebox-catalog-ios"
git add <files>
git commit -m "..."
git push origin main
```

## Known gotchas
- xcodegen sources path is `.` — any new `.swift` file in root is auto-included.
- `SKIP_INSTALL=NO` and `INSTALL_PATH="/Applications"` required in archive command or `.app` won't appear in xcarchive.
- Homebox's `Authorization` header takes the **raw token string** (no `Bearer ` prefix).
- The "API docs" at homebox.software show the **unreleased** `/v1/entities` unified-entity API. Released versions (v0.25.x) still use separate `/v1/items` + `/v1/locations`. Always cross-check `backend/app/api/routes.go` at the target release tag.
- For very large inventories (>1000 items), we'd need pagination. Currently we ask for `pageSize=1000` in one shot.
- **Semantic Search**: `ItemsListView` uses a hybrid `min(sentenceEmbedding, wordEmbedding)` with a 1.15 threshold because `sentenceEmbedding` alone breaks on single-word synonyms (like seat/couch). Do not revert to a strict sentence embedding check.
