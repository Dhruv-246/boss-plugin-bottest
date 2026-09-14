# boss-plugin-bottest

A [BOSS Console](https://github.com/risa-labs-inc/BossConsole) plugin for testing
conversational AI — chatbots and voice bots — against a fixed rubric.

> **Status: evaluation core implemented, no UI yet.** The runner, deterministic
> evaluators, and suite/category aggregation work and are unit tested. There is
> no LLM judge, no suite file format, and no UI — the panel is still a
> placeholder, and no MCP tool exposes the runner yet.

## Why

Building a conversational bot means constantly swapping components — TTS, STT,
LLM, system prompt — with no standard way to tell whether a change actually
helped. There is no `npm test` for a voice agent.

This plugin aims to be that: a fixed test suite, real metrics, and a
per-category breakdown, driven by the agent that is building the bot.

Working today:

- **HTTP runner** against any JSON chat endpoint — configurable request fields
  and a dot-path response extractor (`choices.0.message.content`)
- **Latency, status, and error capture**, with timeouts and connection failures
  recorded as results rather than crashing the run
- **Deterministic evaluators** — non-empty, HTTP success, latency budget,
  required phrases (with partial credit), forbidden phrases
- **Per-category aggregation** — `happy_path: 92%`, `adversarial: 64%`

Planned:

- **LLM-as-judge** scoring against a rubric (the `Evaluator` seam already exists)
- **Suite files** so cases live in version control, not code
- **Regression diff** against a stored baseline
- **Voice**: latency split by stage (STT → LLM → TTS) and WER

## MCP tools

Tools surface to agents in BOSS as `mcp__boss__bottest_*`.

| Tool | Status | Description |
|---|---|---|
| `bottest_info` | ✅ | Report plugin status and available capabilities |
| `bottest_run_suite` | planned | Run a test suite against a bot endpoint |
| `bottest_compare` | planned | Diff a run against a baseline |

## Build

Requires **JDK 17**.

```bash
./gradlew build            # compile, test, assemble the plugin jar
./gradlew buildPluginJar   # just the loadable jar
./gradlew test             # just the tests
```

The `boss-plugin-api` jar is downloaded automatically into `libs/` on first
build. It is `compileOnly` — the BOSS host provides it at runtime, so it is
never bundled into the plugin jar.

## Install locally

BOSS loads plugins from disk **at startup**, from `~/.boss_debug/plugins/` in
dev mode or `~/.boss/plugins/` in production. Prefer dev mode so a test build
never touches a production install.

```bash
./gradlew clean buildPluginJar
mkdir -p ~/.boss_debug/plugins
cp build/libs/boss-plugin-bottest-0.1.0.jar ~/.boss_debug/plugins/
rm -rf ~/.boss_debug/plugin-cache/ai.rever.boss.plugin.dynamic.bottest
# then restart BOSS
```

Clearing the extracted cache matters — without it the host keeps serving the
previous bytecode.

To verify: the **Bot Test** panel appears in the left sidebar, and an agent in
BOSS's terminal can call `bottest_info`.

## Compatibility

| | |
|---|---|
| `boss-plugin-api` | 1.0.91 |
| Minimum BOSS | 9.5.0 |
| JDK | 17 |

## License

Apache-2.0, matching the BOSS core.
