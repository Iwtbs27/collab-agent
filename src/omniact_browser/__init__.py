from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from omniact_browser.agent.service import Agent
    from omniact_browser.browser.profile import BrowserProfile
    from omniact_browser.browser.session import BrowserSession
    from omniact_browser.tools.service import Tools


_LAZY_IMPORTS = {
    "Agent": ("omniact_browser.agent.service", "Agent"),
    "BrowserProfile": ("omniact_browser.browser.profile", "BrowserProfile"),
    "BrowserSession": ("omniact_browser.browser.session", "BrowserSession"),
    "Browser": ("omniact_browser.browser.session", "BrowserSession"),
    "Tools": ("omniact_browser.tools.service", "Tools"),
}


def __getattr__(name: str):
    if name not in _LAZY_IMPORTS:
        raise AttributeError(f"module 'omniact_browser' has no attribute {name!r}")

    from importlib import import_module

    module_name, attribute_name = _LAZY_IMPORTS[name]
    attribute = getattr(import_module(module_name), attribute_name)
    globals()[name] = attribute
    return attribute


__all__ = ["Agent", "Browser", "BrowserProfile", "BrowserSession", "Tools"]
