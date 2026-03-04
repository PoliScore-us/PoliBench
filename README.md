# PoliBench

PoliBench is a 🚨WORK IN PROGRESS🚨

PoliBench is a Java CLI benchmark for evaluating whether AI models can reason about legislation using the seven structural pillars defined by the PoliScore framework.

It packages small benchmark suites, sends them through openrouter.ai, and grades the returned responses into a `polibench_results.json` archive with the evaluated model list and per-model, per-pillar scores.

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
- generate batch requests for supported models
- execute OpenRouter chat-completion requests against any OpenRouter model ID
- parse an existing OpenRouter output file with `--results-only`
- emit a machine-readable results file with the evaluated model list and per-model pass rates
- estimate batch cost before execution
- run fully offline in mock mode for local testing

## How It Works

Each suite contains one or more benchmark tasks. For each task, PoliBench:

1. Builds a system prompt and user prompt.
2. Generates a batch request.
3. Sends the batch to the configured provider, or reads an existing result file.
4. Grades the model response by checking whether it concludes with either `<PASS>` or `<FAIL>`.
5. Aggregates results into pillar scores.

The default evaluator is intentionally simple: each task declares an expected outcome, and the model must end with the matching token.

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

Only `openrouter.api.key` is required. The `openrouter.http.referer` and `openrouter.title` headers are optional but recommended for OpenRouter app attribution.

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

OpenRouter does not currently expose a provider-side batch flow in this implementation. PoliBench therefore executes a local pseudo-batch: it keeps the JSONL request/output artifacts, but sends the requests sequentially through OpenRouter.

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
- `runDate`
- `systemPrompt`
- `pillarScores`

Each `pillarScores` entry contains:

- `totalTasks`
- `passedTasks`
- `scorePercentage`
- `tasks`

This preserves the benchmark details for each model while making model-to-model comparisons explicit in a single archive.

## Current Limits

The current implementation is intentionally narrow:

- OpenRouter is the only real provider implemented today
- this implementation accepts any OpenRouter model ID, but the exact set of available models and prices comes from OpenRouter at runtime
- the evaluator checks explicit `<PASS>` and `<FAIL>` tokens rather than deeper semantic grading
- bundled suites are still small and should be treated as an early benchmark set, not a finished benchmark corpus

## License

MIT License.
