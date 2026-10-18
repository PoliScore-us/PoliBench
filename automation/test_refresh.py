import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from types import SimpleNamespace
import io
import zipfile
from refresh import (LocalStore, empty_catalog, estimated_cost, fingerprint, plan, publish,
                     validate_catalog, validate_result, PILLARS, S3Store)


class RefreshTest(unittest.TestCase):
    def setUp(self):
        self.description = {"taskCount": 7, "inputTokenUpperBound": 1000, "maxCompletionTokens": 100}
        self.policy = {"include": ["openai/*"], "exclude": ["*:free"], "maxModelsPerRun": 10, "maxRunCostUsd": 1}
        self.model = {"id": "openai/test", "architecture": {"input_modalities": ["text"], "output_modalities": ["text"]},
                      "pricing": {"prompt": "0.0001", "completion": "0.0001"}}
        self.entry = {"id": "run-1", "file": "runs/version/run-1.json", "modelId": "openai/test",
                      "benchmarkFingerprint": "version", "runDate": "2026-10-13T00:00:00Z", "status": "complete"}
        self.archive = {"models": ["openai/test"], "systemPrompt": "prompt", "results": [{
            "modelId": "openai/test", "pillarScores": {pillar: {"totalTasks": 1, "passedTasks": 0, "scorePercentage": 0,
            "tasks": [{"id": pillar, "response": "wrong format", "passed": False, "error": "parse failure"}]}
            for pillar in PILLARS}}]}

    def test_skips_completed_model_but_runs_changed_benchmark_and_force(self):
        catalog = empty_catalog()
        catalog["runs"] = [self.entry]
        self.assertEqual(plan([self.model], catalog, "version", self.policy, self.description)[0], [])
        for version, force in [("changed", False), ("version", True)]:
            self.assertEqual(len(plan([self.model], catalog, version, self.policy, self.description, force)[0]), 1)

    def test_failed_entry_is_retryable(self):
        entry = dict(self.entry, status="failed")
        self.assertEqual(len(plan([self.model], {"runs": [entry]}, "version", self.policy, self.description)[0]), 1)

    def test_filters_and_limits_and_missing_pricing(self):
        free = dict(self.model, id="openai/test:free")
        missing = dict(self.model, id="openai/no-pricing", pricing={})
        pricey = dict(self.model, id="openai/expensive", pricing={"prompt": "1", "completion": "1"})
        planned, skipped = plan([free, missing, pricey, self.model], empty_catalog(), "version", self.policy, self.description)
        self.assertEqual([item["modelId"] for item in planned], ["openai/test"])
        self.assertEqual(len(skipped), 2)
        self.policy["maxModelsPerRun"] = 1
        self.assertEqual(len(plan([self.model, dict(self.model, id="openai/second")], empty_catalog(), "v", self.policy, self.description)[0]), 1)

    def test_explicit_selection_and_unknown_models(self):
        self.assertEqual(len(plan([dict(self.model, id="other/test")], empty_catalog(), "v", self.policy,
                                  self.description, selected=["other/test"])[0]), 1)
        with self.assertRaises(ValueError):
            plan([], empty_catalog(), "v", self.policy, self.description, selected=["missing"])

    def test_claude_gemini_policy_discovers_versions_and_skips_specialized_variants(self):
        policy = json.loads(Path(__file__).with_name("claude-gemini.json").read_text())
        eligible = ["anthropic/claude-sonnet-example", "anthropic/claude-opus-example",
                    "google/gemini-example-pro", "google/gemini-example-flash-preview"]
        excluded = ["openai/test", "anthropic/claude-sonnet-example:batch",
                    "google/gemini-example-pro:free", "google/gemini-example-image",
                    "google/gemini-nano-banana-example", "anthropic/claude-sonnet-latest"]
        models = [dict(self.model, id=model_id) for model_id in eligible + excluded]
        planned, _ = plan(models, empty_catalog(), "v", policy, self.description)
        self.assertEqual({item["modelId"] for item in planned}, set(eligible))

    def test_cost_reserves_all_completion_tokens_and_rejects_invalid_prices(self):
        self.assertEqual(str(estimated_cost(self.model, self.description)), "0.1700")
        for value in ["NaN", "-1", "Infinity"]:
            with self.assertRaises(ValueError):
                estimated_cost(dict(self.model, pricing={"prompt": value, "completion": "0"}), self.description)

    def test_complete_parse_failures_are_publishable_but_partial_results_are_not(self):
        validate_result(self.archive, "openai/test", 7)
        partial = copy.deepcopy(self.archive)
        partial["results"][0]["pillarScores"]["Risk"]["tasks"][0]["response"] = None
        with self.assertRaises(ValueError):
            validate_result(partial, "openai/test", 7)
        with self.assertRaises(ValueError):
            validate_result(self.archive, "openai/test", 8)

    def test_catalog_validation_refuses_corruption(self):
        for catalog in [{}, {"schemaVersion": 1, "runs": [self.entry, self.entry]},
                        {"schemaVersion": 1, "runs": [dict(self.entry, file="runs/../secret")]}]:
            with self.assertRaises(ValueError):
                validate_catalog(catalog)

    def test_publication_order_and_no_catalog_write_when_upload_fails(self):
        store = Mock()
        store.read.return_value = (empty_catalog(), 'etag')
        publish(store, self.entry, self.archive)
        self.assertEqual([call[0] for call in store.mock_calls], ["write_run", "read", "write_catalog"])
        self.assertEqual(store.write_catalog.call_args.args[1], 'etag')
        store.reset_mock()
        store.write_run.side_effect = RuntimeError("upload failed")
        with self.assertRaises(RuntimeError):
            publish(store, self.entry, self.archive)
        store.write_catalog.assert_not_called()

    def test_local_history_retained_and_catalog_survives_failed_run_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LocalStore(directory)
            publish(store, self.entry, self.archive)
            publish(store, dict(self.entry, id="run-2", file="runs/v2/run-2.json", benchmarkFingerprint="v2"), self.archive)
            catalog, _ = store.read()
            self.assertEqual(len(catalog["runs"]), 2)
            self.assertEqual(catalog["benchmarkFingerprint"], "v2")
            with self.assertRaises(FileExistsError):
                publish(store, self.entry, self.archive)
            self.assertEqual(len(store.read()[0]["runs"]), 2)

    def test_fingerprint_ignores_zip_metadata_but_tracks_prompt_suite_and_code(self):
        with tempfile.TemporaryDirectory() as directory:
            def jar(name, text, timestamp, code=b"code"):
                path = Path(directory) / name
                with zipfile.ZipFile(path, "w") as archive:
                    for resource, data in [("system-prompt.txt", text), ("suites/test.json", b"{}"),
                                           ("us/poliscore/polibench/App.class", code)]:
                        archive.writestr(zipfile.ZipInfo(resource, timestamp), data)
                return path
            first = jar("a.jar", b"prompt", (2020, 1, 1, 0, 0, 0))
            second = jar("b.jar", b"prompt", (2026, 1, 1, 0, 0, 0))
            self.assertEqual(fingerprint(first), fingerprint(second))
            self.assertNotEqual(fingerprint(first), fingerprint(jar("c.jar", b"changed", (2020, 1, 1, 0, 0, 0))))
            self.assertNotEqual(fingerprint(first), fingerprint(jar("d.jar", b"prompt", (2020, 1, 1, 0, 0, 0), b"changed")))


class S3StoreTest(unittest.TestCase):
    class ClientError(Exception):
        def __init__(self, code):
            self.response = {"Error": {"Code": code}}

    def setUp(self):
        self.store = S3Store.__new__(S3Store)
        self.store.client = Mock()
        self.store.bucket = "bucket"
        self.store.prefix = "polibench/data"

    def test_rejects_rotating_website_buckets_before_loading_aws_sdk(self):
        for bucket in ["poliscore-website1", "poliscore-website2"]:
            with self.assertRaisesRegex(ValueError, "permanent bucket"):
                S3Store(bucket, "polibench/data")

    def test_missing_catalog_is_empty_but_access_denied_is_fatal(self):
        with patch.dict("sys.modules", {"botocore.exceptions": SimpleNamespace(ClientError=self.ClientError)}):
            self.store.client.get_object.side_effect = self.ClientError("NoSuchKey")
            self.assertEqual(self.store.read(), (empty_catalog(), None))
            self.store.client.get_object.side_effect = self.ClientError("AccessDenied")
            with self.assertRaises(self.ClientError):
                self.store.read()

    def test_catalog_uses_etag_condition_and_run_is_immutable(self):
        self.store.write_catalog(empty_catalog(), '"old-etag"')
        arguments = self.store.client.put_object.call_args.kwargs
        self.assertEqual(arguments["IfMatch"], '"old-etag"')
        self.assertEqual(arguments["CacheControl"], "public,max-age=60,must-revalidate")
        self.store.write_catalog(empty_catalog(), None)
        self.assertEqual(self.store.client.put_object.call_args.kwargs["IfNoneMatch"], "*")
        self.store.write_run("runs/v/run.json", {})
        arguments = self.store.client.put_object.call_args.kwargs
        self.assertEqual(arguments["Key"], "polibench/data/runs/v/run.json")
        self.assertEqual(arguments["IfNoneMatch"], "*")

    def test_read_preserves_etag_and_rejects_corrupt_catalog(self):
        with patch.dict("sys.modules", {"botocore.exceptions": SimpleNamespace(ClientError=self.ClientError)}):
            self.store.client.get_object.return_value = {"ETag": '"etag"', "Body": io.BytesIO(json.dumps(empty_catalog()).encode())}
            self.assertEqual(self.store.read()[1], '"etag"')
            self.store.client.get_object.return_value = {"ETag": '"etag"', "Body": io.BytesIO(b'{}')}
            with self.assertRaises(ValueError):
                self.store.read()
