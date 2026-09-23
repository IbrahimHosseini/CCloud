# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

CCloud is a single-module (`:app`) Android streaming client (movies / TV series) written in Kotlin with Jetpack Compose + Material 3 and Media3 ExoPlayer. It ships one APK for phones, tablets and Android TV (registered for both `LAUNCHER` and `LEANBACK_LAUNCHER`). Package / namespace: `com.pira.ccloud`. minSdk 24, target/compileSdk 36.

## Build commands

```bash
./gradlew assembleDebug                 # debug APKs (ABI splits + universal)
./gradlew assembleRelease -x lint       # what CI runs; output: app/build/outputs/apk/release/app-universal-release.apk
./gradlew lint                          # uses app/lint-baseline.xml; abortOnError = false
./gradlew installDebug                  # install on the connected device/emulator
```

- Gradle wrapper is 8.11.1 (AGP 8.9.2, Kotlin 2.0.21). CI uses **JDK 17**. Gradle 8.11 cannot run on JDK 24+ (e.g. a newer Android Studio bundled JBR), so point `JAVA_HOME` at JDK 17–21 when building from the terminal, and set `ANDROID_HOME` if there is no `local.properties`.
- Release signing reads `key.properties` at the repo root (`storePassword`, `keyPassword`, `keyAlias`, `storeFile`); if the keystore file is missing the release build silently falls back to the debug signing config.
- `scripts/fix-gradle-wrapper.sh` / `verify-gradle-wrapper.sh` regenerate/verify `gradle/wrapper/gradle-wrapper.jar.sha256`, which CI validates.
- Releases: pushing a `v*` tag runs `.github/workflows/build-appshare.yml`, which builds and publishes `CCloud-universal.apk` to GitHub Releases. Bump `versionCode`/`versionName` in `app/build.gradle.kts`; the in-app update check (Settings) compares against the GitHub releases API for `code3-dev/CCloud`.

### Tests

There are currently **no test sources** (`app/src/test` and `app/src/androidTest` don't exist), although JUnit, Espresso and Compose UI test dependencies are declared. When adding tests: `./gradlew testDebugUnitTest --tests "com.pira.ccloud.SomeTest"` for a single JVM test, `./gradlew connectedDebugAndroidTest` for instrumented tests.

## Architecture

The README's architecture section overstates things: there is no domain layer, no LiveData/Flow, no DI, and the Leanback library is a dependency but the TV UI is pure Compose. What actually exists:

### Two activities
- `MainActivity` hosts the whole Compose app (`MainApp` → `MainScreen` → `AppNavigation`).
- `VideoPlayerActivity` is a separate, landscape, fullscreen activity. Start it only via `VideoPlayerActivity.start(context, url)` or `startWithEpisodeInfo(...)` (the episode ids are used to mark the episode as watched). It embeds a Media3 `PlayerView` with `useController = false` inside `AndroidView`, and draws its **own** Compose control overlay (seek bar, play/pause, speed, track-selection dialog). Remote-control handling is described under "Android TV remote input" below.

### Phone vs TV layout
`DeviceUtils.isTv(context)` (UiModeManager == TELEVISION) is the layout switch: TV renders `SidebarNavigation` (a `NavigationRail`) beside the content; phones render `BottomNavigationBar` in the `Scaffold`. Grid column count (`DeviceUtils.getGridColumns`) and default subtitle size also branch on it. For behaviour that depends on *how* the user navigates (remote vs touch/mouse), check `LocalInputModeManager.current.inputMode == InputMode.Keyboard` instead, as `SearchScreen` does. Any D-pad/remote behaviour must be driven by Compose focus APIs (`FocusRequester`, `focusProperties`, `onKeyEvent`) — see `SettingsScreen`/`FavoritesScreen` for explicit focus chains.

### Android TV remote input
- **Player:** `VideoPlayerActivity.dispatchKeyEvent` hands every key to the handler that `VideoPlayerScreen` registers (`onKeyHandlerChanged`), before any view sees it, so keys work even when nothing on screen has focus. With no control focused (controls hidden or shown by a tap): OK = play/pause, Left/Right = seek by `VideoPlayerSettings.seekTimeSeconds`, Up/Down = just show the controls; each of these shows the controls with focus on the seek bar, where Left/Right keep seeking and OK keeps toggling. On other controls the arrows move focus and OK clicks. Back is left to the default handling (closes the player).
- When a key handler consumes a key-down, it must also consume the matching key-up: in Compose foundation 1.7, `clickable` fires `onClick` on **any** OK/Enter key-up, so a stray key-up clicks whichever control just received focus (the player tracks this in `consumedKeys`).
- **Text fields:** a Compose `TextField` opens the keyboard as soon as it gains focus. Never wrap it in `.clickable {}`: that adds a separate outer focus target that captures D-pad focus, so the field (and its keyboard) never gets focus. Compose's own D-pad handling inside text fields ignores remotes that don't report themselves as D-pad devices (common on cheap Android TV boxes, e.g. OK sends `KEYCODE_ENTER`); `SearchScreen.handleRemoteKey` covers them.
- Controls in the player need a visible focus indicator for TV (`Modifier.focusRing`); the default Material ripple focus state is too faint on the dark overlay.

### Testing on an Android TV emulator
- `adb shell input keyevent` injects from a *virtual* device, which Compose's text-field D-pad handling (and `handleRemoteKey`) ignore. To behave like a real remote, register one with `adb shell uinput -` (JSON "register"/"inject" commands; `KEY_SELECT` maps to DPAD_CENTER, `KEY_ENTER` to ENTER).
- The Google TV image needs ≥4 GB emulator RAM; at 2 GB the keyboard (Gboard) stalls for seconds and Back presses fall through to the app.
- The content CDN (`*.dl150m.info`) may be unreachable outside Iran, so real titles can fail to play. `VideoPlayerActivity` is not exported; to try the player with a public test stream, export it in a scratch copy of the project, never in the repo.

### Navigation
String routes are defined in the `AppScreens` sealed class (`splash` is the start destination and navigates to `movies`; detail routes are `single_movie/{movieId}`, `single_series/{seriesId}`, `country/{countryId}`). `MainScreen` maps the current route back to an `AppScreens` by prefix to decide whether the bottom bar is shown. The Movies/Series/Search/Country ViewModels are created once in `AppNavigation` (activity-scoped) so their state survives tab switches — don't create them per-destination.

### Passing items to detail screens
Detail screens do **not** receive objects via nav arguments. The list/search screens first call `StorageUtils.saveMovieToFile` / `saveSeriesToFile` (which deletes previously cached items and writes `filesDir/movie_<id>.json` / `series_<id>.json`), then navigate with only the id; `SingleMovieScreen`/`SingleSeriesScreen` read it back with `loadMovieFromFile`/`loadSeriesFromFile`. Navigating to a detail route without saving first shows "not found".

### Data layer
- Repositories in `data/repository` extend `BaseRepository`, which owns the shared `OkHttpClient`, the API key, and `executeRequest(primaryUrl, requestBuilder)`: on failure it retries the same path against each host in `helperServers`. Always go through `executeRequest` so the fallback hosts work.
- Endpoints are `https://server-hi-speed-iran.info/api/...` with the API key as a path segment. Responses are parsed by hand with `org.json` (`optString`/`optInt` with defaults, skipping malformed items), not kotlinx.serialization.
- ViewModels expose Compose `mutableStateOf` properties with `private set` and launch work in `viewModelScope`. Results are filtered through `LanguageUtils.shouldDisplayTitle` to hide items with Farsi titles.

### Persistence
- `StorageUtils` (a singleton object) stores everything as kotlinx.serialization JSON files in `filesDir`: favorites and favorite groups, watched episodes, subtitle / video-player / font settings, and the cached detail items above. Classes in `data/model` are `@Serializable` and are kept by ProGuard (`-keep class com.pira.ccloud.data.model.**`); renaming their fields breaks data already stored on users' devices.
- Theme settings are the exception: `ThemeManager` uses SharedPreferences (`theme_prefs`).
- Settings changes flow through callbacks, not shared state: `MainApp` holds theme/font state and passes `onThemeSettingsChanged`/`onFontSettingsChanged` down through `AppNavigation` to `SettingsScreen`. `VideoPlayerActivity` reads settings from `StorageUtils` directly when it starts.

### Fonts
The optional Vazirmatn font exists twice: `res/font` (used by `FontManager` for Compose text) and `assets/font` (used by the player via `Typeface.createFromAsset` for subtitle styling). Keep both in sync.
