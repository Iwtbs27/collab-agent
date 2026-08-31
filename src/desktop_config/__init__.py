# Copyright (c) Microsoft Corporation.
# Licensed under the MIT License.

"""
UFO² Configuration System

Modern, modular configuration system with type safety and backward compatibility.
"""

from desktop_config.config_loader import (
    ConfigLoader,
    get_ufo_config,
    get_galaxy_config,
    clear_config_cache,
)

from desktop_config.config_schemas import (
    UFOConfig,
    GalaxyConfig,
    AgentConfig,
    SystemConfig,
    RAGConfig,
)

__all__ = [
    "ConfigLoader",
    "get_ufo_config",
    "get_galaxy_config",
    "clear_config_cache",
    "UFOConfig",
    "GalaxyConfig",
    "AgentConfig",
    "SystemConfig",
    "RAGConfig",
]
