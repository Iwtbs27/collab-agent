import importlib.util
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch


BRIDGE_PATH = Path(__file__).parents[1] / "benchmarks" / "webarena_bridge.py"


class FakeEvaluatorResult:
    evaluator_name = "AgentResponseEvaluator"

    def model_dump(self, **_kwargs):
        return {
            "evaluator_name": self.evaluator_name,
            "status": "failure",
            "score": 0.0,
            "error_msg": "retrieved data did not match",
            "assertions": [{"actual": ["wrong"], "expected": ["right"]}],
            "actual": {"retrieved_data": ["wrong"]},
            "actual_normalized": {"retrieved_data": ["wrong"]},
            "expected": {"retrieved_data": ["right"]},
            "should_not_exist": False,
        }


class FakeEvaluation:
    status = "failure"
    score = 0.0
    error_msg = "one evaluator failed"
    evaluators_results = [FakeEvaluatorResult()]


class WebArenaBridgeTests(unittest.TestCase):
    def _load_bridge(self):
        package = types.ModuleType("webarena_verified")
        api = types.ModuleType("webarena_verified.api")
        api.WebArenaVerified = object
        with patch.dict(sys.modules, {"webarena_verified": package, "webarena_verified.api": api}):
            spec = importlib.util.spec_from_file_location("webarena_bridge_under_test", BRIDGE_PATH)
            module = importlib.util.module_from_spec(spec)
            assert spec.loader is not None
            spec.loader.exec_module(module)
            return module

    def test_parses_explicit_local_site_urls(self):
        bridge = self._load_bridge()

        self.assertEqual(
            bridge.parse_site_urls(
                ["shopping=http://localhost:7770", "shopping_admin=http://localhost:7771/admin"]
            ),
            {
                "shopping": "http://localhost:7770",
                "shopping_admin": "http://localhost:7771/admin",
            },
        )

    def test_payload_retains_evaluator_diagnostics(self):
        bridge = self._load_bridge()

        payload = bridge.build_result_payload("LB04", FakeEvaluation())

        self.assertEqual(payload["case_id"], "LB04")
        self.assertFalse(payload["success"])
        self.assertEqual(payload["detail"], "one evaluator failed")
        self.assertEqual(payload["evaluators"][0]["name"], "AgentResponseEvaluator")
        self.assertEqual(payload["evaluators"][0]["error_msg"], "retrieved data did not match")
        self.assertEqual(
            payload["evaluators"][0]["assertions"][0],
            {"actual": ["wrong"], "expected": ["right"]},
        )
        self.assertEqual(
            payload["evaluators"][0]["expected"], {"retrieved_data": ["right"]}
        )
        self.assertFalse(payload["evaluators"][0]["should_not_exist"])

    def test_builds_evaluator_with_explicit_site_urls(self):
        bridge = self._load_bridge()
        captured = {}

        class FakeEnvironmentConfig:
            def __init__(self, *, urls):
                self.urls = urls

        class FakeConfig:
            def __init__(self, *, environments):
                self.environments = environments

        class FakeWebArenaVerified:
            def __init__(self, *, config):
                captured["config"] = config

        package = types.ModuleType("webarena_verified")
        api = types.ModuleType("webarena_verified.api")
        api.WebArenaVerified = FakeWebArenaVerified
        types_package = types.ModuleType("webarena_verified.types")
        config = types.ModuleType("webarena_verified.types.config")
        config.EnvironmentConfig = FakeEnvironmentConfig
        config.WebArenaVerifiedConfig = FakeConfig
        with patch.dict(
            sys.modules,
            {
                "webarena_verified": package,
                "webarena_verified.api": api,
                "webarena_verified.types": types_package,
                "webarena_verified.types.config": config,
            },
        ):
            bridge.build_evaluator({"shopping": "http://localhost:7770"})

        self.assertEqual(captured["config"].environments["shopping"].urls, ["http://localhost:7770"])


if __name__ == "__main__":
    unittest.main()
