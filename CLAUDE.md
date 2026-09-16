# boss-plugin-bottest — working notes

A BOSS Console plugin. Testing framework for conversational AI.

## Architecture

Standalone Gradle JVM project producing a plugin jar that the BOSS host loads
from `~/.boss/plugins/` (or `~/.boss_debug/plugins/` in dev mode) at startup.

```
BotTestDynamicPlugin.kt   entry point — implements DynamicPlugin
BotTestInfo.kt            PanelInfo — id, icon, sidebar slot
BotTestComponent.kt       PanelComponentWithUI — Compose UI
BotTestMcpTools.kt        McpToolProvider — the bottest_* tool surface
```

## Rules that bite

- **`boss-plugin-api` is `compileOnly`.** The host classloader provides it. Never
  promote it to `implementation` — a bundled copy breaks class identity.
- **Compose Multiplatform APIs only.** Never Android ones.
- **Wrap all UI in `BossTheme { }`** so it follows the host theme.
- **Every `PluginContext` provider is nullable.** Null-check; never crash on a
  missing provider — degrade instead.
- **MCP handlers must be cancellation-cooperative.** The host wraps each call in
  `withTimeout`, which only interrupts at a suspension point. Wrap blocking I/O
  in `withContext(Dispatchers.IO)`. This matters here: eval runs are long.
- **`tools()` is not reactive.** It is recomputed only on register/unregister. If
  availability depends on runtime state, check inside the handler.
- **`McpToolArgs` exposes top-level scalars only** (`string`/`int`/`boolean`/
  `double`). Use `args.raw` for nested structures.
- **Prefer `McpToolDefinition.withRbac(...)`** over `.apply { requiredPermissions = }`
  — a later `.copy()` silently drops RBAC set via `apply`.
- **Never hand-edit `version` in `plugin.json`.** `build.gradle.kts` is the single
  source of truth; `processResources` rewrites the manifest at build time.
- **Reserved tool names** are skipped silently by the terminal-tab bridge. The
  list is in `BotTestMcpTools.kt` and guarded by a test.
- **Manifest parsing is lenient** — typo'd fields are ignored and an invalid
  `type` silently becomes `panel`. Check `plugin.json` by hand.

## Commands

```bash
./gradlew build                      # compile + test + jar
./gradlew buildPluginJar             # jar only
./gradlew downloadBossPluginApi      # fetch the API jar into libs/
```

## Version gating (verified against BossConsole source)

`AiGatewayAPI` and `AiAvailability` were added in **boss-plugin-api 1.0.88**,
first pinned by **BossConsole v9.5.9** (1.0.89 -> v9.5.14, 1.0.90 -> v9.5.17).
Hence `apiVersion: 1.0.88`, `minBossVersion: 9.5.9`.

Compile against the floor, not the latest: `ai.rever.boss.plugin.api.` is
parent-first, so on a 9.5.9 host the host's 1.0.88 classes are what resolve.
Building against 1.0.88 makes the compiler enforce that floor instead of leaving
it to a runtime `NoSuchMethodError`. Raising the pin means re-checking which BOSS
release first shipped that api version and raising `minBossVersion` to match.

Compose 1.10.0, decompose 3.3.0, essenty 2.5.0 and Kotlin 2.3.0 are deliberately
older than the host's (1.12.0 / 3.5.0 / 2.6.0 / 2.4.10). All are parent-first, so
the host's copies win at runtime; these are the versions the canonical
`boss-plugin-git-status` plugin compiles against.

## Upstream references

- Host: https://github.com/risa-labs-inc/BossConsole
- SDK: https://github.com/risa-labs-inc/boss-plugin-api
- Umbrella + docs: https://github.com/risa-labs-inc/boss-plugins
- Reference plugin: https://github.com/risa-labs-inc/boss-plugin-git-status
