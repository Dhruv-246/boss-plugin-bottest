# boss-plugin-bottest

A [BOSS Console](https://github.com/risa-labs-inc/BossConsole) plugin for testing
conversational AI — chatbots and voice bots — against a fixed rubric.

> **Status: usable from an agent, no UI yet.** Suites are JSON files,
> `bottest_list_suites` / `bottest_run_suite` are live MCP tools, and rubric
> criteria are scored by an LLM judge that borrows the AI BOSS already has — no
> second API key. No baseline/regression tracking, and no UI — the panel is still
> a placeholder.

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
- **LLM judge** for rubric criteria, via BOSS's own AI gateway

## The LLM judge

Rubric criteria are graded by an LLM. The plugin holds **no API key**: it calls
`AiGatewayAPI`, which resolves whatever provider BOSS is configured with — and
when you are driving BOSS with a coding CLI, routes through that CLI's own
terminal login. If you already run Claude Code in BOSS, the judge works with
nothing to set up.

- Only cases declaring a rubric are judged, so a deterministic suite costs nothing
- `judge=false` on `bottest_run_suite` disables it
- A judge that is unavailable or fails is **skipped, never failed** — that is a
  fact about your machine, not about the chatbot — and the report says so
- Grading runs at `temperature=0` for repeatability, and the grading model is
  named in each verdict

**Caveat worth knowing:** the judge uses whatever model BOSS is configured with.
If that is the same model that wrote the chatbot, it is marking its own homework.
Point BOSS at a different model when that matters.

Planned:

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
An **array of strings** is a natural-language rubric, graded by the LLM judge.

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

Requires a BOSS development instance. Follow [BOSS's source-build
instructions](https://github.com/risa-labs-inc/BossConsole#development); `./gradlew run`
from the host checkout launches development mode, which uses `~/.boss_debug` rather than
`~/.boss`. Check the actual data path before installing.

```bash
./gradlew clean buildPluginJar   # -> build/libs/boss-plugin-bottest-0.1.0.jar
```

Then in the running development instance: **Toolbox -> From File**, select the JAR, enable
the plugin, and follow any reload prompt. Its tools appear under **Toolbox -> MCP**; enable
them there and call them from an attached agent.

Avoid leaving two JARs with the same plugin ID installed, and confirm the loaded version
after reinstalling.

## Access and data

What this plugin touches, so you can decide whether that is acceptable before running it:

| | |
|---|---|
| **Permissions** | None. The manifest declares no `requiredPermissions` and no `requiresAdmin`. |
| **Filesystem** | Reads `*.json` from one suites directory (`~/.bottest/suites`, or `$BOTTEST_SUITES_DIR`). Read-only, size-capped, and suite ids are restricted so a path cannot escape that directory. Writes nothing. |
| **Network out** | **Yes, by design.** `bottest_run_suite` sends each test case's text to the chat endpoint named in the suite's `target.url`. That is the system under test, chosen by you. |
| **LLM calls** | Only for cases carrying a natural-language rubric, and only when `judge` is not false. These go through BOSS's own `AiGatewayAPI`, so they use the provider BOSS is already configured with. The plugin holds no API key and stores no credential. |
| **What leaves BOSS** | Your test inputs and the bot's replies go to the target URL. For rubric cases, the input, the reply and the rubric are sent to your configured AI provider for grading. Nothing else is transmitted. |
| **Secrets** | Target headers are never rendered into output, and URL query strings are redacted, so a token in a target URL cannot reach the agent. |
| **Execution** | A suite is pure data. No shell command is run and nothing from a suite file is executed. |

## Demo and evidence

No in-BOSS screen recording yet — see Verification below for why. What has been run, against a
local mock chatbot with safe sample data, is the full path from suite file to report:

```
suites listed = [(basic-suite, 8)]

"summary":    { "total": 8, "passed": 8, "failed": 0, "errors": 0,
                "overallScore": 1.0, "averageLatencyMs": 2, "durationMs": 29 }
"categories": happy_path 2/2 · ambiguous 1/1 · out_of_scope 1/1 · adversarial 1/1
              persona 1/1 · multi_turn 1/1 · edge_case 1/1
"notes":      ["8 of 8 cases declare natural-language criteria that were not judged
               (no judge was enabled for this run), so they were scored on the
               deterministic checks alone."]
```

Real suite loaded from disk, eight real HTTP round trips, real aggregation. The `notes` line
is the report refusing to imply that ungraded rubrics passed.

## Verification

Full test plan with reproduction steps: [TESTING.md](TESTING.md).

- **152 unit tests**, `./gradlew test`, all passing; CI runs `./gradlew build` on every push
  and PR. No live chatbot or AI provider is needed to run them - the HTTP transport and the
  judge are both behind interfaces with fakes.
- Covered: request encoding and JSON-injection safety, response extraction failures, timeouts,
  HTTP errors, malformed and empty responses, latency budgets, phrase checks, scoring,
  per-category aggregation, suite parsing and validation, path-traversal attempts, oversized
  files, credential redaction, and judge unavailability.
- **End-to-end verified outside the host** against a live HTTP mock bot (above).

### Known limitations

- **Never loaded inside a running BOSS.** The host could not be brought up here: Supabase is
  unconfigured locally, `BossAppWithAuth` renders `BossApp` only when authenticated, and
  `loadExternalPlugins()` lives inside it. So **the MCP bridge and the `AiGatewayAPI` judge
  path are unexecuted** - tested by unit tests and read against the BOSS sources, not run.
- The LLM judge needs the **AI Gateway plugin** installed; it is not among the system plugins
  BOSS installs by default. Without it the judge reports unavailable and is skipped.
- Cases run sequentially, so a large suite against a slow bot takes a while.
- Multi-turn history is sent in one request rather than replayed turn by turn - fine for a
  stateless endpoint, wrong for a bot holding server-side session state.
- **Tested on macOS (arm64, JDK 17) only.** Windows and Linux are untested; nothing in the
  code is platform-specific, but that is an expectation, not a result.

## Ownership

Author: [@Dhruv-246](https://github.com/Dhruv-246). No other collaborators. All code in this
repository is original work written for the BOSS Contributor Hackathon; no third-party source
was copied in. Runtime dependencies (Compose Multiplatform, Decompose, kotlinx, compose-icons)
keep their own licences.

## Compatibility

| | |
|---|---|
| `boss-plugin-api` | 1.0.88 |
| Minimum BOSS | 9.5.9 |
| JDK | 17 |
| Tested on | macOS arm64 (Windows/Linux untested) |

## License

Apache-2.0, matching the BOSS core.
