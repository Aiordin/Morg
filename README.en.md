# Morg

**Open another instance of the app you are using.**

[简体中文](README.md) · [Download](../../releases/latest) · [MIT License](LICENSE)

Morg is an Android multi-window tool that opens a new task for a running app.

> 0.1.0, experimental. The core behavior depends on each ROM's window manager.
> Read [Limitations](#limitations) first.

## Limitations

- **"Multiple instances" means multiple tasks, not processes.** Instances of the same app
  share one process; each task has its own back stack and Activity.
- **Small windows need platform support.** ColorOS uses native zoom
  (`windowingMode=100`); other devices need Developer options → "Enable freeform windows"
  or "Force activities to be resizable" (`windowingMode=5`, different look). Without
  either, only fullscreen is available.
- **Android 10 (API 29) or newer.** Target SDK is 34.
- Verified on a OnePlus PJF110 / ColorOS 14 only; other ROMs may behave differently.

## Permissions

| Permission | Purpose | Required |
|---|---|---|
| Display over other apps | Draw the edge handle | Yes |
| Usage access | Detect the foreground app, which decides whether the handle shows | Yes |
| Notifications | Persistent notification with a Stop action | No (the handle works without it) |

## Install

1. Download `Morg-*.apk` from [Releases](../../releases/latest), or build it yourself.
2. The first launch shows a welcome page explaining the three switches. Tap “Get started”.
3. Turn on “Edge handle” and grant the two required permissions when prompted.
4. “Open as” is fullscreen by default. Turn it on for window mode; the first time, Morg opens a real small window to confirm support.
5. Once the service is running, you can turn on “Show toolbar” — a floating button at the bottom opens another instance in one tap.

## Build

Requirements:

- JDK 17
- Android SDK: Platform 35, Build-Tools 35.0.0
- The bundled Gradle wrapper (Gradle 9.3.1)

## Project layout

```
app/src/main/kotlin/aiordin/morg/
├── MorgApp.kt                 Application entry point + manual DI
├── MainActivity.kt            The only Activity: setup / settings
├── core/                      Settings, permissions, onboarding rules, build info
├── launch/                    Window modes, launch options, small-window probe
├── overlay/                   Handle and toolbar windows, geometry, foreground tracking, service
├── qs/                        Quick Settings tile
└── ui/                        Compose UI, first-run guides and theme
app/src/test/kotlin/aiordin/morg/   JVM unit tests
gradle/libs.versions.toml       Central dependency versions
flake.nix                       Development environment
```

**Pure logic is kept separate from Android APIs** — window geometry, foreground decisions,
onboarding rules and the small-window probe reference no Android types and are unit-tested
on the JVM. Rectangles use the project's own `WindowBounds` instead of
`android.graphics.Rect`, which is a stub in unit tests.

## Contributing

Issues and pull requests are welcome. For changes to the relaunch mechanism or window
behavior, please open an issue first and include the device and ROM version you tested on.

## License

[MIT](LICENSE)
