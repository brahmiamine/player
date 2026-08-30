# Streamia TV Implementation Plan

> **For agentic workers:** Use the host's available task-by-task implementation workflow. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a TV-only Android application that authenticates against a user-supplied Xtream endpoint, browses live categories and channels, and plays or zaps channels with a remote.

**Architecture:** A single Kotlin/Compose application separates pure Xtream URL/domain logic, Android network and encrypted-storage adapters, a lifecycle ViewModel, and TV-focused composables. Media3 ExoPlayer owns playback; the catalogue is grouped once off the main thread and cached locally for resilient startup.

**Tech Stack:** Kotlin 2.4.10, Android Gradle Plugin 8.13, Jetpack Compose BOM 2026.04.01, Compose for TV 1.1.0, Lifecycle 2.11.0, Media3 1.11.0, JUnit 4, GitHub Actions.

## Global Constraints

- Android TV only; touchscreen hardware is not required.
- No provider, playlist, server URL, username, password, channel, or copyrighted stream is bundled.
- All browsing and playback actions must work with a D-pad remote.
- Credentials remain on-device and are encrypted with Android Keystore.
- Large catalogues use lazy containers, stable keys, cached logos, and no main-thread network or JSON parsing.
- HTTP endpoints remain supported because many private Xtream installations do not expose HTTPS; the login screen warns when transport is not encrypted.

---

### Task 1: Xtream contracts and catalogue persistence

**Files:**
- Create: `app/src/main/java/fr/streamia/tv/domain/XtreamModels.kt`
- Create: `app/src/main/java/fr/streamia/tv/domain/XtreamUrlBuilder.kt`
- Create: `app/src/main/java/fr/streamia/tv/data/XtreamClient.kt`
- Create: `app/src/main/java/fr/streamia/tv/data/CatalogCache.kt`
- Create: `app/src/main/java/fr/streamia/tv/data/CredentialsStore.kt`
- Test: `app/src/test/java/fr/streamia/tv/domain/XtreamUrlBuilderTest.kt`

**Interfaces:**
- Consumes: Xtream `player_api.php`, `get_live_categories`, `get_live_streams`, and `/live/{username}/{password}/{streamId}.ts` contracts.
- Produces: `ServerCredentials`, `LiveCategory`, `LiveChannel`, `Catalog`, `XtreamUrlBuilder`, and `XtreamRepository`.

- [ ] Add URL normalization, encoding, unsupported-scheme, and stream-address tests.
- [ ] Run `gradle :app:testDebugUnitTest`; observe failures before the production contracts exist.
- [ ] Implement network parsing, authentication checks, encrypted credentials, cache read/write, and offline fallback.
- [ ] Run `gradle :app:testDebugUnitTest`; expect all domain tests to pass.
- [ ] Run `gradle :app:lintDebug`; expect no fatal findings.

### Task 2: TV catalogue and login flow

**Files:**
- Create: `app/src/main/java/fr/streamia/tv/ui/StreamiaViewModel.kt`
- Create: `app/src/main/java/fr/streamia/tv/ui/StreamiaApp.kt`
- Create: `app/src/main/java/fr/streamia/tv/ui/LoginScreen.kt`
- Create: `app/src/main/java/fr/streamia/tv/ui/BrowserScreen.kt`
- Create: `app/src/main/java/fr/streamia/tv/ui/RemoteComponents.kt`

**Interfaces:**
- Consumes: `XtreamRepository.restore`, `signIn`, `refresh`, `logout`, and immutable `Catalog` values.
- Produces: login, loading, error, offline and catalogue states; focusable category and channel surfaces.

- [ ] Add ViewModel state-transition tests using a repository seam.
- [ ] Verify the tests fail because restore/sign-in transitions are absent.
- [ ] Implement TV login, error recovery, category selection, lazy channel grid, focus restoration, and account reset.
- [ ] Verify state tests and lint pass.

### Task 3: Full-screen playback and remote zapping

**Files:**
- Create: `app/src/main/java/fr/streamia/tv/ui/PlayerScreen.kt`
- Modify: `app/src/main/java/fr/streamia/tv/ui/StreamiaApp.kt`

**Interfaces:**
- Consumes: `LiveChannel.streamUrl`, `Catalog.channelsIn`, Media3 `ExoPlayer`, and Android key events.
- Produces: full-screen playback, previous/next channel wrapping, play/pause, guide overlay, and Back semantics.

- [ ] Add pure channel-order tests for previous/next wrapping.
- [ ] Verify failure before navigation helpers exist.
- [ ] Implement Media3 lifecycle ownership, loading/error overlay, D-pad zapping, Menu/Left guide opening, and Back-to-close behaviour.
- [ ] Verify unit tests, lint, and debug assembly.

### Task 4: TV packaging, automation, and release proof

**Files:**
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/drawable/*` and `app/src/main/res/mipmap-anydpi-v26/*`
- Create: `.github/workflows/android.yml`
- Create: `README.md`

**Interfaces:**
- Consumes: Android TV launcher intent, leanback feature declarations, system splash theme, and GitHub Actions.
- Produces: installable debug APK artifact and release APK on version tags.

- [ ] Add TV banner, adaptive icon, splash art, and TV-only manifest declarations.
- [ ] Build `assembleDebug` and `assembleRelease`; expect APK outputs.
- [ ] Install on an Android TV emulator, navigate with adb key events, capture UI tree, screenshots, crash log, frame stats, and memory snapshot.
- [ ] Publish the validated branch and open a pull request, or record the exact runtime blocker.
