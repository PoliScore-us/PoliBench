# PoliBench

PoliBench is a 🚨WORK IN PROGRESS🚨

PoliBench is a Java CLI benchmark for evaluating whether an AI model can reason about legislation using the seven structural pillars defined by the PoliScore framework.

It packages small benchmark suites, sends them through a provider-specific batch pipeline, and grades the returned responses into a `polibench_results.json` file with per-pillar scores.

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
- submit and poll OpenAI Batch jobs
- parse an existing OpenAI batch output file with `--results-only`
- emit a machine-readable results file with per-pillar pass rates
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
- [OpenAIProvider.java](src/main/java/us/poliscore/polibench/providers/OpenAIProvider.java): OpenAI Batch integration
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
Usage: polibench [-hVy] [-m=<modelId>] [-o=<outputFile>]
                 [--results-only=<existingBatchResult>] [--suites=<suitesDir>]
```

Options:

- `--model` / `-m`: model identifier, currently `mock`, `gpt-5.2`, `gpt-5.1`, `gpt-5`, `gpt-5-mini`, `gpt-5-nano`, `gpt-4o`, or `gpt-4o-mini`
- `--output` / `-o`: output path for the final `polibench_results.json`
- `--suites`: directory of suite JSON files; if omitted, the bundled suites are used
- `--results-only`: skip execution and grade an existing batch output file
- `--yes` / `-y`: auto-accept the estimated execution cost

## Example Commands

Run the bundled benchmark in mock mode:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model mock --yes
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

Grade a previously downloaded OpenAI batch output file without submitting anything:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model gpt-5-mini --results-only ./batch-output.jsonl --output ./polibench_results.json
```

Run a real OpenAI batch job with automatic cost acceptance:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model gpt-5-mini --yes
```

Run a real OpenAI batch job and review the estimated cost interactively first:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model gpt-5.1
```

## OpenAI Setup

PoliBench automatically uses the OpenAI provider when `--model` starts with `gpt-`.

The OpenAI integration is implemented in [OpenAIProvider.java](src/main/java/us/poliscore/polibench/providers/OpenAIProvider.java), and configuration is loaded automatically by [ConfigLoader.java](src/main/java/us/poliscore/polibench/providers/ConfigLoader.java).

Create a file named `polibench.properties` in the project root with:

```properties
openai.api.key=YOUR_OPENAI_API_KEY
```

Once that file exists, PoliBench will automatically read it from the current working directory when you run the CLI. If there is no local file, it also attempts to load `polibench.properties` from the classpath.

With that in place, this is enough to run against OpenAI:

```bash
java -jar target/polibench-1.0-SNAPSHOT.jar --model gpt-5-mini
```

What happens during an OpenAI run:

1. PoliBench loads the benchmark suites.
2. It estimates token usage and batch cost.
3. It prompts you to continue unless you passed `--yes`.
4. It writes a JSONL batch input file locally.
5. It uploads that file to OpenAI's `/v1/files`.
6. It creates a batch job against `/v1/chat/completions`.
7. It polls until the batch completes.
8. It downloads the output file, parses it, and writes `polibench_results.json`.

If `polibench.properties` is missing or `openai.api.key` is unset, OpenAI execution will fail with a configuration error when submission starts.

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
      "requirement": "Evaluate whether the policy distributes benefits and burdens fairly.",
      "prompt": "A bill ...",
      "expected": "FAIL"
    }
  ]
}
```

Notes:

- `pillar` must match one of the enum values used by the benchmark model layer
- `expected` must be `PASS` or `FAIL`
- stable `id` values are useful when comparing runs or parsing existing results

## Output

The result file is a JSON object keyed by pillar, with:

- `totalTasks`
- `passedTasks`
- `scorePercentage`

This makes it suitable for downstream ingestion by other PoliScore tooling.

## Current Limits

The current implementation is intentionally narrow:

- OpenAI is the only real provider implemented today
- supported live OpenAI models are currently `gpt-5.2`, `gpt-5.1`, `gpt-5`, `gpt-5-mini`, `gpt-5-nano`, `gpt-4o`, and `gpt-4o-mini`
- the evaluator checks explicit `<PASS>` and `<FAIL>` tokens rather than deeper semantic grading
- bundled suites are still small and should be treated as an early benchmark set, not a finished benchmark corpus

## License

MIT License.
