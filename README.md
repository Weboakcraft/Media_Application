# Lumen

An Android app with two halves: a device-wide DNS ad/tracker blocker, and a media
player whose audio keeps going when you leave the app.

## Build the release APK

The project does not build in the chat workspace (no Android SDK there). Build it
locally or in CI.

```bash
./gradlew assembleRelease
```

Output lands in `app/build/outputs/apk/release/`. Per-ABI APKs plus a universal one.

Unsigned builds work out of the box. To sign:

```bash
keytool -genkeypair -v -keystore lumen-release.jks \
  -alias lumen -keyalg RSA -keysize 4096 -validity 10000

cp keystore.properties.example keystore.properties   # then fill it in
./gradlew assembleRelease
```

`keystore.properties` and `*.jks` are git-ignored. Losing the keystore means you can
never update an installed copy.

### CI

`.github/workflows/release.yml` builds on every push to `main`, on any `v*` tag, and
on demand from the Actions tab. It produces a release APK and a debug APK.

If no keystore secrets are set, CI generates a throwaway key so the APK still
installs. That key changes every build, so you must uninstall the previous copy
before installing a new one. For a stable signature set these secrets:

| Secret | What it holds |
| --- | --- |
| `LUMEN_KEYSTORE_BASE64` | `base64 -w0 lumen-release.jks` |
| `LUMEN_KEYSTORE_PASSWORD` | keystore password |
| `LUMEN_KEY_ALIAS` | key alias |
| `LUMEN_KEY_PASSWORD` | key password |

Without them the workflow still runs and produces an unsigned APK.

## What the shield does

A `VpnService` that routes **only** the stub resolver address into the tun device.
Real traffic never enters the process, so throughput is untouched and the service
wakes only when an app resolves a name.

- Blocked names are answered locally with NXDOMAIN — the client gives up immediately
  instead of dialling 0.0.0.0 and waiting for a timeout.
- Everything else is forwarded to the upstream resolver over a `protect()`ed socket,
  which bypasses the tunnel and cannot loop back.
- Both IPv4 and IPv6 are handled. Leaving IPv6 out would let DNS leak around the
  filter on any v6-capable network.
- Per-app bypass, your own allow/deny rules (allow always wins), a Quick Settings
  tile, and optional start-after-reboot.

Blocklists: StevenBlack, AdGuard DNS, EasyPrivacy, malware domains. The parser takes
hosts format, plain domain lists, and `||domain^` rules; rules with modifiers or paths
are skipped because a DNS answer cannot distinguish request context.

**It cannot block YouTube ads.** Those come from the same hosts and are stitched into
the same video streams as the content, so no DNS filter reaches them. Nothing here
patches or reverse-engineers another app.

## What the player does

Media3 `MediaSessionService` with ExoPlayer.

- Audio survives backgrounding, screen-off and swiping the app out of recents
  (`onTaskRemoved` keeps the service alive only while something is actually playing).
- Audio focus is delegated to the player: it ducks for navigation and pauses for calls.
- Notification, lockscreen, Bluetooth and headset controls; pause on unplug.
- Picture-in-picture for video — swipe home mid-video and it follows you out.
- Sleep timer with a fade rather than a hard cut, speed control, skip-silence,
  shuffle and repeat.
- Sources: on-device audio, podcast RSS, and any direct audio/HLS address.

## Privacy and footprint

No analytics, no crash reporting, no account. The only network calls are blocklist
downloads, podcast feeds, and media you ask for. `allowBackup` is off and backup
rules exclude everything. Release builds run R8 in full mode with resource shrinking.

Package visibility uses a launcher-activity `<queries>` filter rather than
`QUERY_ALL_PACKAGES`, which keeps the app off that permission entirely.

## Layout

```
app/src/main/java/com/oakcraft/lumen/
  core/        settings (DataStore), blocklist source definitions
  blocker/     VpnService, IP/UDP and DNS codecs, matcher, list repository, QS tile
  player/      MediaSessionService, local library, podcast feeds, sleep timer
  ui/          Compose screens, theme, view models, media controller binding
```

## Verified so far

Not compiled — there is no Android SDK in the workspace that produced this. What was
checked: package/directory agreement, every `R.*` reference against the resources,
manifest component classes against the sources, version-catalog aliases against the
build file, brace balance, and the packet arithmetic (IPv4 header checksum against the
canonical worked example, UDP checksums over both pseudo-headers, NXDOMAIN synthesis,
and suffix matching) against test vectors.

Expect to fix a few compile errors on the first build.
