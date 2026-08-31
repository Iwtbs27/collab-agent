import argparse
import json
from pathlib import Path
from typing import Any


def parse_site_urls(values: list[str]) -> dict[str, str]:
    site_urls: dict[str, str] = {}
    for value in values:
        site, separator, url = value.partition("=")
        if not separator or not site or not url:
            raise ValueError(f"Invalid --site-url value: {value!r}; expected SITE=URL")
        site_urls[site] = url
    return site_urls


def build_evaluator(site_urls: dict[str, str]) -> Any:
    from webarena_verified.api import WebArenaVerified

    if not site_urls:
        return WebArenaVerified()

    from webarena_verified.types.config import EnvironmentConfig, WebArenaVerifiedConfig

    config = WebArenaVerifiedConfig(
        environments={site: EnvironmentConfig(urls=[url]) for site, url in site_urls.items()}
    )
    return WebArenaVerified(config=config)


def build_result_payload(case_id: str, result: Any) -> dict[str, Any]:
    evaluators = []
    for item in result.evaluators_results:
        dumped = item.model_dump(mode="json")
        payload = {
            key: dumped.get(key)
            for key in (
                "evaluator_name",
                "status",
                "score",
                "actual",
                "actual_normalized",
                "expected",
                "should_not_exist",
                "assertions",
                "error_msg",
            )
        }
        payload["name"] = item.evaluator_name
        evaluators.append(payload)
    return {
        "case_id": case_id,
        "success": result.status == "success",
        "score": result.score,
        "status": result.status,
        "detail": result.error_msg,
        "error_msg": result.error_msg,
        "evaluators": evaluators,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("case_id")
    parser.add_argument("task_id", type=int)
    parser.add_argument("response", type=Path)
    parser.add_argument("network_trace", type=Path)
    parser.add_argument("--site-url", action="append", default=[])
    args = parser.parse_args()

    evaluator = build_evaluator(parse_site_urls(args.site_url))
    result = evaluator.evaluate_task(
        task_id=args.task_id,
        agent_response=args.response,
        network_trace=args.network_trace,
    )
    payload = build_result_payload(args.case_id, result)
    print("OMNIACT_RESULT=" + json.dumps(payload, ensure_ascii=False))


if __name__ == "__main__":
    main()
