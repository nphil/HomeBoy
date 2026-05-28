# HomeBoy (Homebox Catalog iOS) — Project Reference

## 1. Project Overview
Rapid-input iPhone app for cataloguing items into a self-hosted [Homebox](https://homebox.software/) v0.25.x instance. Connects directly to the Homebox v1 REST API — every Add posts to the server, every list view reflects what's there.
- **Target**: iOS 26, Liquid Glass UI, sideloaded via AltStore (no App Store).
- **Display name**: "HomeBoy"; bundle id keeps `homebox-catalog` so AltStore reinstalls update in-place.
- **Tab Structure**: 3 tabs only — Items / Locations / Tags. Settings = sheet (not a tab). Add Item = FAB sheet (not a tab).
- **Auth**: `OnboardingView` covers the screen when `!store.isAuthenticated`. Token in Keychain. `X-Tenant` header scopes requests to the active group — no re-login needed to switch groups.

---

## 2. Build & CI
- **Never run Xcode locally** — use GitHub Actions (`.github/workflows/build.yml`).
- Unsigned IPA (`CODE_SIGNING_ALLOWED=NO`). Runner: `macos-15`, Xcode 26 / iOS 26 SDK.
- **Push to main only**: `git push origin HEAD:main` — do NOT push to any feature branch.
- **CI conflict recovery** (CI auto-bumps version after each push): `git fetch origin main && git rebase origin/main && git push origin HEAD:main`
- **Release**: Every push to `main` automatically builds an IPA, creates a GitHub release, and patches `apps.json`. No manual steps needed.
- Pushing `.github/workflows/*` needs `workflow` scope: `gh auth refresh -h github.com -s workflow`.
- **Versioning**: patch increment for bug fixes (`1.0.1`), minor/major for feature drops (`1.1`, `2.0`).

---

## 3. File Map

| File | Purpose |
|---|---|
| `HomeboxCatalogApp.swift` | `@main`. `ContentView`: 3-tab ZStack + `SiteMenuPopover` (zIndex 100) + toast (zIndex 200). `ShowSiteMenuKey` EnvironmentKey passes `Binding<Bool>` to tabs. |
| `SiteMenuPopover.swift` | Group-switching popover. Declares `.showToast` and `.showSettings` notification names. |
| `OnboardingView.swift` | Login form — replaces tabs when `!store.isAuthenticated`. |
| `Theme.swift` | 30 HSL themes. `Color(hex:)` is non-failable. Solid backgrounds only. |
| `Models.swift` | `HomeboxStore` (@MainActor): auth, locations, groups, group stats. DFS-flattens location tree. |
| `HomeboxClient.swift` | Async HTTP client + all Codable models. `tenantId` → `X-Tenant` header. Maintenance methods: `listMaintenance`, `createMaintenance`, `updateMaintenance`, `deleteMaintenance`. |
| `Keychain.swift` | Token-only Keychain wrapper. |
| `PhotoSource.swift` | Camera sheet + JPEG downscaler (maxDimension 1600). |
| `AddItemView.swift` | Compact VStack add form (no ScrollView — must fit with keyboard open). Accepts `parentId/parentName/parentLocationId` for sub-items. AI tag suggestions via FoundationModels (0.8s debounce). |
| `ItemsListView.swift` | Items tab: list/tile, hybrid semantic search, filter panel, sort, bulk actions, archive chip. |
| `LocationsTabView.swift` | Locations tree tab. Expand/collapse chevrons pinned left with 36px touch target. |
| `LocationDetailView.swift` | Location detail + edit/delete. Defines `ItemDetailRoute`, `LocationDetailRoute`. |
| `ItemDetailView.swift` | Item detail: archive/unarchive (••• menu), sub-items "Components" card, "Maintenance" card, full-screen image. Contains `MaintenanceRow`, `MaintenanceEntrySheet` (floatingCardCover). |
| `NotificationManager.swift` | `NotificationManager` singleton (UNUserNotificationCenterDelegate). `MaintenanceCadence` enum. Schedules/cancels local notifications for maintenance; handles "Mark as Done" notification action by calling API + scheduling next occurrence. Set as delegate in `HomeboxCatalogApp.init()`. |
| `ArchivedItemsView.swift` | Settings → Library → Archived Items. **Only place `SwipeRevealRow` is allowed.** Bulk unarchive. |
| `Components.swift` | `FloatingCardContainer`/`.floatingCardCover`, `DescriptionField`/`DescriptionEditorSheet`, `QuantityControl`, `ThumbnailStore`, `ItemListRowContent`, `SwipeRevealRow`, `GlassCard`, `AlphabetIndexBar`. |
| `project.yml` | xcodegen spec. Any `.swift` in root is auto-included. |
| `apps.json` | AltStore source manifest. CI patches on tagged release. |

---

## 4. Critical Architecture Rules

1. **Push to main only** — always `git push origin HEAD:main`. Never push to any other branch.

2. **Add/Create Modals** — use `.floatingCardCover(isPresented:onDismiss:)` from `Components.swift`, **NOT `.sheet`**. SwiftUI sheets anchor to the screen bottom and get clipped by device rounded corners. `FloatingCardContainer` wraps content in `.fullScreenCover` + `.presentationBackground(.clear)`, rendering a rounded card inset on all four sides over a dimmed backdrop. This applies to all add/edit sheets including `MaintenanceEntrySheet`.

3. **`HBItemUpdate.parentId` must be `String?`** — sending `""` fails Go UUID parsing → HTTP 500. Send `nil` (encodes as JSON `null` → `uuid.Nil`) to clear the parent.

4. **Bearer token has no prefix** — raw token string in `Authorization` header. No `"Bearer "`.

5. **Use `item.effectiveLabels`** (= `labels ?? tags`) — never `.labels` or `.tags` directly. API naming varies across versions.

6. **Tag filter nil guard** — if `effectiveLabels == nil`, return `true`. The server already filtered server-side; returning `false` wipes the list.

7. **`ThumbnailStore` is NOT ObservableObject** — plain `@MainActor class`. Making it ObservableObject causes full-list re-renders on every thumbnail load.

8. **`DescriptionField` for all notes/description inputs** — never inline `TextEditor` in compact forms. `DescriptionField` opens `DescriptionEditorSheet` (draft pattern) as a full-screen sheet.

9. **`SwipeRevealRow` only in `ArchivedItemsView`** — gesture conflicts in main scroll views. Use `.swipeActions` in all other lists.

10. **`QuantityControl` not `Stepper`** — `Stepper(...).labelsHidden()` is broken in iOS 26.

11. **No custom `.glass`/`.glassProminent` button styles** — ambiguous with native iOS 26 styles → compiler error.

12. **Background pattern** — `.background(theme.current.backgroundColor.ignoresSafeArea())` directly on ScrollView/List/Form. Never `ZStack { bg; content }` — breaks safe area and width calculation.

13. **`.scrollIndicators(.hidden)`** on every ScrollView, List, and Form.

14. **`HBTreeItem` must be `final class`** — recursive Codable structs won't compile.

15. **`Color(hex:)` is non-failable** — never write `?? someColor` after it.

16. **Filter chips** — place filter panel VStack *outside and above* the ScrollView (sibling, not child). Use `.contentShape(Capsule())` + `.onTapGesture`/`.onLongPressGesture` — not `Button` — to prevent touch bleed through to cells.

17. **Select mode bottom bar** — `ToolbarItemGroup(placement: .bottomBar)` with native borderless buttons. No custom shapes, no glass styling. Use `.navigationTitle` + `.inline` for selection headers to prevent `S...` truncation.

18. **Full-screen images** — `allowsFullScreen: false` on all list/grid thumbnails. Only `ItemDetailView` enables full-screen zoom.

19. **Semantic search threshold: 1.15** with `min(wordEmbedding, sentenceEmbedding)`. Do not revert to pure sentence embedding or lower thresholds — single-word synonyms break.

20. **Each tab has `.onChange(of: store.activeGroupId)`** to wipe local caches and reload on group switch. Wire this in any new tab too.

21. **Sendable closures** — capture `@MainActor` values before use: `let accentColor = theme.current.accentColor`.

22. **Bulk action errors** — never swallow with empty `catch`. Toast the success count + last error.

---

## 5. Key Gotchas

- **Login is form-encoded** (`application/x-www-form-urlencoded`), not JSON.
- **`listTags()` dual-shape** — Homebox returns tags as either `[HBTag]` or `{ items: [HBTag] }`. Client tries both shapes.
- **`HBItemDetail` fields are mostly optional** except `id` and `name`. Always nil-check.
- **`archived: Bool`** on `HBItemUpdate` is a real v0.25.x field — not an unreleased entities API.
- **`/v1/entities` endpoints are unreleased** (main branch only) — do NOT use. v0.25.x uses `/v1/items` + `/v1/locations`. Maintenance is at `/v1/items/{id}/maintenance` (v0.25.x confirmed).
- **AI tag suggestions** — check `SystemLanguageModel.default.isAvailable` before use; silently skip if unavailable.
- **`DescriptionEditorSheet` draft pattern** — `@State private var draft` buffers edits; "Done" commits, "Cancel" discards. Never bind `TextEditor` directly to a parent binding.
- **`showSiteMenu` flows via `ShowSiteMenuKey` EnvironmentKey** as `Binding<Bool>`. Toggle with `showSiteMenu.wrappedValue.toggle()` inside `withAnimation`.
- **Toast from anywhere**: `NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Done!"])`
