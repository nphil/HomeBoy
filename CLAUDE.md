# HomeBoy (Homebox Catalog iOS) — Master Reference & Handoff Guide

## 1. Project Overview
Rapid-input iPhone app for cataloguing items into a self-hosted [Homebox](https://homebox.software/) v0.25.x instance. Connects directly to the Homebox v1 REST API — every Add posts to the server, every list view reflects what's actually there.
- **Target**: iOS 26, Liquid Glass UI, sideloaded via AltStore (no App Store).
- **Display name**: "HomeBoy"; repo + bundle id keep original `homebox-catalog` naming so AltStore reinstalls update in-place.
- **Tab Structure**: **3 tabs only: Items / Locations / Tags**. Settings is a sheet (not a tab), Add Item is a FAB sheet (not a tab).
- **Authentication**: `ContentView` displays `OnboardingView` when `!store.isAuthenticated` (covers the whole screen). Token is persisted in Keychain, and configuration (URL, theme, group ID) in `UserDefaults`.

---

## 2. Build, Versioning & CI/CD Pipeline
- **Never run Xcode locally** — use GitHub Actions (`.github/workflows/build.yml`).
- `xcodegen` reads `project.yml` and generates `HomeboxCatalog.xcodeproj` in CI (never committed).
- Unsigned IPA is generated with `CODE_SIGNING_ALLOWED=NO` — AltStore signs it during sideloading.
- Runner: `macos-15`, latest Xcode (currently Xcode 26 / iOS 26 SDK).
- **GitHub Workflow Scope**: Pushing `.github/workflows/*` requires the `workflow` scope on your `gh` token. Fix via: `gh auth refresh -h github.com -s workflow`.
- **Release Automation**: Tagging a release (e.g., `v1.0.11`) compiles the unsigned IPA, pushes a release to GitHub, and auto-updates `apps.json` (AltStore source manifest).
- **Apps Manifest**: `apps.json` lists all versioned releases. **Never edit by hand** — CI handles this. AltStore source URL: `https://raw.githubusercontent.com/nphil/HomeBoy/main/apps.json`.
- **Versioning Strategy**:
  - Incremental bug fixes: increment the patch version (e.g. `1.0.1` -> `1.0.2`).
  - Major feature drops/rebases: increment major or minor version (e.g. `1.1`, `2.0`).
- **Always push to BOTH branches**:
  - `main` (triggers CI) AND `claude/ipad-release-visibility-LWSdB` (feature branch).
  - Command: `git push origin HEAD:main && git push origin claude/ipad-release-visibility-LWSdB`.
- **CI Conflict Recovery**: If CI auto-bumps the version, resolve conflicts locally:
  `git fetch origin main && git rebase origin/main && git push origin HEAD:main && git push origin claude/ipad-release-visibility-LWSdB --force-with-lease`.
- **Ending a Session**: Always end your message by pointing the user to: `https://github.com/nphil/HomeBoy/releases/tag/latest` (or specific version tag if pushed).

---

## 3. Tab, Navigation & Global Layout
- **SiteMenuPopover**: ZStack overlay in `ContentView` at `zIndex(100)`. Never place it inside a `NavigationStack`.
  - Toggled by tapping the HomeBoy leading pill (shippingbox.fill + "HomeBoy" + chevron.down) in the toolbar of any tab.
  - Animation: `popoverSpring = .spring(duration: 0.25, bounce: 0.22)`. Scale/fade transition: `.scale(scale: 0.5, anchor: .topLeading).combined(with: .opacity)`.
  - Group switching uses a custom `EnvironmentKey` (`ShowSiteMenuKey` in `HomeboxCatalogApp.swift`) that passes a `Binding<Bool>` to all tabs.
  - Tapping an inactive group switches the active group scoped by `X-Tenant`, clears caches, posts a `.showToast` notification, and updates the local state.
- **Toast Notifications**: Bottom capsule overlay at `zIndex(200)` in `ContentView` with a 2.5s auto-dismiss.
  - Trigger by posting: `NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Your message"])`.
  - Notification names declared in `SiteMenuPopover.swift`.
- **Global Search**: `globalSearchQuery: String` is a `@Binding` passed from `ContentView` down to all tab views.
  - Each tab holds `@State private var isSearchActive = false` and applies search via `.modifier(ConditionalSearchable(text: $globalSearchQuery, isPresented: $isSearchActive, prompt: "…"))`.
  - Tapping the magnifying glass in the toolbar sets `isSearchActive = true`, displaying the native search bar.

---

## 4. Unified File Map & Developer Watch-outs

| File | Purpose | Critical Watch-outs & Gotchas |
|---|---|---|
| [HomeboxCatalogApp.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/HomeboxCatalogApp.swift) | Main entry point. Defines `ContentView`, `ShowSiteMenuKey` environment key, and handles toast overlay. | App launches `.task` to refresh groups, locations, and item counts concurrently. |
| [SiteMenuPopover.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/SiteMenuPopover.swift) | Group-switching popover layout & animation. Declares `.showToast` and `.showSettings` notifications. | Stays in the view hierarchy to preserve state. Tapping settings fires notification. |
| [OnboardingView.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/OnboardingView.swift) | Login form + server configuration. | Entirely replaces `ContentView` tabs when `!store.isAuthenticated`. |
| [Theme.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/Theme.swift) | Defines 30 HSL-based themes. | `Color(hex:)` is **non-failable** — never write `?? someColor`. Solid backgrounds only. |
| [Models.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/Models.swift) | Main `@MainActor` `HomeboxStore` state manager. | Handles API requests scoped by `activeGroupId` using computed clients. DFS-flattens location trees. |
| [HomeboxClient.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/HomeboxClient.swift) | Raw async/await HTTP client & Codable models. | `X-Tenant` header handles multi-tenancy. Bearer token has **no** `"Bearer "` prefix. |
| [Keychain.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/Keychain.swift) | Keyring storage wrapper. | Token-only persistence. Password is never saved. |
| [PhotoSource.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/PhotoSource.swift) | Camera/photo helpers, handles downscaling to keep JPEGs < 300KB. | Downscaling maxDimension = 1600. |
| [AddItemView.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/AddItemView.swift) | Compact entry form. debounced Apple Intelligence tag suggestion. | Compact VStack (no ScrollView) to fit screen + keyboard. Pre-seeded parent ID for components. |
| [ItemsListView.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/ItemsListView.swift) | Items tab. Lists, tiles, filters, sorting, bulk actions, and hybrid semantic search. | Uses `ThumbnailStore` for performance. Holds `SortOption` and view state. |
| [LocationsTabView.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/LocationsTabView.swift) | Tree navigation list of locations. | Pinned left expand/collapse chevrons with 36px wide touch target. |
| [LocationDetailView.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/LocationDetailView.swift) | Detail list of items in location, edit/delete sheet. | Registers nav route destinations. |
| [ItemDetailView.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/ItemDetailView.swift) | Item detail view, sub-components cards, maintenance cards, and full-screen image cover. | `allowsFullScreen: true` is set on detail image loading only. |
| [ArchivedItemsView.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/ArchivedItemsView.swift) | Archived items list accessible from Settings -> Library. | **The ONLY place `SwipeRevealRow` is allowed.** Handles bulk unarchiving. |
| [Components.swift](file:///Users/nitin/Documents/antigravity/HomeBoy/Components.swift) | Custom UI elements (`DescriptionField`, `QuantityControl`, `AlphabetIndexBar`, etc.) | `ThumbnailStore` is a plain `@MainActor class` (NOT ObservableObject). |
| [project.yml](file:///Users/nitin/Documents/antigravity/HomeBoy/project.yml) | xcodegen project configuration. | Spec file. Any new `.swift` file in root is auto-included. |
| `apps.json` | AltStore source manifest. | CI patches automatically. **Never edit by hand.** |

---

## 5. Critical Architecture Rules (Never Violate)
1. **Button Styles**: Do NOT define custom `.glass` or `.glassProminent` button styles — they conflict with native styles in the iOS 26 SDK, causing `ambiguous use of 'glass'` errors.
2. **Background Pattern**: Always use `.background(theme.current.backgroundColor.ignoresSafeArea())` as a modifier directly on the scrollable container (List/ScrollView/Form). Never wrap the structure in a `ZStack { background; content }` as it breaks safe area rendering and width calculation.
3. **Scroll Bars**: Apply `.scrollIndicators(.hidden)` to every single `ScrollView`, `List`, and `Form` to ensure scroll bars are completely invisible everywhere.
4. **Steppers**: `Stepper(...).labelsHidden()` is broken in iOS 26. Always use the custom `QuantityControl` component from `Components.swift`.
5. **JSON Models**: Recursive codable structs fail compilation. `HBTreeItem` must remain a `final class`.
6. **Authorization Header**: Send the raw token string in the `Authorization` header. Do NOT prefix it with `"Bearer "`.
7. **Item Tags**: Always use `item.effectiveLabels` (computed as `labels ?? tags`). Never access `.labels` or `.tags` directly, as Homebox api naming varies across versions.
8. **Tag Filter Nil Guard**: If `item.effectiveLabels` is `nil`, the tag filtering block must return `true`. This trusts the server-side `?labels=` query pre-filtering. Returning `false` will wipe the list.
9. **Deduplicated Row Rendering**: Never make `ThumbnailStore` (or other per-row loading stores) an `ObservableObject`. Keep it a plain `@MainActor class` and use local `@State` to avoid full-list re-renders when rows load.
10. **Description Fields**: Never place inline TextFields or TextEditors for descriptions/notes in add, create, or edit forms. Use `DescriptionField` from `Components.swift`. It renders a compact row that opens a full-screen `DescriptionEditorSheet` (with a draft pattern), preventing keyboard layout issues.
11. **Swipe Actions**: Do NOT use `SwipeRevealRow` in main item list scroll views due to gesture conflicts. Use native `.swipeActions` in main lists. Limit `SwipeRevealRow` usage strictly to standalone, non-nested lists (such as `ArchivedItemsView`).
12. **Parent IDs**: `HBItemUpdate.parentId` MUST be a nullable `String?` (not `String`). Homebox's Go backend expects a UUID; sending an empty string `""` crashes the parser with a `500 Internal Server Error`. Sending JSON `null` (accomplished by setting `parentId = nil` in Swift) decodes to `uuid.Nil` which safely clears the parent relationship.
13. **Bulk Action Results**: Never swallow errors silently in bulk commands (delete, archive, unarchive) via empty `catch` blocks. Always post a toast notifying the user of the success count and the last error message.
14. **Sendable Closures**: Do not directly capture `@MainActor` variables (like `theme.current.accentColor`) in `@Sendable` closures (e.g., PhotosPicker label). Capture it in a local constant first: `let accentColor = theme.current.accentColor`.
15. **Filter Chips Gestures**: Place filter panels *outside* and *above* scroll views (as siblings in a wrapping VStack). Use `.contentShape(Capsule())` and direct `.onTapGesture` / `.onLongPressGesture` rather than `Button` or `.simultaneousGesture` to prevent touches from bleeding through to cells below.
16. **Select Mode Buttons**: Use `ToolbarItemGroup(placement: .bottomBar)` with native borderless buttons for select mode. Do NOT use custom button shapes or `.ultraThinMaterial` / glass styling. Pinned status headers should use `.navigationTitle` and `.navigationBarTitleDisplayMode(.inline)` to avoid `S...` truncation.
17. **Full-screen Image Covers**: Row/cell image thumbnails must set `allowsFullScreen: false`. Limit full-screen image zooming to `ItemDetailView` only.

---

## 6. Key Subsystems & Core Implementations

### Multi-Tenant Group Switching (X-Tenant Header)
Homebox isolates groups via the `X-Tenant: <groupUUID>` HTTP header.
1. `HomeboxClient` appends the `X-Tenant` header dynamically if `tenantId` is set.
2. `HomeboxStore.client` is a computed property that automatically binds `activeGroupId` as the `tenantId`.
3. Updating `store.activeGroupId` automatically scopes all future network requests. No re-login or credentials swap is required.
4. Each tab listens for group changes via `.onChange(of: store.activeGroupId)` to wipe local caches and reload.
5. Inactive group counts on the popover use `store.cachedGroupStats`, populated concurrently during app start and popover display by `refreshAllGroupStats()`.

### Hybrid Semantic Search
`ItemsListView` implements a hybrid search running asynchronously:
1. Fast synchronous substring check `.contains()` on `allItems`.
2. If query length is $\ge 3$ characters and substring yields 0 matches: an async background task calculates semantic distance.
3. It evaluates `min(wordEmbedding, sentenceEmbedding)` distance using `NLEmbedding`.
4. **Threshold**: `1.15` (relaxed to accommodate single-word synonyms). Do NOT restrict search to pure sentence embeddings or lower thresholds.

### AI Tag Suggestions
`AddItemView` queries the local OS `FoundationModels` (`LanguageModelSession`) to suggest tags on the fly:
- Triggered by name updates (length $\ge 4$ characters) debounced by 0.8s.
- Feeds available tag names along with the item name to the model.
- Parses comma-separated names from the result.
- Displays as inline sparkle chips. Silently skipped if Apple Intelligence is unavailable.

### Sub-Items / Component Cards
Items link parents and children via `parentId`.
- The child detail screen displays a "Part of: X" navigation link.
- The parent detail screen shows a "Components" `GlassCard` listing child items (fetched via `listItems(parentIds: [itemId])`).
- Tapping "Add component" launches `AddItemView` with pre-filled parent data, hiding the location picker (location is automatically inherited from the parent).

### Maintenance Logs
Real v0.25.x API integration via endpoints under `/v1/items/{id}/maintenance` and `/v1/maintenance/{id}`:
- `HBMaintenanceEntry` contains `id`, `name`, `description`, completed `date` (ISO 8601), `scheduledDate` (ISO 8601), and `cost`.
- The detail view displays a "Maintenance" `GlassCard` sorting completed logs first, then scheduled.
- `MaintenanceRow` indicates status via a green (completed) or orange (scheduled) dot.
- Adding/Editing uses `MaintenanceEntrySheet` with date/cost toggles.

### Archive Feature
Archiving is handled by flipping `archived: Bool` on `HBItemUpdate` and PUTing.
- Archived items are filtered out by default on the server unless `includeArchived=true` is sent.
- Accessible via the detail view ••• menu, bulk select bar ("Archive"), and from Settings -> Library -> Archived Items (`ArchivedItemsView`).

---

## 7. API Endpoints Reference (v0.25.x)
All requests are scoped to `${serverURL}/api/v1/` with raw `Authorization` tokens.

| Method | Endpoint | Description / Body |
|---|---|---|
| `POST` | `/users/login` | **Form-encoded** (not JSON) credentials. |
| `GET` | `/groups/all` | Retrieves user's groups. |
| `GET` | `/locations` | Retrieves flat location list. |
| `GET` | `/locations/tree?withItems=false` | Retrieves recursive location tree (`HBTreeItem`). |
| `POST` | `/locations` | Creates location: `{ name, parentId?, description }`. |
| `GET` | `/items?page=1&pageSize=1000` | Retrieves item list. Can pass `parentIds` and `includeArchived`. |
| `GET` | `/items?page=1&pageSize=1` | Cheaply fetches the total item count. |
| `GET` | `/items?labels=<id>` | Filters items by tag ID. Repeat parameters for multiple tags. |
| `POST` | `/items` | Creates item: `{ name, quantity, description, locationId, tagIds, parentId? }`. |
| `GET/PUT/DELETE` | `/items/{id}` | Item CRUD. PUT requires building `HBItemUpdate` from the detail struct. |
| `POST` | `/items/{id}/attachments` | Hand-rolled multipart image upload. |
| `DELETE` | `/items/{id}/attachments/{aid}` | Deletes primary or secondary photo attachments. |
| `GET` | `/items/{id}/maintenance` | Fetches maintenance entries (`[HBMaintenanceEntry]`). |
| `POST` | `/items/{id}/maintenance` | Creates maintenance entry (`HBMaintenanceCreate`). |
| `PUT` | `/maintenance/{id}` | Updates maintenance entry. |
| `DELETE` | `/maintenance/{id}` | Deletes maintenance entry. |
| `GET/POST/PUT/DELETE` | `/tags` | Tag CRUD. payload: `TagCreatePayload`. |

---

## 8. User Collaboration Guidelines (Nitin)
- **ADHD Style**: Keep all responses and explanations **extremely concise, short, and focused**. Bullet points are preferred. Never post walls of text.
- **Background**: Nitin has a basic understanding of programming concepts (variables, conditions, loops from high school C++ ~20 years ago) but lacks experience with concurrent programming, advanced Swift, network protocols, or pointers.
- **Workflow (Vibecoding)**:
  1. Nitin requests a feature or adjustment.
  2. Implement changes, commit, and push to BOTH branches.
  3. GitHub Actions builds the unsigned IPA.
  4. Nitin sideloads via AltStore on his iPhone, tests, and reports back.
  5. Repeat.
- **Concept Explanations**: Provide brief, simple analogies or high-level explanations of *why* a change was made, connecting new concepts directly to files he is familiar with.
- **Token Efficiency**: Never repeat explanations, avoid unnecessary file reads of unmodified code, and immediately compress memory when requested.

---

## 9. View Navigation Flow

```
App Entry
├── ContentView (if authenticated)
│   ├── TabView
│   │   ├── Tab 0: ItemsListView (NavigationStack)
│   │   │   ├── → ItemDetailView (push via ItemDetailRoute)
│   │   │   │   ├── EditItemSheet (sheet)
│   │   │   │   │   ├── LocationPickerSheet
│   │   │   │   │   ├── TagPickerSheet
│   │   │   │   │   └── CameraSheet / PhotosPicker
│   │   │   │   ├── FullScreenImageView (fullScreenCover)
│   │   │   │   ├── AddItemView (sheet, "Add component" → sub-item)
│   │   │   │   └── MaintenanceEntrySheet (sheet)
│   │   │   ├── → LocationDetailView (push via LocationDetailRoute)
│   │   │   ├── AddItemView (sheet, from FAB)
│   │   │   │   ├── LocationPickerSheet
│   │   │   │   ├── TagPickerSheet
│   │   │   │   └── CameraSheet
│   │   │   ├── BulkEditSheet (sheet, from multi-select)
│   │   │   │   ├── LocationPickerSheet
│   │   │   │   └── TagPickerSheet
│   │   │   ├── LocationPickerSheet (filter)
│   │   │   └── TagPickerSheet (filter)
│   │   │
│   │   ├── Tab 1: LocationsTabView (NavigationStack)
│   │   │   ├── → LocationDetailView (push)
│   │   │   │   ├── EditLocationSheet (sheet)
│   │   │   │   │   └── LocationPickerSheet
│   │   │   │   └── → LocationDetailView (push, recursive)
│   │   │   ├── → ItemDetailView (push)
│   │   │   └── CreateLocationSheet (sheet, from FAB)
│   │   │       └── LocationPickerSheet
│   │   │
│   │   └── Tab 2: TagsTabView (NavigationStack)
│   │       ├── → TagDetailView (push)
│   │       │   ├── TagEditSheet (sheet)
│   │       │   └── → ItemDetailView (push)
│   │       ├── → ItemDetailView (push)
│   │       └── TagEditSheet (sheet, from FAB, mode: .create)
│   │
│   ├── SiteMenuPopover (zIndex 100, overlay)
│   │   └── SettingsView (sheet)
│   │       └── ArchivedItemsView (NavigationLink push)
│   └── Toast overlay (zIndex 200)
│
└── OnboardingView (if not authenticated)
```
