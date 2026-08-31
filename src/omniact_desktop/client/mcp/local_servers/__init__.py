"""按需注册 UFO2 本地 UI MCP 工具。

Desktop Worker 只需要 UI 感知与动作服务器；Office 专用和远程服务器不会
在导入时自动加载，避免无关依赖改变核心执行链。
"""

import importlib
import platform


def load_all_servers() -> None:
    if platform.system() != "Windows":
        return
    importlib.import_module("omniact_desktop.client.mcp.local_servers.ui_mcp_server")


__all__ = ["load_all_servers"]
