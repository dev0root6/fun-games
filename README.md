# fun-games

Neon-styled party games for Android, LAN multiplayer, no internet or accounts needed.

| Game | What it is | Players |
|------|------------|---------|
| [neon-maze](neon-maze/) | Fog-of-war maze crawler. Solo runs that grow every level, or a LAN race where everyone spawns in a different corner and the first to the portal wins. Also playable in a terminal (`maze.py`). | 1, or 1–4 on LAN |
| [neon-imposter](neon-imposter/) | Word imposter game. Everyone gets the secret word except the imposter; give clues, discuss, vote someone out. | 3–10 on LAN |

Both have custom avatars and usernames, synthesized music and sound effects (no audio files), and haptics.

## Install

Ready-built APKs live in [`releases/`](releases/):

```sh
adb install releases/neon-maze-v1.0.apk
adb install releases/neon-imposter-v1.0.apk
```

Or copy the APK to a phone and open it (allow installs from unknown sources). Android 8.0+.

For LAN play, all phones need the same Wi-Fi or one phone's hotspot. Games are discovered automatically; there's also a "type the host IP" fallback for routers that block discovery.

## Build

No Gradle. The build scripts call the Android SDK tools directly (`aapt2`, `javac`, `d8`, `apksigner`) and need:

- Android SDK with `platforms/android-35` and `build-tools/36.0.0` (`ANDROID_HOME`, default `/opt/android-sdk`)
- JDK 17 (`JAVAC`, default `/usr/lib/jvm/java-17-openjdk/bin/javac`)
- a debug keystore at `~/.android/debug.keystore` (`KEYSTORE` to override)

```sh
neon-maze/android/build.sh --install       # build, install on the connected device, launch
neon-imposter/android/build.sh --install
```

## Releases

```sh
./release.sh neon-imposter 1.1    # or: ./release.sh all 1.1
```

This builds the APKs into `releases/<game>-v<version>.apk` and prints the commit and tag commands. Release APKs are committed on `main`, and each one is tagged `<game>-v<version>`.

## Testing LAN games with one phone

Each game ships a bot script that joins the phone's game over USB:

```sh
adb forward tcp:47777 tcp:47777 && neon-maze/android/lan_bot.py              # host a LAN race on the phone first
adb forward tcp:47787 tcp:47787 && neon-imposter/android/imposter_bots.py --bots 3   # host a game on the phone first
```

The maze bot rebuilds the exact same maze from the race seed (it ports `java.util.Random`) and runs the shortest path to the portal.

| Game | TCP | UDP discovery |
|------|-----|---------------|
| neon-maze | 47777 | 47778 |
| neon-imposter | 47787 | 47788 |
