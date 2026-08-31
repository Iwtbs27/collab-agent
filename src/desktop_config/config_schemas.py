"""Desktop Worker 使用的 UFO 配置模型。

UFO2 原配置支持大量可选模块。本项目只保留桌面 Agent 核心会读取的字段，
并给每个字段提供与原项目兼容的大小写访问方式。
"""

from dataclasses import dataclass, field
from typing import Any, Dict, List


class _ConfigAccess:
    def __getattr__(self, name: str) -> Any:
        if name.startswith("_"):
            raise AttributeError(name)
        lower_name = name.lower()
        if hasattr(type(self), lower_name):
            return object.__getattribute__(self, lower_name)
        extras = object.__getattribute__(self, "_extras")
        if name in extras:
            return extras[name]
        upper_name = name.upper()
        if upper_name in extras:
            return extras[upper_name]
        raise AttributeError(name)

    def __getitem__(self, key: str) -> Any:
        try:
            return getattr(self, key)
        except AttributeError as exc:
            raise KeyError(key) from exc

    def get(self, key: str, default: Any = None) -> Any:
        try:
            return self[key]
        except KeyError:
            return default

    def to_dict(self) -> Dict[str, Any]:
        data = {
            key: value
            for key, value in self.__dict__.items()
            if not key.startswith("_")
        }
        data.update(self._extras)
        # UFO2 still reads the legacy uppercase keys in several modules.
        data.update({key.upper(): value for key, value in data.items()})
        return data


@dataclass
class AgentConfig(_ConfigAccess):
    visual_mode: bool = True
    reasoning_model: bool = False
    api_type: str = "openai"
    api_base: str = ""
    api_key: str = ""
    api_version: str = ""
    api_model: str = ""
    prompt: str = ""
    example_prompt: str = ""
    _extras: Dict[str, Any] = field(default_factory=dict, repr=False)


@dataclass
class RAGConfig(_ConfigAccess):
    offline_docs: bool = False
    offline_docs_retrieved_topk: int = 1
    online_search: bool = False
    online_search_topk: int = 5
    online_retrieved_topk: int = 5
    experience: bool = False
    experience_retrieved_topk: int = 5
    demonstration: bool = False
    demonstration_retrieved_topk: int = 5
    _extras: Dict[str, Any] = field(default_factory=dict, repr=False)


@dataclass
class SystemConfig(_ConfigAccess):
    max_tokens: int = 2000
    max_retry: int = 20
    temperature: float = 0.0
    top_p: float = 0.0
    timeout: int = 120
    control_backend: List[str] = field(default_factory=lambda: ["uia"])
    iou_threshold_for_merge: float = 0.1
    max_step: int = 50
    max_round: int = 1
    sleep_time: float = 0.1
    rectangle_time: float = 0.1
    action_sequence: bool = False
    show_visual_outline_on_screen: bool = False
    maximize_window: bool = False
    json_parsing_retry: int = 3
    safe_guard: bool = True
    history_keys: List[str] = field(
        default_factory=lambda: ["step", "subtask", "action_representation", "user_confirm"]
    )
    click_api: str = "click_input"
    after_click_wait: float = 0.0
    input_text_api: str = "type_keys"
    input_text_enter: bool = False
    input_text_inter_key_pause: float = 0.05
    include_last_screenshot: bool = True
    concat_screenshot: bool = False
    log_xml: bool = False
    log_to_markdown: bool = False
    screenshot_to_memory: bool = True
    save_ui_tree: bool = True
    save_full_screen: bool = False
    ask_question: bool = False
    use_customization: bool = False
    qa_pair_file: str = ""
    qa_pair_num: int = 20
    omniparser: Dict[str, Any] = field(default_factory=dict)
    use_apis: bool = True
    use_mcp: bool = True
    mcp_servers_config: str = ""
    mcp_preferred_apps: List[str] = field(default_factory=list)
    mcp_fallback_to_ui: bool = True
    mcp_tool_timeout: int = 30
    hostagent_prompt: str = ""
    appagent_prompt: str = ""
    appagent_example_prompt: str = ""
    appagent_example_prompt_as: str = ""
    api_prompt: str = ""
    evaluation_prompt: str = ""
    third_party_agent_config: Dict[str, Any] = field(default_factory=dict)
    enabled_third_party_agents: List[str] = field(default_factory=list)
    output_presenter: str = "rich"
    default_png_compress_level: int = 6
    annotation_font_size: int = 14
    annotation_colors: Dict[str, Any] = field(default_factory=dict)
    eva_all_screenshots: bool = False
    control_list: List[str] = field(default_factory=list)
    _extras: Dict[str, Any] = field(default_factory=dict, repr=False)


@dataclass
class UFOConfig:
    system: SystemConfig
    host_agent: AgentConfig
    app_agent: AgentConfig
    evaluation_agent: AgentConfig
    rag: RAGConfig
    backup_agent: AgentConfig = field(default_factory=AgentConfig)
    operator: AgentConfig = field(default_factory=AgentConfig)
    _raw: Dict[str, Any] = field(default_factory=dict, repr=False)

    def to_dict(self) -> Dict[str, Any]:
        data = dict(self._raw)
        data.update(
            {
                "SYSTEM": self.system.to_dict(),
                "HOST_AGENT": self.host_agent.to_dict(),
                "APP_AGENT": self.app_agent.to_dict(),
                "EVALUATION_AGENT": self.evaluation_agent.to_dict(),
                "RAG": self.rag.to_dict(),
                "BACKUP_AGENT": self.backup_agent.to_dict(),
                "OPERATOR": self.operator.to_dict(),
            }
        )
        return data


@dataclass
class GalaxyConfig:
    _raw: Dict[str, Any] = field(default_factory=dict, repr=False)

    def to_dict(self) -> Dict[str, Any]:
        return dict(self._raw)
