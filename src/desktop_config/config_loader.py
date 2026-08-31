"""轻量 UFO2 配置加载器，支持 Desktop Worker 的独立模型配置。"""

import os
from pathlib import Path

from .config_schemas import AgentConfig, GalaxyConfig, RAGConfig, SystemConfig, UFOConfig


_CONFIG: UFOConfig | None = None


def _prompt_path(relative: str) -> str:
    return str(Path(__file__).resolve().parents[1] / "omniact_desktop" / "prompts" / relative)


def _make_config() -> UFOConfig:
    api_key = os.getenv("DESKTOP_WORKER_API_KEY", "")
    base_url = os.getenv("DESKTOP_WORKER_BASE_URL", "")
    model = os.getenv("DESKTOP_WORKER_MODEL", "")
    host = AgentConfig(api_type="openai", api_key=api_key, api_base=base_url, api_model=model)
    app = AgentConfig(api_type="openai", api_key=api_key, api_base=base_url, api_model=model)
    backup = AgentConfig(api_type="openai", api_key=api_key, api_base=base_url, api_model=model)
    operator = AgentConfig(api_type="openai", api_key=api_key, api_base=base_url, api_model=model)
    system = SystemConfig(
        hostagent_prompt=_prompt_path("share/base/host_agent.yaml"),
        appagent_prompt=_prompt_path("share/base/app_agent.yaml"),
        appagent_example_prompt=_prompt_path("examples/visual/app_agent_example.yaml"),
        appagent_example_prompt_as=_prompt_path("examples/visual/app_agent_example_as.yaml"),
        api_prompt=_prompt_path("share/base/api.yaml"),
    )
    evaluation = AgentConfig(api_type="openai", api_key=api_key, api_base=base_url, api_model=model)
    ui_mcp = {
        "HostAgent": {
            "default": {
                "data_collection": [
                    {"type": "local", "namespace": "UICollector"}
                ],
                "action": [
                    {"type": "local", "namespace": "HostUIExecutor"}
                ],
            }
        },
        "AppAgent": {
            "default": {
                "data_collection": [
                    {"type": "local", "namespace": "UICollector"}
                ],
                "action": [
                    {"type": "local", "namespace": "AppUIExecutor"}
                ],
            }
        },
    }
    return UFOConfig(
        system=system,
        host_agent=host,
        app_agent=app,
        evaluation_agent=evaluation,
        backup_agent=backup,
        operator=operator,
        rag=RAGConfig(),
        _raw={"mcp": ui_mcp},
    )


def get_ufo_config(reload: bool = False) -> UFOConfig:
    global _CONFIG
    if _CONFIG is None or reload:
        _CONFIG = _make_config()
    return _CONFIG


def configure_desktop_worker(api_key: str, base_url: str, model: str) -> UFOConfig:
    """更新当前进程中的 UFO 配置，供一个 Desktop Worker 会话使用。"""
    os.environ["DESKTOP_WORKER_API_KEY"] = api_key
    os.environ["DESKTOP_WORKER_BASE_URL"] = base_url
    os.environ["DESKTOP_WORKER_MODEL"] = model
    return get_ufo_config(reload=True)


def get_galaxy_config(reload: bool = False) -> GalaxyConfig:
    return GalaxyConfig()


def clear_config_cache() -> None:
    global _CONFIG
    _CONFIG = None


class ConfigLoader:
    """兼容 UFO2 调用点的最小配置加载入口。"""

    def load_ufo_config(self) -> UFOConfig:
        return get_ufo_config()


__all__ = [
    "ConfigLoader",
    "configure_desktop_worker",
    "get_galaxy_config",
    "get_ufo_config",
    "clear_config_cache",
]
