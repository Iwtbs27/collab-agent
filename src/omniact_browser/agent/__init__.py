from typing import TYPE_CHECKING

if TYPE_CHECKING:
	from omniact_browser.agent.service import Agent


def __getattr__(name: str):
	if name != 'Agent':
		raise AttributeError(f"module 'omniact_browser.agent' has no attribute {name!r}")

	from omniact_browser.agent.service import Agent

	return Agent

__all__ = ['Agent']
