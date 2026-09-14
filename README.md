# boss-plugin-bottest

A [BOSS Console](https://github.com/risa-labs-inc/BossConsole) plugin for testing
conversational AI — chatbots and voice bots — against a fixed rubric.

> **Status: usable from an agent, no UI yet.** Suites are JSON files, and
> `bottest_list_suites` / `bottest_run_suite` are live MCP tools. There is no LLM
> judge, no baseline/regression tracking, and no UI — the panel is still a
> placeholder.

## Why

Building a conversational bot means constantly swapping components — TTS, STT,
LLM, system prompt — with no standard way to tell whether a change actually
helped. There is no `npm test` for a voice agent.

This plugin aims to be that: a fixed test suite, real metrics, and a
per-category breakdown, driven by the agent that is building the bot.

Working today:

- **JSON suite files**, runnable from an agent via MCP
- **HTTP runner** against any JSON chat endpoint — configurable request fields
  and a dot-path response extractor (`choices.0.message.content`)
- **Latency, status, and error capture**, with timeouts and connection failures
  recorded as results rather than crashing the run
- **Deterministic evaluators** — non-empty, HTTP success, latency budget,
  required phrases (with partial credit), forbidden phrases
- **Per-category aggregation** — `happy_path: 92%`, `adversarial: 64%`

Planned:

- **LLM-as-judge** scoring against a rubric (the `Evaluator` seam already exists)
- **Regression diff** against a stored baseline
- **Voice**: latency split by stage (STT → LLM → TTS) and WER

## MCP tools

Tools surface to agents in BOSS as `mcp__boss__bottest_*`.

| Tool | Status | Description |
|---|---|---|
| `bottest_info` | ✅ | Plugin status and available capabilities |
| `bottest_list_suites` | ✅ | List suites — id, name, description, test count, categories |
| `bottest_run_suite` | ✅ | Run a suite; returns a structured JSON report |
| `bottest_compare` | planned | Diff a run against a baseline |

`bottest_run_suite` takes `suite_id` (required) and `timeout_ms` (optional
per-request override). It returns JSON — summary counts, overall score, per
category scores, average latency, error rate, failing test ids, and the reason
each one failed.

## Suite files

Suites are `<id>.json` files in one directory. The filename must match the
suite's `id`. The directory is `~/.bottest/suites` by default, or
`$BOTTEST_SUITES_DIR` if set. See [`examples/basic-suite.json`](examples/basic-suite.json).

```json
{
  "id": "customer-support",
  "name": "Customer Support Tests",
  "description": "Basic tests for the support chatbot",
  "target": {
    "url": "http://localhost:8000/chat",
    "method": "POST",
    "timeoutMs": 15000,
    "request": { "messageField": "message" },
    "response": { "responsePath": "response" }
  },
  "tests": [
    {
      "id": "happy_01",
      "category": "happy_path",
      "input": "How do I reset my password?",
      "criteria": { "requiredPhrases": ["password"], "maxLatencyMs": 3000 }
    },
    {
      "id": "ambiguous_01",
      "category": "ambiguous",
      "input": "Can you change my appointment?",
      "criteria": ["Does not assume which appointment", "Asks a clarification"]
    }
  ]
}
```

`criteria` takes two shapes. An **object** drives the deterministic evaluators.
An **array of strings** is a natural-language rubric — it is stored, but
**nothing checks it yet**; runs that rely on it say so in the report's `notes`.

Categories: `happy_path`, `ambiguous`, `out_of_scope`, `adversarial`, `persona`,
`multi_turn`, `edge_case`.

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
