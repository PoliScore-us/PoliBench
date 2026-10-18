#!/usr/bin/env python3
"""Discover, evaluate and publish versioned benchmarks. Offline mode never contacts AWS or OpenRouter."""
import argparse
from datetime import datetime, timezone
from decimal import Decimal
import fnmatch
import hashlib
import json
import math
import os
import re
from pathlib import Path
import subprocess
import sys
import urllib.request
import uuid
import zipfile

PILLARS = ("Precision", "Evidence", "Feasibility", "Budget", "Fairness", "Governance", "Risk")


def fingerprint(jar):
    digest = hashlib.sha256(b"polibench-fingerprint-v1\0")
    # ZIP timestamps, credentials, signatures and third-party implementation details are not inputs.
    with zipfile.ZipFile(jar) as archive:
        names = sorted(name for name in archive.namelist() if
                       name.startswith("us/poliscore/polibench/") and name.endswith(".class")
                       or name.startswith("suites/") and name.endswith(".json")
                       or name == "system-prompt.txt")
        if not names or "system-prompt.txt" not in names:
            raise ValueError("Not a standalone PoliBench jar")
        for name in names:
            data = archive.read(name)
            digest.update(name.encode() + b"\0" + len(data).to_bytes(8, "big") + data)
    return digest.hexdigest()


def empty_catalog():
    return {"schemaVersion": 1, "benchmarkFingerprint": None, "runs": []}


def validate_catalog(catalog):
    if catalog.get("schemaVersion") != 1 or not isinstance(catalog.get("runs"), list):
        raise ValueError("Unsupported or corrupt catalog; refusing to replace it")
    ids = set()
    for run in catalog["runs"]:
        if not all(isinstance(run.get(key), str) and run[key] for key in
                   ("id", "file", "modelId", "benchmarkFingerprint", "runDate")):
            raise ValueError("Invalid catalog entry")
        if run["id"] in ids or not run["file"].startswith("runs/") or ".." in run["file"].split("/"):
            raise ValueError("Duplicate or unsafe catalog entry")
        ids.add(run["id"])
    return catalog


def estimated_cost(model, description):
    pricing = model.get("pricing", {})
    values = []
    for field in ("prompt", "completion"):
        value = Decimal(str(pricing[field]))
        if not value.is_finite() or value < 0:
            raise ValueError("Missing or invalid pricing")
        values.append(value)
    request_price = Decimal(str(pricing.get("request", "0")))
    if not request_price.is_finite() or request_price < 0:
        raise ValueError("Invalid per-request price")
    return (values[0] * description["inputTokenUpperBound"]
            + values[1] * description["maxCompletionTokens"] * description["taskCount"]
            + request_price * description["taskCount"])


def resolve_selectors(models, selectors):
    """Resolve one concrete release per slot before checking completion history."""
    resolved = []
    for selector in selectors:
        if "id" in selector:
            matches = [model for model in models if model["id"] == selector["id"]]
        else:
            pattern = re.compile(selector["pattern"])
            matches = [model for model in models if pattern.fullmatch(model["id"])]
        if not matches:
            raise ValueError("No available model for slot: " + selector["name"])
        def release_key(model):
            match = re.fullmatch(selector["pattern"], model["id"]) if "pattern" in selector else None
            version = tuple(int(part) for part in match.group(1).split(".")) if match else ()
            if selector.get("orderBy") == "created":
                return model.get("created", 0), version, model["id"]
            return version, model.get("created", 0), model["id"]
        resolved.append(max(matches, key=release_key)["id"])
    return list(dict.fromkeys(resolved))


def plan(models, catalog, version, policy, description, force=False, selected=None):
    if selected is None and "selectors" in policy:
        selected = resolve_selectors(models, policy["selectors"])
    tested = {run["modelId"] for run in catalog["runs"]
              if run["benchmarkFingerprint"] == version and run.get("status") == "complete"}
    candidates, skipped, unavailable = [], [], set(selected or [])
    for model in models:
        model_id = model["id"]
        unavailable.discard(model_id)
        if selected:
            eligible = model_id in selected
        else:
            eligible = (any(fnmatch.fnmatchcase(model_id, pattern) for pattern in policy["include"])
                        and not any(fnmatch.fnmatchcase(model_id, pattern) for pattern in policy["exclude"]))
        if not eligible or model_id in tested and not force:
            continue
        architecture = model.get("architecture", {})
        if "text" not in architecture.get("output_modalities", []) or "text" not in architecture.get("input_modalities", []):
            skipped.append({"modelId": model_id, "reason": "No text input/output support"})
            continue
        try:
            cost = estimated_cost(model, description)
        except (KeyError, ValueError, ArithmeticError):
            skipped.append({"modelId": model_id, "reason": "Missing or invalid pricing"})
            continue
        candidates.append((model, cost))
    if unavailable:
        raise ValueError("Requested models unavailable: " + ", ".join(sorted(unavailable)))
    # Cheapest first permits useful progress under a bounded budget; ties are deterministic.
    candidates.sort(key=lambda item: (item[1], item[0]["id"]))
    planned, reserved = [], Decimal("0")
    limit = Decimal(str(policy["maxRunCostUsd"]))
    if not limit.is_finite() or limit <= 0 or policy["maxModelsPerRun"] < 1:
        raise ValueError("Budget and model count must be positive")
    for model, cost in candidates:
        if len(planned) >= policy["maxModelsPerRun"] or reserved + cost > limit:
            reason = "Model count limit" if len(planned) >= policy["maxModelsPerRun"] else "Run budget limit"
            skipped.append({"modelId": model["id"], "reason": reason})
            continue
        planned.append({"modelId": model["id"], "estimatedMaximumCostUsd": str(cost)})
        reserved += cost
    return planned, skipped


def validate_result(archive, model_id, task_count):
    if archive.get("models") != [model_id] or len(archive.get("results", [])) != 1:
        raise ValueError("Runner output must contain exactly the requested model")
    result = archive["results"][0]
    if result.get("modelId") != model_id or not archive.get("systemPrompt"):
        raise ValueError("Missing result provenance")
    scores = result.get("pillarScores", {})
    if set(scores) != set(PILLARS):
        raise ValueError("Result does not cover all seven pillars")
    total = 0
    ids = set()
    for score in scores.values():
        tasks = score.get("tasks", [])
        if not isinstance(score.get("totalTasks"), int) or score["totalTasks"] != len(tasks) or not tasks:
            raise ValueError("Incomplete task results")
        total += len(tasks)
        passed = sum(task.get("passed") is True for task in tasks)
        expected_percentage = passed / len(tasks) * 100
        if score.get("passedTasks") != passed or not math.isclose(score.get("scorePercentage", -1), expected_percentage):
            raise ValueError("Inconsistent scores")
        for task in tasks:
            if not task.get("response") or not task.get("id") or task["id"] in ids:
                raise ValueError("Missing response or duplicate task ID")
            ids.add(task["id"])
    if total != task_count:
        raise ValueError("Task count differs from benchmark")
    # Parsing failures are legitimate benchmark outcomes; transport failures never produce a complete archive.
    return archive


class LocalStore:
    def __init__(self, root):
        self.root = Path(root)

    def read(self):
        path = self.root / "catalog.json"
        return (validate_catalog(json.loads(path.read_text())), None) if path.exists() else (empty_catalog(), None)

    def write_run(self, key, archive):
        path = self.root / key
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("x") as stream:
            json.dump(archive, stream, indent=2)

    def write_catalog(self, catalog, etag):
        self.root.mkdir(parents=True, exist_ok=True)
        temporary = self.root / "catalog.tmp"
        temporary.write_text(json.dumps(catalog, indent=2))
        temporary.replace(self.root / "catalog.json")


class S3Store:
    def __init__(self, bucket, prefix):
        if re.fullmatch(r"poliscore-website\d+", bucket):
            raise ValueError("PoliBench results require a permanent bucket, not a rotating website bucket")
        import boto3
        self.client = boto3.client("s3")
        self.bucket = bucket
        self.prefix = prefix.strip("/")
        if not self.prefix or ".." in self.prefix.split("/"):
            raise ValueError("S3 prefix must be a non-root path")

    def key(self, name):
        return self.prefix + "/" + name

    def read(self):
        from botocore.exceptions import ClientError
        try:
            response = self.client.get_object(Bucket=self.bucket, Key=self.key("catalog.json"))
        except ClientError as error:
            if error.response["Error"]["Code"] != "NoSuchKey":
                raise  # Access denial/network failures must never be treated as an empty catalog.
            return empty_catalog(), None
        return validate_catalog(json.loads(response["Body"].read())), response["ETag"]

    def write_run(self, key, archive):
        self.client.put_object(Bucket=self.bucket, Key=self.key(key),
                               Body=json.dumps(archive).encode(), ContentType="application/json",
                               CacheControl="public,max-age=31536000,immutable", IfNoneMatch="*")

    def write_catalog(self, catalog, etag):
        condition = {"IfMatch": etag} if etag else {"IfNoneMatch": "*"}
        self.client.put_object(Bucket=self.bucket, Key=self.key("catalog.json"),
                               Body=json.dumps(catalog).encode(), ContentType="application/json",
                               CacheControl="public,max-age=60,must-revalidate", **condition)


def publish(store, entry, archive):
    store.write_run(entry["file"], archive)
    # Read latest catalog just before commit. CAS prevents another writer silently losing history.
    catalog, etag = store.read()
    catalog["runs"].append(entry)
    catalog["benchmarkFingerprint"] = entry["benchmarkFingerprint"]
    catalog["generatedAt"] = datetime.now(timezone.utc).isoformat()
    store.write_catalog(catalog, etag)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--policy", type=Path, default=Path(__file__).with_name("models.json"))
    parser.add_argument("--bucket", default=os.getenv("POLIBENCH_RESULTS_BUCKET", "poliscore-prod-public"))
    parser.add_argument("--prefix", default=os.getenv("POLIBENCH_RESULTS_PREFIX", "polibench/data"))
    parser.add_argument("--local-store", type=Path, help="Offline mock run; no external calls")
    parser.add_argument("--work-dir", type=Path, default=Path("results/automation"))
    parser.add_argument("--dry-run", action="store_true", help="Plan only, no model requests or writes to storage")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--models", help="Comma-separated exact IDs; overrides discovery filters")
    parser.add_argument("--max-models", type=int, help="Positive model-count override (defaults to policy)")
    args = parser.parse_args()
    if args.max_models is not None and args.max_models < 1:
        parser.error("--max-models must be a positive integer")
    args.jar = args.jar.resolve()
    args.work_dir.mkdir(parents=True, exist_ok=True)
    policy = json.loads(args.policy.read_text())
    if args.max_models is not None:
        policy["maxModelsPerRun"] = args.max_models
    if policy["modelTimeoutSeconds"] < 1:
        raise ValueError("Timeout must be positive")
    description_path = args.work_dir / "description.json"
    subprocess.run(["java", "-jar", str(args.jar), "--describe", str(description_path)], check=True, timeout=60)
    description = json.loads(description_path.read_text())
    version = fingerprint(args.jar)
    if args.local_store:
        store = LocalStore(args.local_store)
        models = [{"id": "mock", "architecture": {"input_modalities": ["text"], "output_modalities": ["text"]},
                   "pricing": {"prompt": "0", "completion": "0"}}]
        policy.pop("selectors", None)
        policy["include"] = ["mock"]
        policy["exclude"] = []
    else:
        if not args.bucket:
            raise ValueError("Set POLIBENCH_RESULTS_BUCKET or --bucket")
        if not os.getenv("OPENROUTER_API_KEY"):
            raise ValueError("Set OPENROUTER_API_KEY")
        store = S3Store(args.bucket, args.prefix)
        request = urllib.request.Request("https://openrouter.ai/api/v1/models",
                                        headers={"Authorization": "Bearer " + os.environ["OPENROUTER_API_KEY"]})
        with urllib.request.urlopen(request, timeout=30) as response:
            models = json.load(response)["data"]
    catalog, _ = store.read()
    selected = [model.strip() for model in args.models.split(",") if model.strip()] if args.models else None
    planned, skipped = plan(models, catalog, version, policy, description, args.force, selected)
    summary = {"benchmarkFingerprint": version, "planned": planned, "skipped": skipped,
               "completed": [], "failed": [], "dryRun": args.dry_run}
    summary_path = args.work_dir / "summary.json"
    summary_path.write_text(json.dumps(summary, indent=2))
    print(json.dumps({"benchmarkFingerprint": version, "planned": planned, "skipped": skipped}, indent=2), flush=True)
    if not args.dry_run:
        for candidate in planned:
            model_id = candidate["modelId"]
            run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex
            output = (args.work_dir / (run_id + ".json")).resolve()
            try:
                # No automatic retries: a timed-out request may already have been billed.
                subprocess.run(["java", "-Dpolibench.openrouter.threads=4", "-Dpolibench.openrouter.rpm=60",
                                "-jar", str(args.jar), "--model", model_id, "--yes", "--output", str(output)],
                               check=True, timeout=policy["modelTimeoutSeconds"])
                archive = validate_result(json.loads(output.read_text()), model_id, description["taskCount"])
                entry = {"id": run_id, "file": f"runs/{version}/{run_id}.json", "modelId": model_id,
                         "benchmarkFingerprint": version, "runDate": datetime.now(timezone.utc).isoformat(),
                         "status": "complete", "sourceCommit": os.getenv("GITHUB_SHA", "local"), **candidate}
                archive.update({"schemaVersion": 1, "benchmarkFingerprint": version,
                                "runDate": entry["runDate"], "sourceCommit": entry["sourceCommit"],
                                "generation": {key: description[key] for key in
                                               ("maxCompletionTokens", "temperature", "reasoningEffort")}})
                output.write_text(json.dumps(archive, indent=2))
                publish(store, entry, archive)
                summary["completed"].append(entry)
            except Exception as error:
                summary["failed"].append({"modelId": model_id, "error": str(error)})
                print(f"Failed {model_id}: {error}", file=sys.stderr, flush=True)
            finally:
                summary_path.write_text(json.dumps(summary, indent=2))
    github_summary = os.getenv("GITHUB_STEP_SUMMARY")
    if github_summary:
        with open(github_summary, "a") as stream:
            stream.write(f"## PoliBench refresh\n\nBenchmark: `{version}`\n\n"
                         f"Planned: {len(planned)} · Completed: {len(summary['completed'])} · "
                         f"Failed: {len(summary['failed'])} · Skipped: {len(skipped)}\n\n"
                         "See the uploaded summary.json for model selection and failures.\n")
    return 1 if summary["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
