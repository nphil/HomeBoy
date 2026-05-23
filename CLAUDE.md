# Homebox Catalog iOS — Project Reference

## What this is
Rapid-input iPhone app for cataloguing items into a self-hosted [Homebox](https://homebox.software/) instance. The app connects directly to the Homebox v1 REST API — every Add posts to the server, the Items tab reflects what's actually there.

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

## Homebox API surface used
All endpoints under `${serverURL}/api/v1/`. Bearer token in `Authorization` header (no `Bearer ` prefix — Homebox accepts the raw token).

| Endpoint | Why |
|---|---|
| `POST /users/login` (form-encoded) | Sign in. Returns `{ token, expiresAt }`. |
| `GET /entity-types` | Discover the "Item" entity-type id (the first one with `isLocation == false`). Cached in UserDefaults. |
| `GET /entities?pageSize=1000` | Fetch everything; the app filters client-side: `isLocation` → location tree, else → items list. Homebox doesn't expose an `entityTypeId` query filter. |
| `POST /entities` (JSON) | Create an item: `{ name, entityTypeId, parentId?, quantity, description? }`. `parentId` is the **location entity's id**, not a string path. |

## File map

| File | Purpose |
|---|---|
| `HomeboxCatalogApp.swift` | `@main`. Injects `HomeboxStore` + `ThemeManager`, transparent nav appearance, TabView (Add / Items / Settings), `BrandMark`. |
| `Theme.swift` | `AppTheme` (4 themes), `ThemeManager`, `ThemeBackground` (blurred orbs on near-black), `ThemeSwatch`, `Color(hex:)`. |
| `Models.swift` | `HomeboxStore` ObservableObject — auth state, server URL, item-type id, in-memory locations cache. Helpers: `locationsAsTree()`, `pathString(forLocationId:)`. |
| `HomeboxClient.swift` | Async/await HTTP client. Codable models: `HBEntity`, `HBEntityType`, `HBParentRef` (recursive), `HBCreateEntityRequest`, `HBLoginResponse`, `HBEntityListResponse`. `HBError` for typed failures. |
| `Keychain.swift` | Minimal `SecItem`-backed wrapper for the bearer token (accessible after first unlock, this-device-only). |
| `AddItemView.swift` | Main form. Name → Quantity pill → location picker row → optional description → submit. POSTs `/v1/entities`. Shows "Connect to Homebox" card when unauthenticated. |
| `LocationPickerSheet.swift` | Sheet presenting the location tree DFS-ordered with depth indentation, indent ticks, search, pull-to-refresh. Tap to pick, "Clear" in toolbar to deselect. |
| `ItemsListView.swift` | Fetches `/v1/entities`, filters to non-locations, newest-first, with search + pull-to-refresh. Glass-card rows with parent breadcrumb. |
| `SettingsView.swift` | Server URL field + sign-in form (server prefix auto-added if scheme missing). Signed-in summary: user, cached location count, refresh, sign out. Theme picker. About. |
| `Components.swift` | `GlassCard`, `QuantityControl` (custom -/+ pill, replaces broken `Stepper.labelsHidden()`). |
| `project.yml` | xcodegen spec — iOS 26 deployment, no signing, asset catalog. |
| `.github/workflows/build.yml` | macOS-15 runner: install xcodegen → generate → archive unsigned → zip IPA → publish to "latest" release. |
| `Assets.xcassets/` | App icon (1024×1024 ocean-blue gradient with box + checkmark). |

## Architecture rules

- **No custom `.glass` / `.glassProminent` button-style definitions** — iOS 26 SDK provides these natively. Custom ones cause "ambiguous use of 'glass'" errors.
- **Background pattern**: apply `.background(ThemeBackground().ignoresSafeArea())` as a modifier on the ScrollView / List / Form — **do not** wrap in a `ZStack { ThemeBackground; ScrollView }`. The ZStack sizes to its largest child; ThemeBackground extends beyond safe area via `.ignoresSafeArea()`, which corrupts the ScrollView's effective width and shifts content off-screen. (This is the bug that wasted the first iteration.)
- **Tokens go in Keychain**, server URL and username in UserDefaults. Never persist passwords.
- **Locations are entities too.** Homebox's model is "everything is an entity, distinguished by `entityType.isLocation`." When creating an item the `parentId` field is the **id** of a location entity — not a string path.
- **`Stepper(...).labelsHidden()` is broken on iOS 26** — it reserves layout space for the hidden label and blows out the row. Use `QuantityControl` instead.
- **Recursive Codable**: `HBParentRef` is a `final class` (not struct) because Codable structs can't be self-referential. Conform via `Hashable` on `id`.

## Common tasks

### Add a new optional field (e.g. serial number)
1. Add it to `HBCreateEntityRequest` in `HomeboxClient.swift`.
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
- `GET /v1/entities` doesn't support `entityTypeId` as a query param — we fetch all and filter client-side.
- For very large inventories (>1000 items), we'd need pagination. Currently we ask for `pageSize=1000` in one shot.
