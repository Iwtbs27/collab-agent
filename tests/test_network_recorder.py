import json
import sys
import unittest
import asyncio
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from collab_agent.network_recorder import NetworkRecorder


class FakeRegistry:
    def __init__(self):
        self._handlers = {}

    def register(self, method, callback):
        self._handlers[method] = callback


class FakeNetworkCommands:
    async def getResponseBody(self, params, session_id):
        return {"body": '{"saved": true}', "base64Encoded": False}


class FailingNetworkCommands:
    async def getResponseBody(self, params, session_id):
        raise RuntimeError("body is unavailable")


class NetworkRecorderTests(unittest.IsolatedAsyncioTestCase):
    async def test_records_har_without_replacing_existing_listener(self):
        previous_events = []
        registry = FakeRegistry()
        registry.register(
            "Network.responseReceived",
            lambda event, session_id: previous_events.append((event, session_id)),
        )
        client = SimpleNamespace(
            _event_registry=registry,
            send=SimpleNamespace(Network=FakeNetworkCommands()),
        )
        session = SimpleNamespace(cdp_client=client)
        recorder = NetworkRecorder(allowed_hosts={"localhost:7770"})
        await recorder.start(session)

        await registry._handlers["Network.requestWillBeSent"](
            {
                "requestId": "noise",
                "wallTime": 1_700_000_000,
                "timestamp": 9.0,
                "request": {
                    "method": "GET",
                    "url": "https://example.com/background",
                    "headers": {},
                },
            },
            "session-1",
        )

        await registry._handlers["Network.requestWillBeSent"](
            {
                "requestId": "1",
                "wallTime": 1_700_000_000,
                "timestamp": 10.0,
                "request": {
                    "method": "POST",
                    "url": "http://localhost:7770/cart",
                    "headers": {"Content-Type": "application/json"},
                    "postData": '{"sku": "A1"}',
                },
            },
            "session-1",
        )
        response_event = {
            "requestId": "1",
            "timestamp": 10.2,
            "response": {
                "status": 200,
                "statusText": "OK",
                "protocol": "h2",
                "headers": {"Content-Type": "application/json"},
                "mimeType": "application/json",
                "url": "http://localhost:7770/cart",
            },
        }
        await registry._handlers["Network.responseReceived"](
            response_event, "session-1"
        )
        await registry._handlers["Network.loadingFinished"](
            {"requestId": "1", "timestamp": 10.3, "encodedDataLength": 15},
            "session-1",
        )
        await asyncio.sleep(0)
        await registry._handlers["Network.requestWillBeSentExtraInfo"](
            {
                "requestId": "1",
                "headers": {
                    "content-type": "application/vnd.api+json",
                    "X-Verified-Request": "true",
                },
            },
            "session-1",
        )

        with TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "network.har"
            await recorder.write(output)
            entries = json.loads(output.read_text(encoding="utf-8"))["log"]["entries"]

        self.assertEqual(previous_events, [(response_event, "session-1")])
        self.assertEqual(entries[0]["request"]["method"], "POST")
        self.assertEqual(entries[0]["request"]["postData"]["text"], '{"sku": "A1"}')
        request_headers = {
            item["name"].casefold(): item["value"]
            for item in entries[0]["request"]["headers"]
        }
        self.assertEqual(request_headers["content-type"], "application/vnd.api+json")
        self.assertEqual(request_headers["x-verified-request"], "true")
        self.assertEqual(entries[0]["response"]["status"], 200)
        self.assertEqual(entries[0]["response"]["content"]["text"], '{"saved": true}')

    async def test_response_body_failure_is_visible_in_har(self):
        registry = FakeRegistry()
        client = SimpleNamespace(
            _event_registry=registry,
            send=SimpleNamespace(Network=FailingNetworkCommands()),
        )
        recorder = NetworkRecorder(allowed_hosts={"localhost:7770"})
        await recorder.start(SimpleNamespace(cdp_client=client))
        await registry._handlers["Network.requestWillBeSent"](
            {
                "requestId": "1",
                "request": {
                    "method": "GET",
                    "url": "http://localhost:7770/api/result",
                    "headers": {},
                },
            },
            "session-1",
        )
        await registry._handlers["Network.loadingFinished"](
            {"requestId": "1", "timestamp": 1.0, "encodedDataLength": 0},
            "session-1",
        )

        with TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "network.har"
            await recorder.write(output)
            entry = json.loads(output.read_text(encoding="utf-8"))["log"]["entries"][0]

        self.assertIn(
            "body is unavailable", entry["response"]["content"]["_bodyError"]
        )


if __name__ == "__main__":
    unittest.main()
