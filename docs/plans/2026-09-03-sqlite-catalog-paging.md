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

- [ ] Reopen cached profiles from SQLite metadata/categories without hydrating Movies/Series.
- [ ] Restore the last Live category first when resuming playback.
- [ ] Load a browser category in bounded pages and merge pages into the presentation catalogue.
- [ ] Trigger the next page when the TV list/grid approaches the end.
- [ ] Keep counts accurate using database metadata rather than loaded-list size.

### Task 3: Remove resolved full-catalog duplication and preserve user customization

**Files:**
- Modify: `app/src/main/java/fr/streamia/tv/data/CatalogCache.kt`
- Modify: `app/src/main/java/fr/streamia/tv/data/XtreamRepository.kt`
- Modify: `app/src/main/java/fr/streamia/tv/ui/StreamiaViewModel.kt`

**Interfaces:**
- Consumes: `UserLibrarySnapshot` deltas.
- Produces: one supplier catalogue store plus existing lightweight user-library persistence.

- [ ] Stop writing a second resolved copy of the full provider catalogue.
- [ ] Apply category order/moved-entry deltas only to the rows/pages being presented.
- [ ] Preserve organizer behavior by hydrating the requested section before organizer operations.

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