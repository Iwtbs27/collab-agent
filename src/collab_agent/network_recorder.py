import asyncio
import inspect
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import parse_qsl, urlsplit


class NetworkRecorder:
    """Collect the CDP network events required by WebArena-Verified."""

    def __init__(self, allowed_hosts: set[str] | None = None) -> None:
        self._allowed_hosts = allowed_hosts
        self._client: Any = None
        self._requests: dict[tuple[str | None, str], dict[str, Any]] = {}
        self._request_extra_headers: dict[tuple[str | None, str], dict[str, Any]] = {}
        self._entries: list[dict[str, Any]] = []
        self._body_tasks: set[asyncio.Task] = set()

    async def start(self, browser_session: Any) -> None:
        self._client = browser_session.cdp_client
        self._subscribe("Network.requestWillBeSent", self._on_request)
        self._subscribe("Network.requestWillBeSentExtraInfo", self._on_request_extra_info)
        self._subscribe("Network.responseReceived", self._on_response)
        self._subscribe("Network.loadingFinished", self._on_finished)
        self._subscribe("Network.loadingFailed", self._on_failed)

    async def write(self, path: Path) -> Path:
        if self._body_tasks:
            await asyncio.gather(*self._body_tasks, return_exceptions=True)
        for key in list(self._requests):
            self._finish(key)
        payload = {
            "log": {
                "version": "1.2",
                "creator": {"name": "OmniAct CDP recorder", "version": "1"},
                "entries": self._entries,
            }
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        return path

    def _subscribe(self, method: str, callback: Callable) -> None:
        registry = self._client._event_registry
        previous = registry._handlers.get(method)

        async def combined(event: dict[str, Any], session_id: str | None) -> None:
            if previous is not None:
                result = previous(event, session_id)
                if inspect.isawaitable(result):
                    await result
            callback(event, session_id)

        registry.register(method, combined)

    @staticmethod
    def _key(event: dict[str, Any], session_id: str | None) -> tuple[str | None, str]:
        return session_id, str(event["requestId"])

    def _on_request(self, event: dict[str, Any], session_id: str | None) -> None:
        if self._allowed_hosts is not None:
            host = urlsplit(event["request"]["url"]).netloc
            if host not in self._allowed_hosts:
                return
        key = self._key(event, session_id)
        if key in self._requests:
            self._requests[key]["response"] = event.get("redirectResponse", {})
            self._finish(key)
        state = {
            "request": event["request"],
            "wall_time": event.get("wallTime"),
            "started": event.get("timestamp", 0),
            "session_id": session_id,
        }
        extra_headers = self._request_extra_headers.pop(key, None)
        if extra_headers is not None:
            state["extra_headers"] = extra_headers
        self._requests[key] = state

    def _on_request_extra_info(self, event: dict[str, Any], session_id: str | None) -> None:
        key = self._key(event, session_id)
        headers = event["headers"]
        state = self._requests.get(key)
        if state is None:
            self._request_extra_headers[key] = headers
        else:
            state["extra_headers"] = headers

    def _on_response(self, event: dict[str, Any], session_id: str | None) -> None:
        state = self._requests.get(self._key(event, session_id))
        if state is not None:
            state["response"] = event["response"]
            state["response_time"] = event.get("timestamp")

    def _on_finished(self, event: dict[str, Any], session_id: str | None) -> None:
        key = self._key(event, session_id)
        state = self._requests.get(key)
        if state is None:
            return
        state["finished"] = event.get("timestamp")
        state["encoded_length"] = event.get("encodedDataLength", -1)
        task = asyncio.create_task(self._load_body(key))
        self._body_tasks.add(task)
        task.add_done_callback(self._body_tasks.discard)

    def _on_failed(self, event: dict[str, Any], session_id: str | None) -> None:
        key = self._key(event, session_id)
        state = self._requests.get(key)
        if state is not None:
            state["failure"] = event.get("errorText", "request failed")
            state["finished"] = event.get("timestamp")

    async def _load_body(self, key: tuple[str | None, str]) -> None:
        state = self._requests.get(key)
        if state is None:
            return
        try:
            body = await self._client.send.Network.getResponseBody(
                params={"requestId": key[1]}, session_id=state["session_id"]
            )
            state["body"] = body
        except Exception as error:
            state["body_error"] = f"{type(error).__name__}: {error}"

    def _finish(self, key: tuple[str | None, str]) -> None:
        state = self._requests.pop(key, None)
        if state is not None:
            self._entries.append(self._to_har_entry(state))

    @staticmethod
    def _headers(values: dict[str, Any]) -> list[dict[str, str]]:
        return [{"name": str(name), "value": str(value)} for name, value in values.items()]

    @staticmethod
    def _merged_headers(provisional: dict[str, Any], extra: dict[str, Any]) -> dict[str, Any]:
        headers = dict(provisional)
        names = {str(name).casefold(): name for name in headers}
        for name, value in extra.items():
            previous = names.get(str(name).casefold())
            if previous is not None and previous != name:
                del headers[previous]
            headers[name] = value
            names[str(name).casefold()] = name
        return headers

    @classmethod
    def _to_har_entry(cls, state: dict[str, Any]) -> dict[str, Any]:
        request = state["request"]
        response = state.get("response", {})
        request_headers = cls._headers(
            cls._merged_headers(request.get("headers", {}), state.get("extra_headers", {}))
        )
        response_headers = cls._headers(response.get("headers", {}))
        started = state.get("wall_time")
        started_at = (
            datetime.fromtimestamp(started, timezone.utc).isoformat().replace("+00:00", "Z")
            if started is not None
            else datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        )
        body = state.get("body", {})
        content = {
            "size": state.get("encoded_length", -1),
            "mimeType": response.get("mimeType", ""),
        }
        if "body" in body:
            content["text"] = body["body"]
            if body.get("base64Encoded"):
                content["encoding"] = "base64"
        if "body_error" in state:
            content["_bodyError"] = state["body_error"]
        request_data = {
            "method": request["method"],
            "url": request["url"],
            "httpVersion": "HTTP/1.1",
            "cookies": [],
            "headers": request_headers,
            "queryString": [
                {"name": name, "value": value}
                for name, value in parse_qsl(urlsplit(request["url"]).query, keep_blank_values=True)
            ],
            "headersSize": -1,
            "bodySize": len(request.get("postData", "")),
        }
        if "postData" in request:
            content_type = next(
                (item["value"] for item in request_headers if item["name"].casefold() == "content-type"),
                "",
            )
            request_data["postData"] = {"mimeType": content_type, "text": request["postData"]}
        start = state.get("started", 0)
        end = state.get("finished", state.get("response_time", start))
        return {
            "startedDateTime": started_at,
            "time": max(0, (end - start) * 1000),
            "request": request_data,
            "response": {
                "status": response.get("status", -1),
                "statusText": response.get("statusText", ""),
                "httpVersion": response.get("protocol", "HTTP/1.1"),
                "cookies": [],
                "headers": response_headers,
                "content": content,
                "redirectURL": next(
                    (item["value"] for item in response_headers if item["name"].casefold() == "location"),
                    "",
                ),
                "headersSize": -1,
                "bodySize": state.get("encoded_length", -1),
                **({"_failureText": state["failure"]} if "failure" in state else {}),
            },
            "cache": {},
            "timings": {"send": -1, "wait": -1, "receive": -1},
        }
