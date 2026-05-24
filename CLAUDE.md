# HomeBoy (Homebox Catalog iOS) — Project Reference

## What this is
Rapid-input iPhone app for cataloguing items into a self-hosted [Homebox](https://homebox.software/) v0.25.x instance. Connects directly to the Homebox v1 REST API — every Add posts to the server, every list view reflects what's actually there. Display name is "HomeBoy"; repo + bundle id keep the original `homebox-catalog` naming so AltStore reinstalls stay in-place.

Target: iOS 26, Liquid Glass UI, sideloaded via AltStore (no App Store).

## Repo
`https://github.com/nphil/homebox-catalog-ios` — every push to `main` triggers CI and produces an unsigned IPA on the "latest" pre-release.

## Build / CI
- **Never run Xcode locally** — use GitHub Actions (`.github/workflows/build.yml`).
- xcodegen reads `project.yml` → generates `HomeboxCatalog.xcodeproj` in CI (never committed).
- Unsigned IPA, `CODE_SIGNING_ALLOWED=NO` — AltStore signs at sideload.
- Runner: `macos-15`, latest Xcode (currently 26 with iOS 26 SDK).
- **Always commit + push after any meaningful change** — CI does the rest.
- **Versioning & Releases**: Versioning starts at 1.0. Small bug fixes increment the patch version (e.g. 1.0.1), while large feature additions or rebases use larger version increments (e.g. 1.1, 2.0). To create a production release that anyone can download, push a git tag starting with `v` (e.g., `v1.0`).
- Pushing `.github/workflows/*` needs `workflow` scope on the `gh` token. Fix with `gh auth refresh -h github.com -s workflow`.

## Tab structure — 3 tabs only
**Items / Locations / Tags**

- **Settings** is NOT a tab. It's a sheet presented via `NotificationCenter.default.post(name: .showSettings, object: nil)`, which `ContentView` listens for.
- **Add Item** is NOT a tab. It's a FAB (floating action button, bottom-right circle) on the Items tab that presents `AddItemView` as a sheet.
- **Unauthenticated state**: `ContentView` shows `OnboardingView` when `!store.isAuthenticated` (covers the whole screen instead of the tab view).

## Navigation — SiteMenuPopover
Every tab has a **top-left toolbar `ToolbarItemGroup`** containing the "HomeBoy" pill (shippingbox + "HomeBoy" + chevron.down). Tapping the pill toggles `showSiteMenu: Bool` in `ContentView`.

`SiteMenuPopover` is a `ZStack` overlay in `ContentView` at `zIndex(100)`, above all tabs:
- **Animation**: `popoverSpring = .spring(duration: 0.25, bounce: 0.22)`. Transition is `.scale(scale: 0.5, anchor: .topLeading).combined(with: .opacity)`.
- Shows all groups from `store.groups` as individual cards. Active group has an accent-coloured border (2.5 pt) + filled checkmark. **All cards show live counts** (location count + item count) — active uses live store values, inactive uses `store.cachedGroupStats`.
- Opening the popover triggers `store.refreshAllGroupStats()` to keep inactive card counts fresh.
- Tapping an inactive group calls `store.setActiveGroup()`, refreshes locations + item count, dismisses the popover, and fires a `.showToast` notification.
- **Settings button** fires `NotificationCenter.default.post(name: .showSettings)`.
- Dimmed background tap dismisses it.

The `showSiteMenu` binding flows to child views via a **custom `EnvironmentKey`** (`ShowSiteMenuKey` in `HomeboxCatalogApp.swift`):
```swift
@Environment(\.showSiteMenu) var showSiteMenu
// Toggle:
withAnimation(.spring(duration: 0.25, bounce: 0.22)) {
    showSiteMenu.wrappedValue.toggle()
}
```

## Toast notifications
Any part of the app can show a bottom toast pill by posting:
```swift
NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Your message"])
```
`ContentView` renders the toast as a `Capsule` overlay at `zIndex(200)` with a 2.5 s auto-dismiss. Both `.showSettings` and `.showToast` notification names are declared in `SiteMenuPopover.swift`.

## Global search
`globalSearchQuery: String` is a `@Binding` passed from `ContentView` down to all three tab views. Each tab also has `@State private var isSearchActive = false` wired to `.searchable(text: $globalSearchQuery, isPresented: $isSearchActive, prompt: "…")` on its `NavigationStack` via the `ConditionalSearchable` modifier. The magnifying glass icon in the toolbar sets `isSearchActive = true` to programmatically present the native search bar with keyboard.

## Homebox API surface used (v0.25.x)
All endpoints under `${serverURL}/api/v1/`. Bearer token in `Authorization` header — **raw token, no `"Bearer "` prefix**.

**Multi-tenant (Collections/Groups)**: Homebox uses the `X-Tenant: <groupUUID>` request header to scope all responses to a specific group. The same auth token works for every group. `HomeboxClient.tenantId` is sent as this header automatically. Switching groups = change `activeGroupId` → `store.client` picks it up → all subsequent API calls are scoped to the new group. There is **no re-login** needed.

| Endpoint | Why |
|---|---|
| `POST /users/login` (form-encoded) | Sign in. Returns `{ token, expiresAt }`. |
| `GET /locations` | Flat list with itemCount — used to build count map for FlatLocation. |
| `GET /locations/tree?withItems=false` | Nested TreeItem. DFS-flattened into `FlatLocation[]` with ancestor chain. |
| `POST /locations` (JSON) | `{ name, parentId?, description }`. Used by CreateLocationSheet. |
| `GET /items?page=1&pageSize=1000` | Paginated ItemSummary list. Items tab. |
| `GET /items?page=1&pageSize=1` | Used by `refreshItemTotal()` — just needs the `total` field, not the items. |
| `GET /items?labels=<id>` | Tag filter — **repeated param**, not comma-joined. |
| `POST /items` (JSON) | Create: `{ name, quantity, description, locationId, tagIds }`. `locationId` required. |
| `GET/PUT/DELETE /items/{id}` | Item CRUD. PUT uses the large `HBItemUpdate` struct — always seed from `HBItemUpdate(from: detail)`. |
| `POST /items/{id}/attachments` (multipart) | Upload photo. Fields: `file`, `name`, `primary`. Type inferred from filename extension. |
| `GET /items/{id}/attachments/{aid}` | Fetch attachment bytes (raw). |
| `GET/POST/PUT/DELETE /locations/{id}` | Location CRUD. |
| `GET/POST/PUT/DELETE /tags` | Tag CRUD. `TagCreatePayload` used for both create and update. |
| `GET /groups/all` | All groups the user can access. Used to populate `store.groups` in `SiteMenuPopover`. |

**Important**: the `/v1/entities` endpoints on homebox.software are unreleased main-branch APIs — they do **not** exist in v0.25.x. Always cross-check `backend/app/api/routes.go` at the target release tag.

## File map

| File | Purpose |
|---|---|
| `HomeboxCatalogApp.swift` | `@main`. `ContentView`: 3-tab ZStack + `SiteMenuPopover` overlay (zIndex 100) + toast overlay (zIndex 200). `ShowSiteMenuKey` environment key passes `Binding<Bool>` into tab toolbars. `.task` refreshes group list, locations, and item total concurrently on every authenticated launch. `BrandMark` struct (unused in tabs but kept). |
| `SiteMenuPopover.swift` | Floating popover: loops `store.groups` as individual cards; active group gets accent border + checkmark; tapping switches group. Zoom-from-chevron animation. `Notification.Name.showSettings` + `.showToast` declared here. |
| `OnboardingView.swift` | Full-screen unauthenticated view — server URL + login form. Replaces the tab view until `store.isAuthenticated`. |
| `Theme.swift` | 30 `AppTheme` cases (ported from Homebox CSS), `ThemeManager`, **`Color(hex:)` non-failable**, `Color(h:s:l:)` HSL helper, `ThemeSwatch`. No orb backgrounds — solid bg only. |
| `Models.swift` | `HomeboxStore` (@MainActor ObservableObject): auth, `locationsFlat: [FlatLocation]`, `cachedItemTotal`, `groupName`, `groups: [HBGroup]`, `activeGroupId: String?` (persisted), `cachedGroupStats: [String: GroupStats]`. Key methods: `login()`, `refreshGroups()` (stores full group list + sets activeGroupId), `refreshLocations()`, `refreshItemTotal()` (pageSize=1 fast count), `setActiveGroup()` (switches X-Tenant, refreshes), `refreshAllGroupStats()` (per-group scoped clients). `GroupStats { locationCount, itemTotal }` struct. `FlatLocation` struct for DFS-flattened location tree. |
| `HomeboxClient.swift` | Async/await HTTP client. `tenantId: String?` on the struct — when set, sends `X-Tenant: tenantId` on every request to scope responses to that group. All Codable models: `HBItem` (with `effectiveLabels`), `HBLocation`, `HBTreeItem` (final class, recursive), `HBItemCreate`, `HBItemUpdate`, `HBGroup`, `HBTag`, etc. `uploadAttachment` hand-rolls multipart/form-data. `listGroups()` → `GET /v1/groups/all`. `listTags()` tries bare array first, then `{ items: [...] }` wrapper. |
| `Keychain.swift` | Minimal `SecItem` wrapper. Token only (`AccessibleAfterFirstUnlockThisDeviceOnly`). |
| `PhotoSource.swift` | `CameraSheet` (UIImagePickerController wrapper) + `downscale(_:maxDimension:)` — keeps JPEGs under a few hundred KB. |
| `AddItemView.swift` | Single-screen add form. `lockLocation` + `lockTags` toggles persist fields across submissions. AI tag suggestions via FoundationModels (0.8 s debounce, silently skips if Apple Intelligence unavailable). Presented as sheet from Items FAB. Two action buttons ("Add" and "Add Another") in `.safeAreaInset(edge: .bottom)` — no frosted glass. |
| `ItemsListView.swift` | Items tab. Hybrid semantic search (NaturalLanguage `min(wordEmbedding, sentenceEmbedding)`, threshold 1.15). FAB opens AddItemView. List/tile view toggle. Filter panel (location + tag + sort menu). Multi-select with bulk actions. `globalSearchQuery` binding. `SortOption` enum with 6 cases. `BulkEditSheet` for bulk location/tag changes and delete. |
| `LocationsTabView.swift` | Locations tab. Tree with collapse/expand, tile view, A-Z scrubber. FAB opens `CreateLocationSheet`. `globalSearchQuery` binding. In `LocationListRow`, the expand/collapse chevron is pinned to the far left (before depth-indent lines), `frame(width: 36).frame(maxHeight: .infinity)` for a large tap target. |
| `LocationDetailView.swift` | Location detail + edit + delete. Defines route structs: `ItemDetailRoute`, `LocationDetailRoute`. |
| `ItemDetailView.swift` | Item detail + edit + delete + photo. Contains `AuthImage` (authenticated image loader), `FullScreenImageView` (pinch-to-zoom, save, share), `EditItemSheet`. |
| `LocationPickerSheet.swift` | Reusable indented tree picker with search. Single-select. |
| `TagPickerSheet.swift` | Multi-select tag picker + inline create. Contains `TagChipsRow` for displaying tag capsules. Used in AddItemView / ItemDetailView / BulkEditSheet. |
| `TagsTabView.swift` | Tags tab. FAB opens `TagEditSheet(mode: .create)`. Tag detail shows items with that tag. `globalSearchQuery` binding. Uses `HomeboxTagPalette` — 12 hex colors matching Homebox web app. |
| `SettingsView.swift` | Server URL + login, theme picker (5-col grid of swatches), About. Presented as a sheet from `SiteMenuPopover`. |
| `Components.swift` | `ConditionalSearchable` (viewModifier for programmatic searchable toggle), `GlassCard`, `QuantityControl` (replaces broken `Stepper.labelsHidden()`), `AlphabetIndexBar`, `LetterPopupBox`, `ThumbnailStore` (plain `@MainActor class`, NOT ObservableObject), `ItemListRowContent` (shared item row). |
| `project.yml` | xcodegen spec — iOS 26 deployment, no signing. `CFBundleDisplayName: HomeBoy`. |
| `.github/workflows/build.yml` | macOS-15: xcodegen → archive unsigned → zip IPA → publish to "latest" release. |
| `Assets.xcassets/` | App icon: 1024×1024 isometric box on warm peach gradient. |

## Architecture rules — never violate

- **3 tabs only**: Items / Locations / Tags. Settings = sheet via NotificationCenter. Add Item = FAB sheet.
- **SiteMenuPopover is a ZStack overlay at zIndex(100)** in ContentView. Never put it inside a NavigationStack.
- **Toast overlay is at zIndex(200)** in ContentView. Trigger via `NotificationCenter.default.post(name: .showToast, ...)`. Never build separate toast UI.
- **No custom `.glass` / `.glassProminent` button styles** — iOS 26 SDK provides these natively. Custom ones → `ambiguous use of 'glass'` error.
- **Background pattern**: `.background(theme.current.backgroundColor.ignoresSafeArea())` as a modifier on the ScrollView/List/Form. Never wrap in `ZStack { background; content }` — breaks safe area and layout.
- **`Stepper(...).labelsHidden()` broken on iOS 26** — use `QuantityControl` from Components.swift.
- **`HBTreeItem` is a `final class`** — Codable structs can't be self-referential.
- **`Color(hex:)` in Theme.swift is non-failable** — never add another one, never write `?? someColor` after it.
- **Bearer token is raw** — no `"Bearer "` prefix in the Authorization header.
- **`HBItem.effectiveLabels`** = `labels ?? tags`. Never access `.labels` or `.tags` directly.
- **Tag filter nil guard**: `guard let labels = item.effectiveLabels else { return true }` — nil means server already filtered server-side.
- **`showSiteMenu` flows via `ShowSiteMenuKey` EnvironmentKey** as `Binding<Bool>`. Each tab reads `@Environment(\.showSiteMenu) var showSiteMenu`.
- **`ThumbnailStore` is NOT ObservableObject** — plain `@MainActor class`. Rows use local `@State`. Making it ObservableObject causes full-list re-renders on every thumbnail load.
- **Semantic search threshold is 1.15** with hybrid `min(wordEmbedding, sentenceEmbedding)`. Do not revert to pure sentenceEmbedding < 0.75.
- **Group switching uses `X-Tenant` header** — never re-login, never store multiple tokens. `store.client` always passes `activeGroupId` as `tenantId`. To switch: `await store.setActiveGroup(group)`.
- **Each tab has `.onChange(of: store.activeGroupId)`** to wipe local caches and reload on group switch. If you add a new tab, wire this too.
- **Toolbar leading slot is a `ToolbarItemGroup`** on all tabs — HomeBoy pill. Don't replace it with a single `ToolbarItem`.
- **Search bar**: each tab has `@State private var isSearchActive = false` + uses `ConditionalSearchable` modifier. The magnifying glass button in the toolbar sets `isSearchActive = true`.
- **Gesture propagation & filters**: Do not use `Button` with `.simultaneousGesture(LongPressGesture())` inside lists or scroll views; touches will propagate underneath. Use standard gestures (`.onTapGesture` and `.onLongPressGesture`) directly on custom container shapes instead.
- **AuthImage fullscreen**: Set `allowsFullScreen: false` on list rows and grid thumbnails. Tapping row/tile images should toggle selection or open details, never launch fullscreen image viewers directly from the main list.
- **Bottom bar / bulk action bar**: Use `ToolbarItemGroup(placement: .bottomBar)` with standard borderless text buttons. Do NOT use custom button shapes (like `.glass` style) in system bottom bars — they get squeezed by the OS toolbar layout engine and truncate labels into circles with ellipses (`...`). Do NOT use `.ultraThinMaterial` or frosted glass backgrounds — use opaque theme backgrounds or standard system bottom bar placement.
- **Selection header title**: Use standard `.navigationTitle` with `.navigationBarTitleDisplayMode(.inline)` for selection status headers instead of custom leading toolbar items to prevent `S...` truncation on narrow/compact devices.
- **Filter chips consume their own touches**: Use `.contentShape(Capsule())` + direct `.onTapGesture` / `.onLongPressGesture` on filter chip content. This prevents taps from propagating to underlying list rows. Never use `Button` or `.simultaneousGesture` for chips inside scroll views.
- **Multi-select in card/tile view**: In select mode, tile items use `.contentShape(Rectangle()).onTapGesture { toggleSelection(item) }` instead of `NavigationLink`. No `AuthImage` fullscreen should trigger. The entire tile is the tap target.
- **Multi-select activation**: Long-press (0.4s) on any item (row or tile) enters select mode via `.highPriorityGesture(LongPressGesture())`. Haptic feedback via `UIImpactFeedbackGenerator(style: .medium)`.

## Items view multi-select — bulk action bar

When `selectMode == true`:
- Tab bar is hidden (`.toolbar(selectMode ? .hidden : .visible, for: .tabBar)`)
- Navigation title shows "Select Items" or "N Selected"
- "Done" button in top-right trailing toolbar
- Bottom bar via `ToolbarItemGroup(placement: .bottomBar)` with:
  - "Select All" — sets `selectedIds` to all filtered item IDs
  - "Deselect All" — clears `selectedIds` (disabled when empty)
  - "Edit" (bold) — opens `BulkEditSheet` (disabled when empty)

## Items view sorting

`SortOption` enum with `@AppStorage("itemsSortOption")` persistence:
| Case | Display | Icon |
|---|---|---|
| `nameAZ` | Name: A-Z | `text.sort.ascending` |
| `nameZA` | Name: Z-A | `text.sort.descending` |
| `dateNewest` | Date: Newest | `clock.fill` |
| `dateOldest` | Date: Oldest | `clock` |
| `quantityHighToLow` | Quantity: High to Low | `arrow.up.circle.fill` |
| `quantityLowToHigh` | Quantity: Low to High | `arrow.down.circle.fill` |

Sort menu is a `Menu` with `Picker` inside the filter panel. Shows current sort as a capsule chip. Non-default sorts use accent color fill.

## Items view filter chips

Filter panel shown when `showFilters == true` (toggled via toolbar filter icon):
- **Location chip**: Shows location breadcrumb when active, "Location" when inactive. Tap toggles clear/pick. Long-press always opens picker.
- **Tag chip**: Shows "N tags" when active, "Tags" when inactive. Same tap/long-press behavior.
- **Sort chip**: `Menu` dropdown with all `SortOption` cases. Current sort has checkmark.
- **Clear button**: Appears when any filter is active.

## AddItemView floating buttons

Two buttons in `.safeAreaInset(edge: .bottom)`:
- **"Add Another"** — `.glass` button style. Saves item, resets form (respects lockLocation/lockTags), stays open.
- **"Add"** — `.glassProminent` button style. Saves item, dismisses sheet.
- Both disabled when `!canSubmit` (name empty OR no location selected).
- NO frosted glass background. No `.ultraThinMaterial`.

## Common tasks

### Add a new optional field to items
1. Add property to `HBItemCreate` (and `HBItemUpdate`) in `HomeboxClient.swift`.
2. Add `@State` + UI in `AddItemView.swift`.
3. Wire into `payload` in `submit()`.

### Wire a new endpoint
Method on `HomeboxClient`. Reuse `request(path:method:body:contentType:query:)` — sets auth, handles 401/transport errors.

### Show a toast from anywhere
```swift
NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Done!"])
```

### Force re-login on token expiry
Wire `POST /v1/users/refresh` inside `HomeboxClient.request` on `HBError.unauthorized`, retry once.

### Change the app icon
Replace `Assets.xcassets/AppIcon.appiconset/AppIcon.png` with a new 1024×1024 PNG.

### Push and trigger a build
```bash
cd "/Users/nitin/Documents/antigravity/HomeBoy"
git add <files>
git commit -m "..."
git push origin main
```
IPA appears at https://github.com/nphil/homebox-catalog-ios/releases/tag/latest.

## Known gotchas
- xcodegen sources path is `.` — any new `.swift` file in root is auto-included.
- `SKIP_INSTALL=NO` and `INSTALL_PATH="/Applications"` required in archive or `.app` won't appear in xcarchive.
- Authorization header takes **raw token** — no `"Bearer "` prefix.
- `homebox.software` docs show the **unreleased** `/v1/entities` API. v0.25.x uses `/v1/items` + `/v1/locations`. Always check `routes.go` at the target release tag.
- For >1000 items, pagination is needed. Currently `pageSize=1000` in one shot.
- `refreshItemTotal()` uses `pageSize=1` — it only needs the `total` field from `HBItemListResponse`, not the items themselves.
- **Semantic Search**: hybrid `min(sentenceEmbedding, wordEmbedding)` threshold 1.15. Do not revert to strict sentence embedding — single-word synonyms break.
- **AI tag suggestions**: FoundationModels (Apple Intelligence). Check `SystemLanguageModel.default.isAvailable` before use; silently skip if unavailable.
- **`listTags()` dual-shape decoding**: Homebox versions return tags as either a bare `[HBTag]` array or wrapped in `{ items: [HBTag] }`. The client tries both.
- **Login is form-encoded** (`application/x-www-form-urlencoded`), not JSON.
- **`HBItemDetail` has nearly all fields optional** except `id` and `name`. Always handle nil values.
- **`ConditionalSearchable` modifier**: Workaround because `.searchable` on iOS has no clean way to programmatically toggle visibility. Only applies searchable when `isPresented` or text non-empty.
