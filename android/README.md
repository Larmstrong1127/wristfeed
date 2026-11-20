# WristFeed for Wear OS

A standalone Wear OS app (no phone app needed) that shows any WristFeed v1 JSON feed
(see `../SPEC.md`) as a tile and as watch-face complications.

> **Status (2026-11-19):** the first compile succeeded with no errors or warnings, and the 23 core
> JVM tests pass, including the shared conformance corpus. The debug APK installed and ran on a
> Galaxy Watch 8 (SM-L330, Android 16 / API 36): it fetched a feed from a secret gist and listed it.
> **Checked on the watch (screenshots):**
> - **dashboard:** the bezel ring, eyebrow, two-line headline, alert line and three chips. A
>   41-character headline fits in two lines in the 172 dp box; a 44-character all-capitals one ends
>   in "…". Publishers should aim for about 36 characters. Overflowing text is ellipsized, never
>   clipped silently.
> - **stats:** title, three rows with per-row status colors, and the age footer
> - **progress:** the ring is sized and placed correctly (VERIFY 6 closed)
> - **message:** a full 60-character message wraps to 2 lines inside the round screen and is cut
>   with "…"
> - **stale:** colors at half brightness and the "Old data · 89h ago" footer
> - **"On tile":** switches the tile feed; deleting the tile feed falls back to the first feed
> - **launcher icon:** shows correctly in the app list
>
> **Still unchecked on a device:** the three complication types, and the tile-picker preview image.

## Layout

```
android/
  settings.gradle.kts, build.gradle.kts, gradle.properties
  gradlew, gradlew.bat, gradle/wrapper/      Gradle 8.7 wrapper (committed)
  core/   pure Kotlin/JVM, no Android imports: the SPEC contract
    MiniJson.kt     small strict JSON reader (keeps core testable on a plain JVM)
    Feed.kt         model
    FeedParser.kt   SPEC rules 1-5, clock-skew guard, fallback flag
    FeedState.kt    loading / fresh / stale / unreachable / malformed / unsupported
    Display.kt      truncation with "…", "Nm ago" / "Nh ago" / "Old data · …", complication text
    src/test        CoreTest + ConformanceTest (reads ../conformance/)
  app/    the Wear OS app
    data/FeedStore.kt       up to 5 feeds in SharedPreferences (URL, last good body, last result)
    data/Fetcher.kt         HTTPS GET, 10 s timeouts, 64 KB cap, HTTP != 200 = unreachable; Refresher
    data/RefreshWorker.kt   WorkManager: every 30 min with network, plus one-shot refreshes
    tile/FeedTileService.kt, tile/TileLayouts.kt   ProtoLayout tile
    complication/FeedComplicationService.kt        SHORT_TEXT, LONG_TEXT, RANGED_VALUE
    ui/MainActivity.kt      Compose for Wear OS: list, add (RemoteInput), delete, "On tile", refresh
```

## Build

1. Install Android Studio (Koala or newer). Its setup wizard installs the SDK. Once the SDK license
   is accepted, the build fetches the Android 14 (API 34) platform and build tools itself.
2. Use **JDK 17** to run Gradle 8.7. Android Studio 2026 bundles Java 25, which Gradle 8.7 cannot
   run on.
   - From a shell: `JAVA_HOME=<jdk-17> ANDROID_HOME=<sdk> ./gradlew ...`
   - In Studio: set **Settings > Build Tools > Gradle > Gradle JDK** to 17.

   The wrapper is committed. CI runs `gradle test assembleDebug` with Gradle 8.7 from
   `gradle/actions/setup-gradle`; keep that version in sync with
   `gradle/wrapper/gradle-wrapper.properties`.
3. Run the JVM tests: `./gradlew :core:test` (no emulator needed). The conformance test reads every
   `<name>.json` + `<name>.expected.json` pair in `../conformance/` and fails if the folder is empty.
4. Build the app: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Install on a Galaxy Watch

1. On the watch: **Settings > About watch > Software information**, tap **Software version** 5 times
   until "Developer mode turned on".
2. **Settings > Developer options**: turn on **ADB debugging** and **Wireless debugging** (the watch
   and PC must be on the same Wi-Fi). Open Wireless debugging to see the IP and port.
3. Newer Wear OS needs pairing first: tap **Pair new device**, then on the PC
   `adb pair <ip>:<pair-port>` and enter the code.
4. `adb connect <ip>:<port>` and `adb devices` should list the watch.
5. `adb install -r app/build/outputs/apk/debug/app-debug.apk` (or press Run in Studio with the watch selected).

## Use

1. Open WristFeed on the watch, tap **Add feed** and type or dictate the https URL
   (a raw secret-gist URL works; feeds must never contain secrets). GitHub caches a raw gist
   file for up to 5 minutes, so a refresh right after a publish can still show the previous copy.
   Size `stale_after_minutes` for that (well above 5).
2. Add the tile: long-press the watch face's tile area (or swipe to the end of the tiles) >
   **+ Add tile** > WristFeed. The tile shows the first feed, or the one you marked **On tile**.
3. Add a complication: long-press the watch face > **Customize** > a complication slot >
   WristFeed. RANGED_VALUE appears only for slots that support it and shows data only when the
   feed has `progress`.
4. Tapping the tile opens the app; if the feed has `tap_url`, the app asks the paired phone to open it.

## Design notes

- **Parser in `core` with its own JSON reader.** `org.json` exists only on-device (its JVM stub throws
  in unit tests), so a 150-line strict reader keeps the contract testable with plain JUnit and lets
  the parser tell `1` (integer) from `1.0`. Decisions follow the Python reference
  (`publisher/src/wristfeed/feed.py`) so both pass the same corpus.
- **The last good copy is stored as raw bytes** and re-parsed on read, so stored state never drifts from
  the parser. A failed, malformed or unsupported fetch never replaces it (SPEC: never a blank tile).
- **Unsupported** is shown only when there is no earlier good copy, same as unreachable/malformed.
- **Refresh:** WorkManager 30-min periodic work (network required); when the tile is requested or
  becomes visible and the last attempt is over 15 min old, a one-shot work request runs. The tile
  re-renders every minute from local data so the footer age stays true.
- **Stale = dimmed** by halving the RGB of every color (no opacity modifier needed).
- Credits: clock-skew guard and footer age (Whyater/Wrist-Usage, MIT); layout-by-kind idea
  (halilozel1903/compose-wear-tiles, MIT). No code copied.

## VERIFY points (check on first build)

1. `app/build.gradle.kts`: Kotlin 1.9.24 + Compose compiler 1.5.14 + wear compose 1.3.1 resolve together.
2. `AndroidManifest.xml`: the `<queries>` entry for the companion app may be unnecessary.
3. `FeedTileService.kt`: `Tile.Builder.setTileTimeline` takes the protolayout `Timeline` (tiles 1.4).
4. `FeedTileService.kt`: `onTileResourcesRequest` returning protolayout `ResourceBuilders.Resources`.
5. `TileLayouts.kt`: `Text.Builder.setText(String)` and `setMultilineAlignment(Int)` in protolayout 1.2.
6. `TileLayouts.kt`: the progress ring's size and position (visual check only).
7. `MainActivity.kt`: `ScalingLazyColumn` import package (foundation vs material).
8. `MainActivity.kt`: `RemoteActivityHelper(context).startRemoteActivity(intent, null)`.
9. `MainActivity.kt`: `RemoteInputIntentHelper.createActionRemoteInputIntent` / `putRemoteInputsExtra`.

The launcher icon and tile preview are vectors in the project palette (`res/drawable/`). Not done
yet: per-tile feed choice through the system tile-config screen, and screenshots in the README.
