import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

from pydantic import BaseModel, ConfigDict

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from omniact_browser.llm.messages import UserMessage
from omniact_browser.llm.exceptions import ModelProviderError
from omniact_browser.llm.openai.chat import ChatOpenAI
from omniact_browser.agent.service import Agent


class BrowserDecision(BaseModel):
    next_goal: str
    action: list[dict]


class NavigateParams(BaseModel):
    model_config = ConfigDict(extra="forbid")

    url: str


class NestedBrowserDecision(BaseModel):
    action: list[dict[str, NavigateParams]]


def model_response(*, tool_name="submit_browser_decision", tool_calls=1):
    calls = [
        SimpleNamespace(
            id=f"call-{index}",
            function=SimpleNamespace(
                name=tool_name,
                arguments=json.dumps(
                    {"next_goal": "open the result", "action": []}
                ),
            ),
        )
        for index in range(tool_calls)
    ]
    return SimpleNamespace(
        choices=[
            SimpleNamespace(
                finish_reason="tool_calls",
                message=SimpleNamespace(
                    content="I will inspect the current page.",
                    tool_calls=calls,
                ),
            )
        ],
        usage=None,
    )


class BrowserModelTests(unittest.IsolatedAsyncioTestCase):
    def make_llm(self, response):
        llm = ChatOpenAI(model="test-model", api_key="test-key")
        client = Mock()
        client.chat.completions.create = AsyncMock(return_value=response)
        llm.get_client = Mock(return_value=client)
        return llm, client

    async def test_browser_decision_uses_one_required_native_tool(self):
        llm, client = self.make_llm(model_response())

        result = await llm.ainvoke(
            [UserMessage(content="choose the next action")],
            output_format=BrowserDecision,
            browser_decision=True,
        )

        request = client.chat.completions.create.await_args.kwargs
        self.assertNotIn("response_format", request)
        self.assertFalse(request["parallel_tool_calls"])
        self.assertEqual(
            request["tool_choice"],
            {"type": "function", "function": {"name": "submit_browser_decision"}},
        )
        tool = request["tools"][0]["function"]
        self.assertEqual(tool["name"], "submit_browser_decision")
        self.assertIn("next_goal", tool["parameters"]["properties"])
        self.assertEqual(result.completion.next_goal, "open the result")
        self.assertEqual(result.response_content, "I will inspect the current page.")
        self.assertEqual(result.tool_call_id, "call-0")
        self.assertEqual(result.tool_name, "submit_browser_decision")

    async def test_browser_decision_rejects_wrong_tool_name(self):
        llm, _ = self.make_llm(model_response(tool_name="navigate"))

        with self.assertRaisesRegex(Exception, "submit_browser_decision"):
            await llm.ainvoke(
                [UserMessage(content="choose the next action")],
                output_format=BrowserDecision,
                browser_decision=True,
            )

    async def test_browser_decision_rejects_multiple_tool_calls(self):
        llm, _ = self.make_llm(model_response(tool_calls=2))

        with self.assertRaisesRegex(Exception, "exactly one"):
            await llm.ainvoke(
                [UserMessage(content="choose the next action")],
                output_format=BrowserDecision,
                browser_decision=True,
            )

    async def test_browser_decision_reports_validation_field_paths(self):
        response = model_response()
        response.choices[0].message.tool_calls[0].function.arguments = "{}"
        llm, _ = self.make_llm(response)

        with self.assertRaises(ModelProviderError) as context:
            await llm.ainvoke(
                [UserMessage(content="choose the next action")],
                output_format=BrowserDecision,
                browser_decision=True,
            )

        self.assertIn("Browser decision arguments are invalid", context.exception.message)
        self.assertIn("action: Field required", context.exception.message)

    async def test_browser_decision_reports_nested_validation_paths(self):
        response = model_response()
        response.choices[0].message.tool_calls[0].function.arguments = json.dumps(
            {"action": [{"go_to_url": {"params": {"url": "https://example.com"}}}]}
        )
        llm, _ = self.make_llm(response)

        with self.assertRaises(ModelProviderError) as context:
            await llm.ainvoke(
                [UserMessage(content="choose the next action")],
                output_format=NestedBrowserDecision,
                browser_decision=True,
            )

        self.assertIn("action[0].go_to_url.url", context.exception.message)

    async def test_agent_constructs_with_only_openai_compatible_model_modules(self):
        llm = ChatOpenAI(
            model="vision-model",
            api_key="test-key",
            base_url="https://proxy.example/v1",
        )

        agent = Agent(task="Open example.com", llm=llm, enable_signal_handler=False)

        self.assertEqual(agent.llm, llm)
        await agent.close()


if __name__ == "__main__":
    unittest.main()
