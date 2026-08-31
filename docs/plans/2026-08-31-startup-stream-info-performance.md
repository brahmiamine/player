# Startup, Stream Info & Playback Performance Implementation Plan

> **For agentic workers:** Use the host's available task-by-task implementation workflow. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Streamia reopen the last playlist/content immediately, refresh catalogs without blocking playback, start/zap faster, and show a bottom information band with real Media3 stream characteristics.

**Architecture:** Keep the existing ViewModel/repository/Compose structure. Extend the existing playback session persistence for the active profile, make profile opening cache-first with a silent network refresh, isolate playback tuning/URL fallback/stream telemetry in small helpers, and let `PlayerScreen` render one responsive bottom HUD fed only by decoded Media3 metadata.

**Tech Stack:** Kotlin, Jetpack Compose for TV, Media3/ExoPlayer 1.11, OkHttp Media3 datasource, Android SharedPreferences/Keystore-backed playlist profiles, JUnit, GitHub Actions Android SDK 36.

## Global Constraints

- Android TV remote behavior stays intact: OK opens the Live category/channel picker, arrows/CH+/CH- zap, numeric keys select channels.
- Do not infer technical quality from channel/movie/series names.
- The bottom band appears whenever content starts or a Live channel changes, and can hide automatically after playback stabilizes.
- No simultaneous preloading of adjacent Live streams, to avoid consuming multiple Xtream connections.
- Credentials and private stream URLs must not be persisted in diagnostics or logs.
- Existing favorites, EPG, search, movie/series details and playback resume must remain compatible.

---

### Task 1: Cache-first bootstrap and persistent active playlist

**Files:**
- Modify: `app/src/main/java/fr/streamia/tv/data/PlaybackSessionStore.kt`
- Modify: `app/src/main/java/fr/streamia/tv/data/XtreamRepository.kt`
- Modify: `app/src/main/java/fr/streamia/tv/ui/StreamiaViewModel.kt`
- Modify: `app/src/main/java/fr/streamia/tv/ui/StreamiaTvRoot.kt`

**Interfaces:**
- Consumes: existing `PlaylistProfile`, `LoadedCatalog`, `LastPlaybackSession`.
- Produces: active-profile persistence, cache-first `openProfile`, silent catalog refresh preserving the current screen/player.

- [ ] Add focused persistence/bootstrap tests where practical and pure decision tests for profile selection.
- [ ] Reopen the last active profile even if no media session exists; explicit logout disables auto-open.
- [ ] Return cached catalogs immediately when available, then refresh network data silently.
- [ ] Restore last Live/movie/episode immediately from the cached catalog; VOD position still comes from history.
- [ ] Preserve the current `Player` screen when a silent refresh succeeds or fails.

### Task 2: Faster playback, network reuse and intelligent fallback

**Files:**
- Create: `app/src/main/java/fr/streamia/tv/player/PlaybackTuning.kt`
- Create: `app/src/main/java/fr/streamia/tv/player/PlaybackUrlStrategy.kt`
- Create: `app/src/main/java/fr/streamia/tv/player/PlaybackTransportStore.kt`
- Create: `app/src/main/java/fr/streamia/tv/player/StreamiaPlayerFactory.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/fr/streamia/tv/ui/PlayerScreen.kt`
- Test: `app/src/test/java/fr/streamia/tv/player/PlaybackTuningTest.kt`
- Test: `app/src/test/java/fr/streamia/tv/player/PlaybackUrlStrategyTest.kt`

**Interfaces:**
- Produces `BufferProfile.forType(MediaType)`, ordered playback URL candidates and a shared OkHttp-backed ExoPlayer factory.

- [ ] Use a small Live startup buffer and a larger VOD buffer.
- [ ] Reuse one player while zapping and explicitly stop the previous media item before opening the next stream.
- [ ] Use Media3 OkHttp datasource with connection pooling/retry.
- [ ] Try Live TS/HLS and HTTP/HTTPS candidates sequentially, never concurrently.
- [ ] Persist only successful scheme/container preference per provider host, never full credential-bearing URLs.

### Task 3: Real stream telemetry and buffering diagnostics

**Files:**
- Create: `app/src/main/java/fr/streamia/tv/player/StreamTechnicalInfo.kt`
- Create: `app/src/main/java/fr/streamia/tv/player/PlaybackDiagnostics.kt`
- Modify: `app/src/main/java/fr/streamia/tv/ui/PlayerScreen.kt`
- Test: `app/src/test/java/fr/streamia/tv/player/StreamTechnicalInfoTest.kt`
- Test: `app/src/test/java/fr/streamia/tv/player/PlaybackDiagnosticsTest.kt`

**Interfaces:**
- Produces actual width/height, FPS when declared, codec, bitrate when declared, HDR/SDR classification, startup-to-first-frame time and rebuffer count/duration.

- [ ] Read selected video `Format` and `VideoSize`; never parse quality words from media names.
- [ ] Update values when adaptive tracks change.
- [ ] Keep unknown values explicit instead of inventing them.
- [ ] Count rebuffering only after the first rendered frame; keep startup buffering separate.

### Task 4: Bottom channel/content information band

**Files:**
- Modify: `app/src/main/java/fr/streamia/tv/ui/PlayerScreen.kt`

**Interfaces:**
- Consumes `MediaEntry`, EPG, `StreamTechnicalInfo`, `PlaybackDiagnostics`.

- [ ] Replace the split top card/bottom controls HUD with one responsive bottom band.
- [ ] Live: logo, number, channel name, current/next EPG, resolution/quality label, FPS, codec, bitrate, HDR/SDR, TS/HLS, startup/rebuffer stats.
- [ ] Movie/series: poster/logo, title/episode, type, resume status and the same technical stream fields.
- [ ] Show the band immediately on launch/zap while technical fields say `Détection…`; refresh in place as Media3 discovers the stream.
- [ ] Keep remote hints compact and preserve the existing picker/settings interactions.

### Task 5: Verification and Android TV QA

**Files:**
- Modify only if verification finds regressions.

- [ ] Run `gradle --no-daemon testDebugUnitTest lintDebug assembleDebug` in GitHub Actions.
- [ ] Inspect failing CI jobs and loop fixes until green or report the exact blocker.
- [ ] If an ADB emulator/device is available, install the debug APK, launch `fr.streamia.tv.debug`, navigate with key events, capture UI tree/screenshot/logcat and verify the bottom HUD/focus behavior.
- [ ] If no emulator/device is exposed in this host, report that limitation explicitly rather than claiming visual emulator validation.

## Unresolved externally observable decisions

None. The requested behavior fixes the main product choices: automatic reopening, automatic playback restoration, cache-first startup, no multi-connection preloading, and a bottom stream-information band based on real decoded metadata.
