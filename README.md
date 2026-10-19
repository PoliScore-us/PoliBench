# PoliBench

Latest test results : [https://poliscore.us/polibench](https://poliscore.us/polibench)

PoliBench is a Java CLI benchmark for evaluating whether AI models can reason about legislation using the seven structural pillars defined by the PoliScore framework.

It is a standalone Java 21 application with a versioned system prompt. It packages small benchmark suites, sends them through openrouter.ai, and grades the returned responses into a `polibench_results.json` archive with the evaluated model list and per-model, per-pillar scores.

## What It Supports

The bundled benchmark currently covers all seven PoliScore pillars:

- `Precision`
- `Evidence`
- `Feasibility`
- `Budget`
- `Fairness`
- `Governance`
- `Risk`

Out of the box, PoliBench can:

- load the bundled benchmark suites from `src/main/resources/suites/`
- load alternate suite JSON files from a directory you provide with `--suites`
- generate requests for supported models
- execute OpenRouter chat-completion requests against any OpenRouter model ID
- parse an existing OpenRouter output file with `--results-only`
- emit a machine-readable results file with the evaluated model list and per-model pass rates
- estimate batch cost before execution
- run fully offline in mock mode for local testing

## How It Works

Each suite contains one or more benchmark tasks. For each task, PoliBench:

1. Builds a system prompt and user prompt.
2. Generates a batch request.
3. Sends requests to the configured provider, or reads an existing result file.
4. Grades the target structural pillar against its expected `<PASS>` or `<FAIL>` verdict.
5. Aggregates results into pillar scores.

Each task declares an expected outcome for one pillar. The evaluator isolates the Structural Analysis section, requires all seven numbered pillars with analysis text and a `<PASS>` or `<FAIL>` verdict, and compares the target pillar to the expected outcome. It accepts markdown headings and uses the last verdict within each pillar. Unrelated report fields are not graded.

## Project Layout

- [App.java](src/main/java/us/poliscore/polibench/App.java): CLI entrypoint and benchmark pipeline
- [OpenRouterProvider.java](src/main/java/us/poliscore/polibench/providers/OpenRouterProvider.java): OpenRouter integration
- [MockProvider.java](src/main/java/us/poliscore/polibench/providers/MockProvider.java): offline/mock execution path
- [BenchmarkEvaluator.java](src/main/java/us/poliscore/polibench/eval/BenchmarkEvaluator.java): response grading
- [src/main/resources/suites](src/main/resources/suites): bundled benchmark suites

## Build

Compile the project:

```bash
mvn compile
```

Build the shaded jar:

```bash
mvn package
```

The packaged CLI jar is written to `target/`.

## CLI Usage

The CLI currently exposes:

```text
Usage: polibench [-hVy] [-m=<modelIds>]... [-o=<outputFile>]
                 [--results-only=<existingBatchResult>] [--suites=<suitesDir>]
```

Options:

- `--model` / `-m`: one or more model identifiers; repeat the option or pass a comma-separated list such as `--model mock --model openai/gpt-5.2` or `--model mock,openai/gpt-5.2`
- `--output` / `-o`: output path for the final `polibench_results.json`
- `--suites`: directory of suite JSON files; if omitted, the bundled suites are used
- `--results-only`: skip execution and grade an existing batch output file; this currently supports exactly one model
- `--yes` / `-y`: auto-accept the estimated execution cost

## Example Commands

Run the bundled benchmark in mock mode:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model mock --yes
```

Run the bundled benchmark against multiple models in one archive:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model mock,openai/gpt-5.2 --yes
```

This writes to:

```text
results/polibench_results.json
```

Write the results to a custom location:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model mock --yes --output /tmp/polibench_results.json
```

Run the benchmark against a custom suites directory:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model mock --yes --suites ./my-suites
```

Grade a previously generated OpenRouter output file without submitting anything:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model openai/gpt-5.2 --results-only ./results/openrouter_batch_output_example.jsonl --output ./results/polibench_results.json
```

Run a real OpenRouter-backed evaluation with automatic cost acceptance:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model openai/gpt-5.2 --yes
```

Run a real OpenRouter-backed evaluation and review the estimated cost interactively first:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model openai/gpt-5.2
```

## OpenRouter Setup

PoliBench automatically uses the OpenRouter provider for any non-`mock` model ID.

The OpenRouter integration is implemented in [OpenRouterProvider.java](src/main/java/us/poliscore/polibench/providers/OpenRouterProvider.java), and configuration is loaded automatically by [ConfigLoader.java](src/main/java/us/poliscore/polibench/providers/ConfigLoader.java).

Create a file named `polibench.properties` in the project root with:

```properties
openrouter.api.key=YOUR_OPENROUTER_API_KEY
openrouter.http.referer=https://your-site.example
openrouter.title=PoliBench
```

Once that file exists, PoliBench will automatically read it from the current working directory when you run the CLI. If there is no local file, it also attempts to load `polibench.properties` from the classpath.

Alternatively, set `OPENROUTER_API_KEY`, `OPENROUTER_HTTP_REFERER`, and `OPENROUTER_TITLE` environment variables. Environment values override properties. Only `openrouter.api.key` / `OPENROUTER_API_KEY` is required. The `openrouter.http.referer` and `openrouter.title` headers are optional but recommended for OpenRouter app attribution.

With that in place, this is enough to run against OpenRouter:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model openai/gpt-5.2
```

What happens during an OpenRouter run:

1. PoliBench loads the benchmark suites.
2. It estimates token usage and batch cost.
3. It prompts you to continue unless you passed `--yes`.
4. It writes a JSONL batch input file locally.
5. It executes the generated requests against OpenRouter's `/api/v1/chat/completions`.
6. It writes the raw provider responses to `results/openrouter_batch_output_<batch-id>.jsonl`.
7. It parses that output file and writes `polibench_results.json`.

OpenRouter does not currently expose a provider-side batch flow in this implementation. PoliBench therefore executes a local batch using bounded worker concurrency and a shared request rate limit, while preserving JSONL response artifacts. Requests have a five-minute timeout and an 8,192-token completion cap.

If `polibench.properties` is missing or `openrouter.api.key` is unset, OpenRouter execution will fail with a configuration error when submission starts.

## Suite Format

Each suite file is JSON with this shape:

```json
{
  "name": "Fairness Suite",
  "pillar": "Fairness",
  "description": "Tests distributional reasoning.",
  "tasks": [
    {
      "id": "optional-stable-id",
      "billText": "A bill ...",
      "expected": "FAIL"
    }
  ]
}
```

Notes:

- `pillar` must match one of the enum values used by the benchmark model layer
- `expected` must be `PASS` or `FAIL`
- `id` is optional; if omitted, PoliBench generates one at runtime

## Output

The result file is a JSON archive with:

- `runDate`: when the archive was written
- `models`: the ordered list of evaluated model IDs
- `results`: one entry per model run

Each item in `results` contains:

- `modelId`
- `allNonparseable`
- `pillarScores`

Each `pillarScores` entry contains:

- `totalTasks`
- `passedTasks`
- `scorePercentage`
- `tasks`

This preserves the benchmark details for each model while making model-to-model comparisons explicit in a single archive.

## Current Limits

The current implementation is intentionally narrow:

- The current set of tests are small and may not be entirely comprehensive. Submission of additional tests (via pull requests) from the community would be greatly appreciated!
- There is some ambiguity in the grading of some of the current tests. This is becoming apparent as more recent models might be "outsmarting" the benchmark.
- A response must include all seven structural pillars and their verdicts to be parseable.
- The versioned benchmark prompt is maintained in this repository; changes in the separate PoliScore repository must be adopted explicitly.

## Automated results

A single GitHub Actions workflow supports manual execution and Sunday-night
refreshes. It discovers models, detects changed benchmark inputs, runs missing
model/version pairs, and publishes immutable JSON results plus a live catalog to
S3. See [automation setup and operation](automation/README.md) for GitHub variables,
OIDC permissions, spending policy, frontend setup, recovery, and offline checks.

Default refreshes resolve eleven curated slots from the live OpenRouter catalog:
latest standard GPT Sol and Luna, Claude Sonnet and Haiku, pinned Gemini 3.8
Flash and Gemini 4 Argon, latest standard numbered Grok, and latest DeepSeek Pro
and Flash, and latest standard Qwen Max and Flash. Both pinned Gemini models request high reasoning; GPT models use
medium reasoning. The exact selected IDs are saved in the run plan and results.

`automation/models.json` defines these slots. Numeric version ordering selects
release families, with catalog creation time selecting dated revisions. Grok uses
catalog creation time because its numbering is not decimal-ordered (4.20 predates
4.7). Full-ID matching excludes Pro upgrades of GPT, Claude Opus/Fable, batch,
free, image, heavy, multi-agent and experimental variants. A missing slot fails
planning explicitly instead of silently substituting another tier. Completed
latest releases are skipped without falling back to older releases.

The existing $25 budget and updated 11-model count limit remain; plan diagnostics
identify model-count and budget skips separately. Explicit manual `--models`
continues to override curated selection. The optional legacy
`automation/claude-gemini.json` policy selects the broader backfill, not the
scheduled default. Publication remains human-operated.

Use `--describe /tmp/benchmark.json` to inspect request counts, prompt and generation
limits without executing a provider. Execution errors now return a nonzero CLI
exit code. The standalone runner no longer depends on the PoliScore core.

## License

MIT License.
