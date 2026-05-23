# HomeBoy — LLM Handoff Guide

A field guide for any LLM picking up this project. Read this *before* touching code. The companion file `CLAUDE.md` has the API surface; this file is the **gotchas, quirks, and unwritten rules** you'll otherwise discover the hard way.

---

## 1. The Project in One Paragraph

iPhone app (iOS 26, SwiftUI, Liquid Glass) for rapid item entry into a self-hosted [Homebox](https://homebox.software/) v0.25.x instance. Display name is **HomeBoy**; repo/bundle id keep the original `homebox-catalog` naming so AltStore sideloads update in place. Five tabs: **Add / Items / Locations / Tags / Settings**. Every list view talks directly to the Homebox REST API — there is no local persistence beyond keychain (token) and `UserDefaults` (server URL, username, theme choice).

---

## 2. The Unbreakable Rules

These have all been learned through pain. Don't relearn them.

### Build & deploy
- **Never run Xcode locally.** Always push to GitHub; CI builds the IPA. The user does not have Xcode set up for this project and there's no Apple Developer signing.
- **Every meaningful change → commit + push.** That's how it gets onto the user's phone (via AltStore sideload).
- **`HomeboxCatalog.xcodeproj` is generated, not committed.** xcodegen runs in CI from `project.yml`.
- **xcodegen sources path is `.`** — any new `.swift` file in the repo root is auto-included. No need to add files to a project manifest.
- **Pushing to `.github/workflows/*` requires `workflow` scope** on the `gh` token. Fix with `gh auth refresh -h github.com -s workflow`.
- **Always end a session by linking the user to the latest release**: https://github.com/nphil/homebox-catalog-ios/releases/tag/latest

### Code
- **No custom `.glass` / `.glassProminent` button styles.** iOS 26 SDK provides these natively. Defining your own causes `ambiguous use of 'glass'` errors.
- **`Color(hex:)` already exists in `Theme.swift`** and is **non-failable** (returns `Color`, not `Color?`). Do **not** add another one. Do **not** write `?? .gray` or `?? someColor` after it — the compiler will warn that the LHS is non-optional.
- **Background pattern**: apply `.background(theme.current.backgroundColor.ignoresSafeArea())` as a *modifier* on the ScrollView/List/Form. Do **not** wrap in a `ZStack { background; content }` — the ZStack sizes to its largest child and `.ignoresSafeArea()` corrupts the ScrollView's effective width.
- **`Stepper(...).labelsHidden()` is broken on iOS 26** — it reserves layout space for the invisible label and blows out the row. Use `QuantityControl` from `Components.swift` instead.
- **Recursive Codable structs don't compile in Swift.** `HBTreeItem` is a `final class` for this reason. Conform via `Hashable` on `id`.

---

## 3. File Map

| File | Purpose | Watch out for |
|---|---|---|
| `HomeboxCatalogApp.swift` | `@main`, TabView (5 tabs), transparent nav appearance, `BrandMark` view | Tab order: Add / Items / Locations / Tags / Settings. Don't reorder casually — user has muscle memory. |
| `Theme.swift` | 30 themes ported from Homebox web CSS, `ThemeManager`, `Color(h:s:l:)` HSL helper, **`Color(hex:)` non-failable**, `ThemeSwatch` | Don't add another `Color(hex:)`. Don't use orb backgrounds — solid bg only. |
| `Models.swift` | `HomeboxStore` (ObservableObject), `FlatLocation`, `Route` structs, `HBItem.tagId`s helpers | Locations are DFS-flattened into `locationsFlat` with `ancestors` chain. |
| `HomeboxClient.swift` | Async/await HTTP client, all Codable models | Bearer token is **raw** — no `"Bearer "` prefix. Multipart upload is hand-rolled. |
| `Keychain.swift` | `SecItem` wrapper for token, `AccessibleAfterFirstUnlockThisDeviceOnly` | Token only. Password never persisted. |
| `PhotoSource.swift` | `CameraSheet` (UIImagePickerController wrapper) + `downscale(_:maxDimension:)` | Keeps JPEGs under a few hundred KB. |
| `AddItemView.swift` | The main form — single-screen, no step flow | Auto-focuses name field 0.15s after appear. `lockLocation` and `lockTags` toggles preserve fields across submissions. |
| `LocationPickerSheet.swift` | Indented-tree picker, used by AddItem and CreateLocationSheet | Shared component — don't fork it. |
| `LocationsTabView.swift` | Tree list + tile grid view, A-Z index bar, search | Has `viewMode` toggle (list/tile) in toolbar. List view supports collapse/expand per node — state held in `collapsedIds`. |
| `ItemsListView.swift` | Item list with section headers, tag filter, location filter, A-Z bar | Tag filter uses `effectiveLabels` to handle API version variance — see §5. |
| `ItemDetailView.swift` | Photos + full fields + edit + delete | |
| `LocationDetailView.swift` | Edit/delete + children + items in location | |
| `TagPickerSheet.swift` | Multi-select tag picker + inline "create new" | Distinct from `TagEditSheet` in TagsTabView. |
| `TagsTabView.swift` | Full tag management: list, create, edit (name+color+desc), delete, view items per tag | Uses `HomeboxTagPalette` — 12 hex colors matching Homebox web. |
| `SettingsView.swift` | Server URL + login + theme picker (5-col grid) + about | URL auto-prefixes `https://` if scheme missing. |
| `Components.swift` | `GlassCard`, `QuantityControl`, `AlphabetIndexBar`, `LetterPopupBox`, `ThumbnailStore` | **`ThumbnailStore` is not an ObservableObject** — see §6. |
| `project.yml` | xcodegen spec | iOS 26 deployment target, `CODE_SIGNING_ALLOWED=NO`, `CFBundleDisplayName: HomeBoy`. |
| `.github/workflows/build.yml` | CI: xcodegen → archive unsigned → zip IPA → publish to `latest` release | macos-15, Xcode 26. |

---

## 4. Homebox API Quirks (v0.25.x)

All under `${serverURL}/api/v1/`. Bearer token in `Authorization` header (no `Bearer ` prefix).

### The big landmine
**The `/v1/entities/*` endpoints on the public Homebox docs site are unreleased main-branch APIs and DO NOT EXIST in v0.25.x.** Always use the separate `/v1/items` and `/v1/locations` routes. If you ever need to verify an endpoint exists, check `backend/app/api/routes.go` at the target release tag, not the docs site.

### Version-tolerant decoding
- **`HBItem` decodes both `labels` and `tags` fields** (different Homebox versions emit different names; some omit both in the list summary). Use `item.effectiveLabels` (which returns `labels ?? tags`) everywhere, not raw access.
- **`listTags()` accepts both `[HBTag]` and `{ items: [HBTag] }` shapes.** Try-bare-array-first, then wrapper.
- **`HBItemDetail` has every field optional except `id`/`name`** for the same reason — server versions differ.

### Endpoints used

| Endpoint | Notes |
|---|---|
| `POST /users/login` | **Form-encoded**, not JSON. Returns `{ token, expiresAt }`. |
| `GET /locations` | Flat, no parent info. We barely use this — prefer `/locations/tree`. |
| `GET /locations/tree?withItems=false` | Nested. Recursive `HBTreeItem` (final class). DFS-flattened into `FlatLocation` with ancestor chain. |
| `POST /locations` | `{ name, parentId?, description }` |
| `GET /items?page=1&pageSize=1000` | Paginated. We grab 1000 in one shot — TODO for >1000 inventories. |
| `GET /items?labels=<id>&labels=<id2>` | Tag filter is a repeated query param, not comma-joined. |
| `POST /items` | `{ name, quantity, description, locationId, parentId?, tagIds }`. `locationId` is **required**. |
| `GET/PUT/DELETE /items/{id}` | Full CRUD. PUT body is huge (`HBItemUpdate`) — round-trip via `HBItemUpdate(from: detail)` so untouched fields keep their values. |
| `POST /items/{id}/attachments` | **`multipart/form-data`**, hand-rolled. Fields: `file`, `name`, `primary`. Server infers attachment type from filename extension — that's why uploads use `photo-<epoch>.jpg`. |
| `GET /items/{id}/attachments/{aid}` | Returns raw bytes. |
| `GET/POST/PUT/DELETE /tags` | Standard CRUD. `TagCreatePayload` is reused for both create and update. |

### Auth model
- Token lives in Keychain (`AccessibleAfterFirstUnlockThisDeviceOnly`).
- 401/403 → `HBError.unauthorized`. UI surfaces "Not signed in. Open Settings to log in." — there's **no auto-refresh** wired up. To add: call `POST /v1/users/refresh` from inside `HomeboxClient.request` on `unauthorized`, retry once.

---

## 5. The Tag Filter Story (Critical Context)

The tag filter was broken for a long time because of API version variance. Current correct logic in `ItemsListView.swift`:

```swift
// Filter items by selected tag IDs
let filtered = items.filter { item in
    guard !selectedTagIds.isEmpty else { return true }
    guard let labels = item.effectiveLabels else {
        // API didn't include labels in the summary —
        // trust the server-side ?labels= filter we sent.
        return true
    }
    return !selectedTagIds.isDisjoint(with: Set(labels.map(\.id)))
}
```

**Why the `return true` on nil:** Some Homebox versions don't include `labels` or `tags` in the item list summary at all. In that case, the server still honors the `?labels=` query param we passed to `GET /items`, so the items in the response are *already filtered*. Rejecting them client-side would empty the list.

**Why `effectiveLabels`:** It returns `labels ?? tags`. Different server versions emit different field names. Always use this, never `item.labels` or `item.tags` directly.

`HBItem.effectiveLabels`:
- `nil` = API didn't include the field → trust server filter.
- `[]` = API included it, item genuinely has no tags.
- `[HBTag(...)]` = item has these tags.

---

## 6. Performance: ThumbnailStore Pattern

**Problem:** Naive thumbnail loading caused all rows in the items list to re-render whenever any one thumbnail finished loading, producing visible scroll stutter.

**Solution** (in `Components.swift`):

1. `ThumbnailStore` is a **plain `@MainActor class`**, NOT `ObservableObject`. No `@Published` properties.
2. Each row holds a local `@State var thumbnailPath: String?` and calls `store.load(itemId:)` in `.task`.
3. `ThumbnailStore.load()` deduplicates concurrent requests via `inFlight: [String: Task<String?, Never>]` — if two rows ask for the same item at once, only one network call happens.
4. The store caches `[itemId: filePath]` on disk so subsequent app launches are instant.

**Rule:** If you add another async-loaded property to rows, follow the same pattern. Never make `ThumbnailStore` (or similar) an `ObservableObject` — it will re-trigger every row.

---

## 7. UI Patterns You'll See Everywhere

### Themed card background
```swift
.background {
    RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
    RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(0.07))
}
.overlay(RoundedRectangle(cornerRadius: 14).stroke(theme.current.accentColor.opacity(0.18), lineWidth: 1))
```
Two stacked fills (material + tint) give the "frosted glass tinted with theme" look. Don't try to do it with a single fill — opacity blending differs.

### Caption tags above fields
```swift
Text("ITEM").font(.caption.weight(.semibold)).tracking(0.6)
    .foregroundStyle(theme.current.accentColor.opacity(0.75))
```
Always uppercase, `tracking(0.6)`, accent color at 0.75 opacity.

### A-Z scrubber
- Invisible `Color.clear` hit area (32pt wide) on the trailing edge of the scroll view.
- `DragGesture(minimumDistance: 0)` fires `onSelect` as finger moves.
- `LetterPopupBox` is rendered as a separate centered overlay (themed translucent box, big rounded letter) — only visible while `currentLetter != nil`.
- Auto-hides 0.35s after drag ends.
- Pattern is used in `ItemsListView` and `LocationsTabView`. The bar is in `Components.swift`.

### Navigation route structs
```swift
struct ItemDetailRoute: Hashable { let id: String }
struct LocationDetailRoute: Hashable { let id: String }
struct TagDetailRoute: Hashable { let id: String }
```
Each tab's `NavigationStack` registers `.navigationDestination(for:)` for the routes it can show. Don't push views directly — push routes.

---

## 8. Theming

- **30 themes** ported from Homebox web app's `assets/css/main.css`.
- Each theme has `backgroundColor`, `accentColor`, `preferredColorScheme`.
- Stored in `Theme.swift` as static `AppTheme` values.
- User picks one in Settings → 5-column `LazyVGrid` of `ThemeSwatch` previews.
- Selection persists in `UserDefaults`.
- Apply via `theme.current.accentColor`, `theme.current.backgroundColor`, `theme.current.preferredColorScheme`.
- The root `WindowGroup` applies `.tint(theme.current.accentColor)` and `.preferredColorScheme(...)`.
- `Color(h:s:l:)` HSL helper lets us paste CSS HSL values straight in.

---

## 9. Common Tasks (Cookbook)

### Add a new optional field to items
1. Add property to `HBItemCreate` (and `HBItemUpdate` if editable) in `HomeboxClient.swift`.
2. Add `@State` + UI in `AddItemView.swift` (or `ItemDetailView.swift` for edit).
3. Wire into `payload` in `submit()`.
4. If editable in detail view, ensure `HBItemUpdate(from: detail, ...)` preserves it.

### Add a new endpoint
Method on `HomeboxClient`. Reuse the private `request(path:method:body:contentType:query:)` helper — it sets auth, handles 401/transport errors. Always decode in a `do/catch` that re-throws as `HBError.decode`.

### Add a new tab
1. Add `case` in `ContentView` body (`HomeboxCatalogApp.swift`).
2. Use `BrandMark` as `.principal` toolbar item to stay consistent.
3. Background goes on the inner `ZStack` or directly on the `ScrollView` — not on `NavigationStack`.

### Change the app icon
Replace `Assets.xcassets/AppIcon.appiconset/AppIcon.png` with a new 1024×1024 PNG.

### Push and trigger a build
```bash
cd "/Users/nitin/AI Playground/homebox-catalog-ios"
git add <files>
git commit -m "..."
git push origin main
```
Watch CI: `gh run watch` or visit the Actions tab. IPA appears at https://github.com/nphil/homebox-catalog-ios/releases/tag/latest.

---

## 10. Things That Are Easy to Get Wrong

- **Don't add a `Color(hex:)` extension.** Theme.swift has the canonical non-failable one. The compiler error is `invalid redeclaration of 'init(hex:)'`. Burned us once already.
- **Don't define custom `.glass` button styles.** Native iOS 26 conflict.
- **Don't put `.background(...)` in a wrapping ZStack** with the content as a sibling. Use it as a modifier on the scrolling view directly.
- **Don't use `Stepper.labelsHidden()`.** Use `QuantityControl`.
- **Don't decode `HBItem.labels` or `.tags` directly.** Use `effectiveLabels`.
- **Don't make data stores into `ObservableObject` if they update frequently.** Use the local-`@State`-per-row pattern (see `ThumbnailStore`).
- **Don't reach for `/v1/entities`.** Doesn't exist in v0.25.x.
- **Don't add `"Bearer "` prefix to the auth header.** Homebox wants the raw token.
- **Don't commit `HomeboxCatalog.xcodeproj`.** Generated artifact.
- **Don't try to build locally.** Push and let CI handle it.

---

## 11. Open / Known Issues

- **No pagination.** `GET /items?pageSize=1000` works for the user's current inventory. If you go beyond ~1000 items, build proper paging into `ItemsListView`.
- **No token refresh.** 401 forces re-login via Settings. Wire `POST /v1/users/refresh` if this becomes annoying.
- **Sendable closure warning** in `AddItemView.swift` (`theme.current.accentColor` inside `PhotosPicker` label closure). Currently just a warning — Swift 5.10, no strict concurrency. If/when the project moves to Swift 6, capture `theme.current.accentColor` to a local `let` outside the closure.
- **Sort UI was removed.** Items list is currently sorted by `createdAt` desc (or by name within sections). If you re-add sort, don't bring back the old multi-option menu — the user explicitly wanted it gone.

---

## 12. Semantic Search Implementation

Apple's `NLEmbedding.sentenceEmbedding` natively scores single-word synonyms poorly (e.g. "seat" vs "couch" gets a terrible 1.33 distance).
To fix this, `ItemsListView` uses a **hybrid search model**:
1. It tokenizes the item names into individual words.
2. It calculates the minimum cosine distance between query words and item words using `wordEmbedding`.
3. It also calculates the full string distance using `sentenceEmbedding`.
4. It takes `min(wordDist, sentDist)` and applies a relaxed threshold of `1.15`.
**Rule:** Do not revert to a pure `sentenceEmbedding < 0.75` check, or single-word synonym matches will break again.

---

## 13. The User (Nitin)

This part matters more than the tech.

- **Programming background**: Last did C++ in high school ~20 years ago. Knows variables, constants, if/else, for loops. Does **not** know: pointers, manual memory, concurrency primitives, OOP beyond basics, networking specifics, Swift idioms.
- **Has ADHD**: Keep explanations **short and focused**. One concept per change. No walls of text. No information dumps.
- **Learns by doing**: Briefly explain the *why* behind each change as you make it. Tie new concepts to what was just done. Use analogies.
- **Vibecoding workflow**: User describes what they want, LLM implements + commits + pushes, CI produces an IPA, user installs via AltStore, tests on iPhone, reports back. Loop.
- **Token-conscious**: Don't over-explain. Don't re-read files you just wrote. Don't repeat the same fix attempt twice. The user *will* call this out as wasted tokens.
- **Always end a HomeBoy session by linking the latest release**: https://github.com/nphil/homebox-catalog-ios/releases/tag/latest

---

## 14. Quick Reference

| Want to... | Look at |
|---|---|
| Understand the API surface | `CLAUDE.md` §"Homebox API surface" |
| See all Codable models | `HomeboxClient.swift` top |
| Add a new tab | `HomeboxCatalogApp.swift` |
| Change theme list | `Theme.swift` |
| Touch the add-item form | `AddItemView.swift` |
| Touch the items list | `ItemsListView.swift` |
| Touch a detail view | `ItemDetailView.swift` / `LocationDetailView.swift` |
| Add a reusable widget | `Components.swift` |
| Change CI/build | `.github/workflows/build.yml` |
| Change app metadata | `project.yml` |

**Repo:** https://github.com/nphil/homebox-catalog-ios
**Latest IPA:** https://github.com/nphil/homebox-catalog-ios/releases/tag/latest
