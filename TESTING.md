# Test plan

How to verify BotTest, in three steps of increasing setup cost. Steps 1 and 2 need
neither BOSS nor an AI provider and are the fastest way to check the work is real.

| | |
|---|---|
| **Tested commit** | [`28d430c`](https://github.com/Dhruv-246/boss-plugin-bottest/commit/28d430c) on `main` |
| **Release** | [v0.1.0](https://github.com/Dhruv-246/boss-plugin-bottest/releases/tag/v0.1.0), built JAR attached |
| **Compatibility** | `boss-plugin-api` 1.0.88 · minimum BOSS 9.5.9 · JDK 17 |
| **Verified on** | macOS arm64. Windows and Linux untested. |
| **CI** | green on `28d430c` — [run 36167343195](https://github.com/Dhruv-246/boss-plugin-bottest/actions/runs/36167343195) |

## Step 1 — Unit tests (~1 min, nothing external)

```bash
git clone https://github.com/Dhruv-246/boss-plugin-bottest
cd boss-plugin-bottest
./gradlew test
```

**Expected:** 152 tests pass. No chatbot, AI provider, network service or BOSS install
is required — the HTTP transport and the LLM judge are both behind interfaces with fakes.

Covers: request encoding and JSON-injection safety, response extraction failures, timeouts,
HTTP errors, malformed and empty responses, latency budgets, required/forbidden phrases,
scoring, per-category aggregation, suite parsing and validation, path-traversal attempts,
oversized suite files, credential redaction, and judge unavailability.

`./gradlew build` additionally assembles the loadable JAR.

## Step 2 — End to end against a real chatbot (~2 min, no BOSS)

Any HTTP endpoint that accepts `POST {"message": "..."}` and returns `{"response": "..."}`
works. A ten-line stub is enough.

```bash
mkdir -p ~/.bottest/suites
cp examples/basic-suite.json ~/.bottest/suites/
# edit target.url in that file to point at your endpoint
```

Then run a suite through `BotTestRunner` (or via the MCP tool once installed in BOSS).

**Expected:** the suite is discovered from disk, one real HTTP request is made per case,
and the report is JSON containing `summary` (total/passed/failed/errors, `overallScore`,
`passRate`, `errorRate`, `averageLatencyMs`), `categories` with a per-category `passRate`,
`failedTestIds`, and a reason for each failure.

**Observed on this commit**, against a local mock bot with safe sample data:

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

That `notes` line is the report declining to imply that ungraded rubric criteria passed.

## Step 3 — Inside BOSS

```bash
./gradlew buildPluginJar     # -> build/libs/boss-plugin-bottest-0.1.0.jar
```

Install via **Toolbox → From File**, or hot reload through Tool Evolver:

```json
{
  "plugin_id": "ai.rever.boss.plugin.dynamic.bottest",
  "jar_path": "/absolute/path/to/build/libs/boss-plugin-bottest-0.1.0.jar"
}
```

Enable `bottest_info`, `bottest_list_suites` and `bottest_run_suite` under **Toolbox → MCP**,
then from an attached agent:

| Tool call | Expected |
|---|---|
| `bottest_info` | plugin version, and whether the LLM judge is available |
| `bottest_list_suites` | `basic-suite`, its name, description, `testCount` 8, and its categories |
| `bottest_run_suite {"suite_id": "basic-suite"}` | the JSON report from Step 2, as `mcp__boss__bottest_run_suite` |

## Infrastructure blocker — not a failing test

**Step 3 has not been executed.** No test fails in it; the step was never reached.

I could not bring up a BOSS host on this machine. Supabase is unconfigured locally, and in
`BossAppWithAuth` the host renders `BossApp` only on `AuthState.Authenticated`. `DefaultPlugin`
— which calls `loadExternalPlugins()` — is constructed inside `BossApp`, so with no
authenticated window no external plugin is ever loaded. The supported credential-free path
(`runLocal` plus a local Supabase) needs Docker and the Supabase CLI, which I judged a worse
use of the remaining time than getting the plugin itself right.

**Consequently unverified by execution:** the MCP bridge (whether the three tools surface as
`mcp__boss__bottest_*`) and the `AiGatewayAPI` judge path. Both are covered by unit tests and
were checked against the BossConsole and boss-plugin-api sources — correct API signatures,
correct lifecycle, correct classloading assumptions — but reading source is not running code,
and I am not claiming otherwise.

Steps 1 and 2 are fully executed and reproducible by a reviewer.

## Other known limitations

- The LLM judge needs the **AI Gateway** plugin, which BOSS does not install by default.
  Without it the judge is skipped, never failed, and the report says so in `notes`.
- Cases run sequentially, so a large suite against a slow bot takes a while.
- Multi-turn history is sent in one request rather than replayed turn by turn — correct for a
  stateless endpoint, wrong for a bot holding server-side session state.

More detail in the [README](README.md#known-limitations).
