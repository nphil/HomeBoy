# HomeBoy — LLM Handoff Guide

A field guide for any LLM picking up this project. Read this *before* touching code. The companion file `CLAUDE.md` has the API surface and file map; this file is the **gotchas, quirks, and unwritten rules** you'll otherwise discover the hard way.

---

## 1. The Project in One Paragraph

iPhone app (iOS 26, SwiftUI, Liquid Glass) for rapid item entry into a self-hosted [Homebox](https://homebox.software/) v0.25.x instance. Display name is **HomeBoy**; repo/bundle id keep `homebox-catalog` naming so AltStore sideloads update in place. **Three tabs: Items / Locations / Tags**. Settings is a sheet (not a tab), Add Item is a FAB (not a tab). Every list view talks directly to the Homebox REST API — no local persistence beyond Keychain (token) and `UserDefaults` (server URL, username, theme).

---

## 2. The Unbreakable Rules

These have all been learned through pain. Don't relearn them.

### Build & deploy
- **Never run Xcode locally.** Always push to GitHub; CI builds the IPA. No Apple Developer signing.
- **Every meaningful change → commit + push.** That's how it gets onto the user's phone (AltStore sideload).
- **`HomeboxCatalog.xcodeproj` is generated, not committed.** xcodegen runs in CI from `project.yml`.
- **xcodegen sources path is `.`** — any new `.swift` file in the repo root is auto-included.
- **Pushing `.github/workflows/*` requires `workflow` scope** on the gh token. Fix: `gh auth refresh -h github.com -s workflow`.
- **Always end a session** by linking the user to: https://github.com/nphil/homebox-catalog-ios/releases/tag/latest

### Code
- **No custom `.glass` / `.glassProminent` button styles.** iOS 26 SDK provides these natively. Defining your own → `ambiguous use of 'glass'` compile error.
- **`Color(hex:)` already exists in `Theme.swift`** and is **non-failable** (returns `Color`, not `Color?`). Do NOT add another one. Do NOT write `?? .gray` after it — the compiler warns that the LHS is non-optional.
- **Background pattern**: `.background(theme.current.backgroundColor.ignoresSafeArea())` as a modifier on the ScrollView/List/Form. Do NOT wrap in `ZStack { background; content }` — the ZStack sizes to its largest child and `.ignoresSafeArea()` corrupts the ScrollView's effective width.
- **`Stepper(...).labelsHidden()` is broken on iOS 26** — reserves layout space for the invisible label and blows out the row. Use `QuantityControl` from `Components.swift`.
- **Recursive Codable structs don't compile.** `HBTreeItem` is a `final class` for this reason.

---

## 3. Tab & Navigation Structure

### The 3 tabs
```
Items tab          → ItemsListView     (shippingbox.fill)
Locations tab      → LocationsTabView  (mappin.and.ellipse)
Tags tab           → TagsTabView       (tag.fill)
```

### What is NOT a tab
- **Settings** — sheet, triggered by `NotificationCenter.default.post(name: .showSettings, object: nil)`, handled in `ContentView`.
- **Add Item** — FAB (floating circle button, bottom-right) on the Items tab, presents `AddItemView` as a sheet.

### SiteMenuPopover
Each tab toolbar has a **`ToolbarItemGroup(placement: .topBarLeading)`** with two items: a magnifying glass (search) and the HomeBoy pill (shippingbox.fill + "HomeBoy" + chevron.down). Tapping the pill toggles `showSiteMenu: Bool` in `ContentView`.

`SiteMenuPopover` is a `ZStack` overlay in `ContentView` at `zIndex(100)` — it floats above all three tabs. **Never put it inside a `NavigationStack`.**

- **Animation**: `popoverSpring = .spring(duration: 0.25, bounce: 0.22)`. Transition: `.scale(scale: 0.5, anchor: .topLeading).combined(with: .opacity)` — fast, springy, grows from top-left.
- **Group cards**: loops `store.groups`. Active group = accent border (2.5 pt) + filled checkmark + live `store.locationsFlat.count` + `store.cachedItemTotal`. Inactive groups show counts from `store.cachedGroupStats[group.id]` (populated by `refreshAllGroupStats()`).
- **Opening the popover** triggers `store.refreshAllGroupStats()` to keep inactive card counts fresh.
- **Switching groups**: calls `store.setActiveGroup()` → sets `activeGroupId` → changes `X-Tenant` header on `store.client` → tabs see the `.onChange(of: store.activeGroupId)` and reload → posts `.showToast` → dismisses popover.
- **Settings button**: posts `.showSettings` notification.

`showSiteMenu` flows to child views via a custom `EnvironmentKey`:
```swift
// HomeboxCatalogApp.swift
private struct ShowSiteMenuKey: EnvironmentKey {
    static let defaultValue: Binding<Bool> = .constant(false)
}
extension EnvironmentValues {
    var showSiteMenu: Binding<Bool> { get/set via ShowSiteMenuKey }
}
// In each tab:
@Environment(\.showSiteMenu) var showSiteMenu
withAnimation(.spring(duration: 0.25, bounce: 0.22)) {
    showSiteMenu.wrappedValue.toggle()
}
```

### Toast system
```swift
// Trigger from anywhere:
NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Done!"])
// ContentView handles it: Capsule pill at zIndex(200), auto-dismisses after 2.5 s
```
Both `Notification.Name.showSettings` and `.showToast` are declared in `SiteMenuPopover.swift`.

### Global search
`globalSearchQuery: String` is a `@Binding` passed from `ContentView` into all three tabs. Each tab has:
```swift
@State private var isSearchActive = false
// On NavigationStack:
.searchable(text: $globalSearchQuery, isPresented: $isSearchActive, prompt: "Search …")
```
The magnifying glass in the toolbar pill sets `isSearchActive = true`, which opens the native iOS search bar with keyboard animation. iOS resets it to `false` + clears the text when the user taps Cancel.

---

## 4. File Map

| File | Purpose | Watch out for |
|---|---|---|
| `HomeboxCatalogApp.swift` | `@main`, `ContentView` (3-tab ZStack + SiteMenuPopover zIndex 100 + toast zIndex 200), `ShowSiteMenuKey` env key, `BrandMark`, `showToast()` helper | `.task` refreshes group list + locations + item count **concurrently** on every authenticated launch — this is what makes the popover show correct counts. |
| `SiteMenuPopover.swift` | Group cards, zoom animation, group switching, `Notification.Name.showSettings` + `.showToast` | Always in the view hierarchy (state persists). Never render it conditionally at the parent level. |
| `OnboardingView.swift` | Full-screen unauthenticated server config + login | Replaces the tab view entirely when `!store.isAuthenticated`. |
| `Theme.swift` | 30 themes, `ThemeManager`, **`Color(hex:)` non-failable**, `Color(h:s:l:)`, `ThemeSwatch` | Don't add another `Color(hex:)`. Solid backgrounds only — no orb backgrounds. |
| `Models.swift` | `HomeboxStore` — auth, `locationsFlat`, `cachedItemTotal`, `groupName`, **`groups: [HBGroup]`**, **`activeGroupId: String?`** (persisted in UserDefaults), **`cachedGroupStats: [String: GroupStats]`**. Methods: `login()`, `refreshGroups()`, `refreshLocations()`, **`refreshItemTotal()`** (pageSize=1), **`setActiveGroup()`**, **`refreshAllGroupStats()`**. `GroupStats { locationCount, itemTotal }` struct. | Class is `@MainActor final`. `store.client` is a computed property that constructs a `HomeboxClient` with `activeGroupId` as `tenantId` — switching activeGroupId automatically scopes all subsequent requests. |
| `HomeboxClient.swift` | Async/await HTTP client, all Codable models, `listGroups()` → `GET /v1/groups/all`. **`tenantId: String?`** on the struct; when set, every request gets `X-Tenant: tenantId` header. | Bearer token = raw string, no "Bearer " prefix. `HBItem.effectiveLabels` = `labels ?? tags`. |
| `Keychain.swift` | `SecItem` wrapper. Token only. | Password is never persisted. |
| `PhotoSource.swift` | `CameraSheet` + `downscale()` | Keeps JPEGs under a few hundred KB. |
| `AddItemView.swift` | Add form. `lockLocation` + `lockTags` toggles. AI tag suggestions (FoundationModels, 0.8 s debounce). | Presented as a modal sheet from Items FAB. AI suggestions silently skipped if Apple Intelligence unavailable. |
| `ItemsListView.swift` | Items tab. Hybrid semantic search (NaturalLanguage). FAB → AddItemView. Filter panel. List/tile toggle. | `ThumbnailStore` is a plain `@MainActor class`, NOT ObservableObject. |
| `LocationsTabView.swift` | Locations tab. Tree with collapse/expand, tile view, A-Z scrubber, FAB → CreateLocationSheet. | In `LocationListRow`, the chevron is at the far left (before depth-indent lines), 36 px wide × full row height. Don't move it back inside the depth indentation — that's what made it hard to tap. |
| `ItemDetailView.swift` | Item detail + edit + delete + photo. | |
| `LocationDetailView.swift` | Location detail + edit + delete. | |
| `LocationPickerSheet.swift` | Shared indented tree picker (used by AddItem + CreateLocationSheet). | Don't fork it. |
| `TagPickerSheet.swift` | Multi-select tag picker + inline create (used by AddItem + ItemDetail). | Distinct from `TagEditSheet` in TagsTabView. |
| `TagsTabView.swift` | Tags tab. FAB → TagEditSheet. Detail view shows items with that tag. | Uses `HomeboxTagPalette` — 12 hex colors matching Homebox web app. |
| `SettingsView.swift` | Server + login, theme picker, About, logout. | Presented as a sheet from SiteMenuPopover's Settings button. |
| `Components.swift` | `GlassCard`, `QuantityControl`, `AlphabetIndexBar`, `LetterPopupBox`, `ThumbnailStore`, `ItemListRowContent` | **`ThumbnailStore` is NOT ObservableObject** — see §8. |
| `project.yml` | xcodegen spec. `CFBundleDisplayName: HomeBoy`, iOS 26, no signing. | |
| `.github/workflows/build.yml` | CI: xcodegen → archive → zip → "latest" release. | macos-15, Xcode 26. |

---

## 5. Homebox API Quirks (v0.25.x)

All under `${serverURL}/api/v1/`. Bearer token in `Authorization` header (no `Bearer ` prefix).

### The big landmine
**`/v1/entities/*` on the public docs site = unreleased main-branch API. Does NOT exist in v0.25.x.** Always use `/v1/items` + `/v1/locations`. Verify against `backend/app/api/routes.go` at the target release tag.

### Version-tolerant decoding
- **`HBItem` decodes both `labels` and `tags` fields** — different Homebox versions emit different names, some omit both in list summaries. Always use `item.effectiveLabels` (`labels ?? tags`).
- **`listTags()` tries bare array first, then `{ items: [...] }` wrapper** — both shapes appear in the wild.
- **`HBItemDetail` has every field optional except `id`/`name`**.

### Key endpoint notes
| Endpoint | Notes |
|---|---|
| `POST /users/login` | **Form-encoded**, not JSON. |
| `GET /items?labels=<id>&labels=<id2>` | Tag filter is a **repeated param** — not comma-joined. |
| `GET /items?page=1&pageSize=1` | `refreshItemTotal()` uses this to cheaply get total count. |
| `PUT /items/{id}` | Uses the large `HBItemUpdate` — always seed via `HBItemUpdate(from: detail)`. |
| `POST /items/{id}/attachments` | **Multipart**, hand-rolled. `name` field is required. Type inferred from filename extension. |
| `GET /groups/all` | Returns all groups. Used by `store.groups` / `SiteMenuPopover`. |

---

## 6. Multi-Tenant Group Switching (X-Tenant Header)

Homebox's backend middleware reads `X-Tenant: <groupUUID>` on every request and scopes the response to that group. The same auth token works for all groups the user belongs to.

**How it's wired in HomeBoy:**
1. `HomeboxClient` has `tenantId: String?`. When set, every `request()` call adds `X-Tenant: tenantId` to the headers.
2. `HomeboxStore.client` is a **computed property** that creates a new `HomeboxClient(serverURL:token:tenantId: activeGroupId)` on every access.
3. Changing `activeGroupId` in `HomeboxStore` automatically scopes all subsequent API calls — no re-login, no token swap.
4. `setActiveGroup(_ group: HBGroup)` sets `activeGroupId`, clears stale caches, refreshes locations + item count, and updates `cachedGroupStats[group.id]`.
5. Each tab has `.onChange(of: store.activeGroupId)` that wipes its local `@State` caches and triggers a fresh load.

**`cachedGroupStats: [String: GroupStats]`** stores `{ locationCount, itemTotal }` per group so the SiteMenuPopover can show counts on inactive cards without live API calls:
```swift
// SiteMenuPopover reads:
let locCount: Int = isActive ? store.locationsFlat.count
                             : (store.cachedGroupStats[group.id]?.locationCount ?? 0)
```
`refreshAllGroupStats()` constructs a temporary scoped `HomeboxClient` for each group and fetches its counts concurrently. Called when the popover opens and at app launch.

---

## 7. The Tag Filter Story (Critical Context)

```swift
let filtered = items.filter { item in
    guard !selectedTagIds.isEmpty else { return true }
    guard let labels = item.effectiveLabels else {
        // API didn't include labels in the summary — trust server-side ?labels= filter.
        return true
    }
    return !selectedTagIds.isDisjoint(with: Set(labels.map(\.id)))
}
```

**Why `return true` on nil:** Some Homebox versions omit `labels`/`tags` from the item list summary. The server still filters via `?labels=` query param, so items in the response are already pre-filtered. Rejecting them client-side empties the list.

`HBItem.effectiveLabels`:
- `nil` = API omitted the field → trust server filter.
- `[]` = Item genuinely has no tags.
- `[HBTag]` = Item has these tags.

---

## 8. Performance: ThumbnailStore Pattern

**Problem:** Naive thumbnail loading caused all rows to re-render whenever any thumbnail finished loading → visible scroll stutter.

**Solution** (`Components.swift`):
1. `ThumbnailStore` is a **plain `@MainActor class`** — no `ObservableObject`, no `@Published`.
2. Each row holds a local `@State var thumbnailPath: String?` and calls `store.load(itemId:)` in `.task`.
3. `ThumbnailStore.load()` deduplicates via `inFlight: [String: Task<String?, Never>]`.
4. Results are cached on disk — subsequent launches are instant.

**Rule:** If you add another async-loaded per-row property, follow this pattern. Never make `ThumbnailStore` (or similar stores) `ObservableObject`.

---

## 9. UI Patterns You'll See Everywhere

### Themed card background
```swift
.background {
    RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
    RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(0.07))
}
.overlay(RoundedRectangle(cornerRadius: 14).stroke(theme.current.accentColor.opacity(0.18), lineWidth: 1))
```
Two stacked fills (material + tint) = frosted-glass-tinted-with-theme. A single fill won't look the same.

### Caption section labels
```swift
Text("ITEMS").font(.caption.weight(.semibold)).tracking(0.6)
    .foregroundStyle(theme.current.accentColor.opacity(0.75))
```
Always uppercase, tracking 0.6, accent at 0.75 opacity.

### FAB pattern (Floating Action Button)
```swift
VStack { Spacer()
    HStack { Spacer()
        Button { showCreate = true } label: {
            Image(systemName: "plus")
                .font(.title2.weight(.semibold)).foregroundStyle(.white)
                .frame(width: 56, height: 56)
                .background(theme.current.accentColor).clipShape(Circle())
                .shadow(color: theme.current.accentColor.opacity(0.4), radius: 6, x: 0, y: 4)
        }.padding()
    }
}
```

### A-Z scrubber
Invisible 32pt hit area on trailing edge. `DragGesture(minimumDistance: 0)` fires `onSelect` while dragging. `LetterPopupBox` centred overlay auto-hides 0.35 s after drag ends. Used in Items + Locations tabs. Components in `Components.swift`.

### Navigation route structs
```swift
struct ItemDetailRoute: Hashable { let id: String }
struct LocationDetailRoute: Hashable { let id: String }
struct TagDetailRoute: Hashable { let id: String }
```
Each tab registers `.navigationDestination(for:)` in its `NavigationStack`. Push routes, not views.

---

## 10. Theming

- **30 themes** ported from Homebox web app CSS.
- Each has `backgroundColor`, `accentColor`, `preferredColorScheme`.
- User picks in Settings → 5-column `LazyVGrid` of `ThemeSwatch` views.
- Selection persists in `UserDefaults`.
- Root `WindowGroup` applies `.tint(theme.current.accentColor)` + `.preferredColorScheme(...)`.
- `Color(h:s:l:)` lets us paste CSS HSL values straight in.

---

## 11. Things That Are Easy to Get Wrong

- **Don't add a `Color(hex:)` extension.** Theme.swift has the canonical non-failable one. Compiler error: `invalid redeclaration of 'init(hex:)'`.
- **Don't define custom `.glass` styles.** Native iOS 26 conflict.
- **Don't put background in a wrapping ZStack.** Use it as a modifier on the scrolling view.
- **Don't use `Stepper.labelsHidden()`.** Use `QuantityControl`.
- **Don't access `HBItem.labels` or `.tags` directly.** Use `effectiveLabels`.
- **Don't make `ThumbnailStore` (or similar) `ObservableObject`.** Causes full-list re-renders.
- **Don't reach for `/v1/entities`.** Doesn't exist in v0.25.x.
- **Don't add `"Bearer "` prefix.** Raw token only.
- **Don't commit `HomeboxCatalog.xcodeproj`.** Generated artifact.
- **Don't build locally.** Push and let CI handle it.
- **Don't add a 4th tab.** Tabs are frozen at 3. Settings = sheet. Add = FAB.
- **Don't put `SiteMenuPopover` inside a NavigationStack.** It's a ZStack overlay in ContentView at zIndex(100).
- **Don't build custom toast UI.** Post `.showToast` notification; ContentView handles it at zIndex(200).
- **Don't re-login to switch groups.** Use `store.setActiveGroup()` — it updates `activeGroupId`, which changes the `X-Tenant` header on `store.client`. No new token, no new credentials.
- **Don't replace the leading `ToolbarItemGroup` with a single `ToolbarItem`.** It needs both the search icon (first) and the HomeBoy pill.
- **Don't move the LocationListRow chevron back inside the depth lines.** It's intentionally at the far left for tap-target size.
- **Don't use `.simultaneousGesture` on scroll/list-nested buttons (like chips).** It causes gesture events to bleed through to elements underneath. Use direct `.onTapGesture` and `.onLongPressGesture` instead.
- **Don't allow tap-to-fullscreen on rows or grid cards.** Set `allowsFullScreen: false` on list/grid card thumbnails so tap gestures correctly trigger navigation or select-toggles. Keep fullscreen zoom viewer local to `ItemDetailView` only.
- **Don't use custom-styled buttons (like `.glass` style) inside standard system bottom bars.** System toolbars compress custom shapes, truncating labels into circles with ellipses (`...`). Use native borderless buttons instead.
- **Don't place custom status labels in the leading toolbar slot during edit states.** They truncate to `S...` on compact screens. Use native `.navigationTitle` and `.navigationBarTitleDisplayMode(.inline)` instead.

---

## 12. Open / Known Issues

- **No pagination.** `pageSize=1000` works for the current inventory. Build proper paging if >1000 items.
- **No token refresh.** 401 forces re-login via Settings. Wire `POST /v1/users/refresh` if this becomes annoying.
- **Group switching is fully functional** via `X-Tenant` header — same token, different data per group. All three tabs respond to `store.activeGroupId` changes and reload automatically.
- **Sendable warning** in `AddItemView.swift` (`theme.current.accentColor` in PhotosPicker label closure). Warning only — not an error with Swift 5.10. Fix by capturing `let accent = theme.current.accentColor` outside the closure when migrating to Swift 6.

---

## 13. Semantic Search Implementation

`ItemsListView` uses a **hybrid search** running asynchronously:
1. Fast synchronous substring `.contains()` on `allItems`.
2. If query ≥ 3 chars and 0 string matches: async background task computes `min(wordEmbedding distance, sentenceEmbedding distance)` via `NLEmbedding`.
3. Threshold: `1.15` (relaxed to handle single-word synonyms like seat/couch).

**Rule:** Do NOT revert to pure `sentenceEmbedding < 0.75` — single-word synonyms will break. Semantic embedding runs asynchronously so typing stays smooth.

---

## 14. AI Tag Suggestions

`AddItemView` uses `FoundationModels` (`LanguageModelSession`) to suggest tags as the user types the item name:
- 0.8 s debounce after name changes (≥ 4 chars).
- Prompts the model with available tag names + the item name.
- Parses comma-separated tag names from the response.
- Displays as horizontal scrolling "sparkle chips" between the name field and location row.
- **Gated on `SystemLanguageModel.default.isAvailable`** — silently skipped on devices without Apple Intelligence.

---

## 15. The User (Nitin)

- **Programming background**: Last did C++ in high school ~20 years ago. Knows variables, constants, if/else, for loops. Does **not** know: pointers, concurrency primitives, OOP beyond basics, Swift idioms, networking specifics.
- **Has ADHD**: Keep explanations **short and focused**. One concept per change. No walls of text.
- **Learns by doing**: Briefly explain the *why* as you make each change. Tie new concepts to what was just done. Use analogies.
- **Vibecoding workflow**: Nitin describes what he wants → LLM implements + commits + pushes → CI makes IPA → Nitin installs via AltStore → tests on iPhone → reports back. Loop.
- **Token-conscious**: Don't over-explain, don't re-read files you just wrote, don't repeat failed attempts.
- **Always end a HomeBoy session** with: https://github.com/nphil/homebox-catalog-ios/releases/tag/latest

---

## 16. Quick Reference

| Want to... | Look at |
|---|---|
| Understand the API surface | `CLAUDE.md` → "Homebox API surface" |
| See all Codable models | `HomeboxClient.swift` top |
| Show a toast | `NotificationCenter.default.post(name: .showToast, ...)` |
| Add to the tab bar | Don't. Add a FAB or a menu item instead. |
| Change themes | `Theme.swift` |
| Touch the add-item form | `AddItemView.swift` |
| Touch the items list | `ItemsListView.swift` |
| Touch a detail view | `ItemDetailView.swift` / `LocationDetailView.swift` |
| Add a reusable widget | `Components.swift` |
| Change CI/build | `.github/workflows/build.yml` |
| Change app metadata | `project.yml` |

**Repo:** https://github.com/nphil/homebox-catalog-ios  
**Latest IPA:** https://github.com/nphil/homebox-catalog-ios/releases/tag/latest
