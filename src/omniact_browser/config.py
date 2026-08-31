import os
from pathlib import Path


class Config:
    @property
    def XDG_CACHE_HOME(self) -> str:
        return os.getenv("XDG_CACHE_HOME", str(Path.home() / ".cache"))

    @property
    def BROWSER_USE_CONFIG_DIR(self) -> Path:
        return Path(os.getenv("BROWSER_USE_CONFIG_DIR", str(Path.home() / ".config" / "browseruse"))).expanduser()

    @property
    def BROWSER_USE_DEFAULT_USER_DATA_DIR(self) -> Path:
        return self.BROWSER_USE_CONFIG_DIR / "profiles" / "default"

    @property
    def BROWSER_USE_EXTENSIONS_DIR(self) -> Path:
        path = self.BROWSER_USE_CONFIG_DIR / "extensions"
        path.mkdir(parents=True, exist_ok=True)
        return path

    @property
    def BROWSER_USE_MODEL_PRICING_URL(self) -> str:
        return os.getenv("BROWSER_USE_MODEL_PRICING_URL", "")

    @property
    def BROWSER_USE_VERSION_CHECK(self) -> bool:
        return False

    @property
    def DEFAULT_LLM(self) -> str:
        return ""

    @property
    def SKIP_LLM_API_KEY_VERIFICATION(self) -> bool:
        return False

    @property
    def IN_DOCKER(self) -> bool:
        return os.getenv("IN_DOCKER", "false").lower() in {"1", "true", "yes", "on"}


CONFIG = Config()
