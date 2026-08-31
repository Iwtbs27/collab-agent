import json
from dataclasses import dataclass
from typing import Any, Dict, List, Optional


def _value(value: Any, key: str, default: Any = None) -> Any:
    if isinstance(value, dict):
        return value.get(key, default)
    return getattr(value, key, default)


@dataclass
class ToolCall:
    call_id: str
    name: str
    arguments: Dict[str, Any]


@dataclass
class ModelTurn:
    kind: str
    visible_text: Optional[str] = None
    tool_call: Optional[ToolCall] = None
    error: Optional[str] = None

    @property
    def tool_name(self) -> Optional[str]:
        return self.tool_call.name if self.tool_call else None

    @property
    def arguments(self) -> Optional[Dict[str, Any]]:
        return self.tool_call.arguments if self.tool_call else None


def parse_native_response(response: Any) -> ModelTurn:
    """Convert an OpenAI-compatible response into one TeamLeader turn."""
    choices = _value(response, "choices") or []
    if not choices:
        return ModelTurn("invalid", error="model response has no choices")

    message = _value(choices[0], "message")
    if message is None:
        return ModelTurn("invalid", error="model response has no message")

    content = _value(message, "content")
    if content is not None and not isinstance(content, str):
        content = str(content)

    tool_calls = _value(message, "tool_calls") or []
    if len(tool_calls) > 1:
        return ModelTurn(
            "invalid",
            visible_text=content,
            error="TeamLeader accepts only one tool call per turn",
        )
    if not tool_calls:
        return ModelTurn("text", visible_text=content or "")

    raw_call = tool_calls[0]
    function = _value(raw_call, "function")
    name = _value(function, "name") if function is not None else None
    arguments = _value(function, "arguments") if function is not None else None
    if not isinstance(name, str) or not name:
        return ModelTurn("invalid", error="tool call has no function name")

    if isinstance(arguments, str):
        try:
            arguments = json.loads(arguments)
        except json.JSONDecodeError as exc:
            return ModelTurn(
                "invalid",
                visible_text=content,
                error=f"tool arguments are not valid JSON: {exc}",
            )
    if not isinstance(arguments, dict):
        return ModelTurn(
            "invalid",
            visible_text=content,
            error="tool arguments must be a JSON object",
        )

    call_id = _value(raw_call, "id") or "tool-call"
    return ModelTurn(
        "tool_call",
        visible_text=content,
        tool_call=ToolCall(call_id, name, arguments),
    )


class OpenAIModelClient:
    """Small OpenAI-compatible client used by TeamLeader."""

    def __init__(self, api_key: str, base_url: str, model: str):
        if not isinstance(api_key, str) or not api_key:
            raise ValueError("api_key must be provided")
        if not isinstance(base_url, str) or not base_url:
            raise ValueError("base_url must be provided")
        if not isinstance(model, str) or not model:
            raise ValueError("model must be provided")

        from openai import AsyncOpenAI

        self.model = model
        self.client = AsyncOpenAI(
            api_key=api_key,
            base_url=base_url,
            default_headers={"User-Agent": "collab-agent"},
        )

    async def complete(
        self, messages: List[Dict[str, Any]], tools: List[Dict[str, Any]]
    ) -> Any:
        return await self.client.chat.completions.create(
            model=self.model,
            messages=messages,
            tools=tools,
        )

    async def close(self) -> None:
        await self.client.close()
