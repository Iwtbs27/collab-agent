"""本地桌面执行所需的 AIP 消息模型。

远程 transport、endpoint 和 heartbeat 不属于 Desktop Worker 的运行边界，
因此不在包导入时加载。需要远程 AIP 时仍可直接从其独立模块导入。
"""

from .messages import (
    AppWindowControlInfo,
    ClientMessage,
    ClientMessageType,
    ClientType,
    Command,
    ControlInfo,
    MCPToolCall,
    MCPToolInfo,
    Rect,
    Result,
    ResultStatus,
    ServerMessage,
    ServerMessageType,
    TaskStatus,
    WindowInfo,
)

__version__ = "1.0.0"

__all__ = [
    "AppWindowControlInfo",
    "ClientMessage",
    "ClientMessageType",
    "ClientType",
    "Command",
    "ControlInfo",
    "MCPToolCall",
    "MCPToolInfo",
    "Rect",
    "Result",
    "ResultStatus",
    "ServerMessage",
    "ServerMessageType",
    "TaskStatus",
    "WindowInfo",
]
