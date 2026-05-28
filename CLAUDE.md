# HomeBoy — iOS 26 Homebox catalog client

**Stack**: SwiftUI iOS 26 Liquid Glass, sideloaded IPA (no App Store). 3 tabs: Items / Locations / Tags. Settings + Add Item = sheets.

## Build & CI
- **Push to main only**: `git push origin HEAD:main` — never push to a feature branch.
- **CI conflict** (CI auto-bumps version after every push): `git fetch origin main && git rebase origin/main && git push origin HEAD:main`
- Push to main → unsigned IPA built → GitHub release created → `apps.json` patched. No manual steps.

## Key files (non-obvious)
| File | Notes |
|---|---|
| `HomeboxClient.swift` | All Codable models + HTTP client. `url()` prepends `/api` — pass `v1/...` paths. Maintenance at `/v1/items/{id}/maintenance`. |
| `Models.swift` | `HomeboxStore` @MainActor. `localDB: LocalDatabase` for offline. `isOffline = isOfflineModeEnabled \|\| !isConnectedToNetwork`. |
| `Components.swift` | `.floatingCardCover`, `QuantityControl`, `ThumbnailStore`, `SwipeRevealRow`, `GlassCard`, `DescriptionField`. |
| `NotificationManager.swift` | `MaintenanceCadence` is a **struct** (not enum). Set as UNUserNotificationCenterDelegate in App `init()`. |
| `LocalDatabase.swift` | JSON-backed offline store. `PendingMaintenanceOp.asDisplayEntry()` → `HBMaintenanceEntry` with `"pending-"` id prefix. |
| `LocationDetailView.swift` | Defines `ItemDetailRoute` + `LocationDetailRoute` used by NavigationStack. |

## Architecture rules

**Modals** — `.floatingCardCover(isPresented:onDismiss:)` for ALL add/edit sheets, never `.sheet`. Sheet content must NOT set `.background(...)` — let `FloatingCardContainer`'s tinted-glass `presentationBackground` show through.

**iOS 26 broken things**:
- Use `QuantityControl` not `Stepper` — `Stepper.labelsHidden()` is broken.
- No custom `.glass`/`.glassProminent` ButtonStyle names — conflicts with native iOS 26 styles → compiler error.
- `HBTreeItem` must be `final class` — recursive Codable struct won't compile.

**Background**: `.background(theme.current.backgroundColor.ignoresSafeArea())` on ScrollView/List/Form. Never `ZStack { bg; content }`.

**Scroll**: `.scrollIndicators(.hidden)` on every ScrollView/List/Form.

**Items**: Always `item.effectiveLabels` (`= labels ?? tags`), never `.labels`/`.tags`. In local tag filters: if `effectiveLabels == nil` return `true` (server already filtered — returning `false` wipes the list).

**`ThumbnailStore`**: plain `@MainActor class`, NOT ObservableObject — causes full-list re-renders on every thumbnail load.

**`SwipeRevealRow`**: only in `ArchivedItemsView`. Use `.swipeActions` everywhere else — gesture conflicts.

**Filter chips**: filter VStack must be *sibling above* the ScrollView, never inside it. Use `.contentShape(Capsule())` + `.onTapGesture` — not `Button` (touch bleeds through to cells).

**Group switching**: every tab needs `.onChange(of: store.activeGroupId)` to wipe caches + reload.

**Sendable closures**: capture `@MainActor` values before use — `let accentColor = theme.current.accentColor`.

**`Color(hex:)`** is non-failable — never `?? someColor` after it.

**Semantic search**: threshold `1.15` with `min(wordEmbedding, sentenceEmbedding)`. Don't change it.

## API gotchas

- **Login**: `application/x-www-form-urlencoded`, not JSON.
- **Bearer token**: raw string — no `"Bearer "` prefix.
- **`HBItemUpdate.parentId`**: `String?` only — sending `""` → Go UUID parse error → HTTP 500.
- **`/v1/entities`**: unreleased (main-branch only) — do NOT use. v0.25.x: `/v1/items`, `/v1/locations`, `/v1/items/{id}/maintenance`.
- **Maintenance cost**: server `json:"cost,string"` — must be encoded as a JSON string. `HBMaintenanceCreate.encode(to:)` handles this.
- **`listTags()`**: returns `[HBTag]` OR `{ items: [HBTag] }` — client tries both shapes.

## Patterns

**Toast**: `NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "text"])`

**`showSiteMenu`**: `ShowSiteMenuKey` EnvironmentKey as `Binding<Bool>`. Toggle: `showSiteMenu.wrappedValue.toggle()` inside `withAnimation`.

**`DescriptionEditorSheet`**: uses `@State private var draft` — "Done" commits, "Cancel" discards. Never bind `TextEditor` directly to a parent `@Binding`.

**AI tag suggestions**: always guard `SystemLanguageModel.default.isAvailable` before use.
