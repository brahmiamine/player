# Scalable Catalog Cache Implementation Plan

> **For agentic workers:** Use the host's available task-by-task implementation workflow. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Streamia reopen large Xtream/M3U libraries quickly without rebuilding the full catalogue in memory before navigation becomes usable.

**Architecture:** Replace the monolithic JSON cache with an indexed SQLite catalogue store. Persist categories and media entries independently, expose lightweight catalogue metadata/counts, and load only the active category in pages while keeping the existing Compose screens and `Catalog` API compatible. Provider refresh remains manual for Xtream and writes the new database atomically.

**Tech Stack:** Kotlin, Android SQLite (`SQLiteOpenHelper`), coroutines, Jetpack Compose TV, JUnit, GitHub Actions.

## Global Constraints

- Xtream must remain local-first: reopening a cached Xtream profile must not contact the provider.
- Manual `Actualiser` remains the operation that refreshes Xtream catalogue data.
- Existing favorites, history, category ordering and moved-entry behavior must remain compatible.
- Live playback must be usable before Movies/Series are hydrated.
- Large `Tout` and category views must not materialize the entire provider catalogue at once.
- Existing `LazyColumn`/`LazyVerticalGrid` rendering remains in place.
- A failed refresh must not delete the last valid local catalogue.

---

### Task 1: Indexed SQLite catalogue store

**Files:**
- Create: `app/src/main/java/fr/streamia/tv/data/CatalogDatabase.kt`
- Modify: `app/src/main/java/fr/streamia/tv/data/CatalogCache.kt`
- Modify: `app/src/main/java/fr/streamia/tv/domain/XtreamModels.kt`
- Test: `app/src/test/java/fr/streamia/tv/domain/CatalogMetadataTest.kt`

**Interfaces:**
- Consumes: `Catalog`, `MediaCategory`, `MediaEntry`, `MediaType`.
- Produces: SQLite-backed save/load, total counts, per-category counts and paged entry reads.

- [x] Add a failing unit test proving `Catalog.count()` and category counts can represent rows not currently materialized in `entries`.
- [x] Implement catalogue metadata in `Catalog` without changing existing full-catalog behavior.
- [x] Create SQLite schema with profile-scoped category/entry tables and indexes on `(profile_id,type,category_id)`, `(profile_id,type,number)` and media key fields.
- [x] Make catalogue writes transactional and preserve the old cache until the SQLite transaction succeeds.
- [x] Verify existing catalogue/domain tests remain green.
- [x] Add `Catalog.withMaterializedEntries` to merge one loaded page into the lightweight catalogue without dropping metadata (`CatalogMetadataTest.pagedRowsMergeByKeyAndPreserveMetadata`).

### Task 2: Fast startup and lazy category hydration

**Files:**
- Modify: `app/src/main/java/fr/streamia/tv/data/XtreamRepository.kt`
- Modify: `app/src/main/java/fr/streamia/tv/ui/StreamiaViewModel.kt`
- Modify: `app/src/main/java/fr/streamia/tv/ui/StreamiaApp.kt`
- Modify: `app/src/main/java/fr/streamia/tv/ui/BrowserScreen.kt`
- Test: `app/src/test/java/fr/streamia/tv/domain/CatalogMetadataTest.kt`

**Interfaces:**
- Consumes: persisted browser location and playback session entry.
- Produces: lightweight startup catalogue plus category page loading callbacks.

- [x] Reopen cached profiles from SQLite metadata/categories without hydrating Movies/Series (`CatalogCache.load` now calls `database.loadLightweight`; `XtreamRepository.cachedCatalog`/`openProfile` were already reading through it, no change needed there).
- [x] Restore the last Live category first when resuming playback — already handled: `BrowserScreen`'s `LaunchedEffect(selectedType, selectedCategoryId)` now calls `StreamiaViewModel.ensureCategoryLoaded` as soon as the initial (restored) category is selected, and the whole Live section is hydrated proactively right after profile open (see Task 3) so zapping/EPG/channel-number-jump never race a lazy category fetch for Live specifically.
- [x] Load a browser category in bounded pages and merge pages into the presentation catalogue — `StreamiaViewModel.ensureCategoryLoaded`/`loadCategoryPage` call `XtreamRepository.loadCategoryPage` and merge via `Catalog.withMaterializedEntries`, applied to both `rawCatalog` and the customized `catalog`.
- [x] Trigger the next page when the TV list/grid approaches the end — `BrowserScreen`'s `LiveChannelList`/`PosterGrid` watch `LazyListState`/`LazyGridState.layoutInfo` and call `onLoadMoreInCategory` → `StreamiaViewModel.loadMoreInCategory` within `LOAD_MORE_THRESHOLD` items of the materialized end.
- [x] Keep counts accurate using database metadata rather than loaded-list size — `BrowserScreen`'s `CategoryRail` and `PosterGrid` header now read `Catalog.countIn`/`count` instead of `entriesIn(...).size`; `OrganizerScreen`'s per-category count fixed the same way.

### Task 3: Remove resolved full-catalog duplication and preserve user customization

**Files:**
- Modify: `app/src/main/java/fr/streamia/tv/data/CatalogCache.kt`
- Modify: `app/src/main/java/fr/streamia/tv/data/XtreamRepository.kt`
- Modify: `app/src/main/java/fr/streamia/tv/ui/StreamiaViewModel.kt`

**Interfaces:**
- Consumes: `UserLibrarySnapshot` deltas.
- Produces: one supplier catalogue store plus existing lightweight user-library persistence.

- [x] Stop writing a second resolved copy of the full provider catalogue (`CatalogCache.saveResolved`/`loadResolved` now just delete the legacy file and return `null`).
- [x] Apply category order/moved-entry deltas only to the rows/pages being presented — `applyUserLibraryToCatalog` already only touches `catalog.entries`, i.e. whatever is currently materialized; `StreamiaViewModel`'s new `loadCategoryPage`/`ensureSectionLoaded` re-run `XtreamRepository.customizedCatalog` after every merge so newly loaded rows get the same treatment.
- [x] Preserve organizer behavior by hydrating the requested section before organizer operations — `StreamiaViewModel.showOrganizer()` now calls `ensureSectionLoaded` for Live/Movie/Series before switching screens, since the organizer lets the user reorder/move entries in any of the three types.

**Known follow-up (not done in this PR):** search (`SearchScreen`) now queries SQLite directly (`XtreamRepository.search` → `CatalogDatabase.search`) instead of `Catalog.search`, since the in-memory index only covers materialized entries — this was a correctness gap the original plan didn't call out explicitly, fixed alongside Task 2/3. EPG (`EpgScreen`), zapping fallback and the numeric channel-jump (`PlayerScreen`) all rely on the Live section being fully materialized; this is covered by the proactive `ensureSectionLoaded(Live)` call after every profile open rather than by patching each call site individually. Movies/Series stay lazy per-category as intended; a category larger than one page (500 rows) now loads incrementally via `loadMoreInCategory` as the list/grid is scrolled.

### Task 4: Verification and CI

**Files:**
- Modify only files required by CI findings.

**Interfaces:**
- Consumes: GitHub Actions test/lint/build output.
- Produces: a reviewable PR with green CI.

- [ ] Run `testDebugUnitTest`, `lintDebug` and `assembleDebug` through the repository workflow.
- [ ] Inspect failing job logs and correct compile/test/lint regressions.
- [ ] Repeat until the PR workflow is green.

## Unresolved product decisions

None. The existing UX and manual-refresh policy are preserved.