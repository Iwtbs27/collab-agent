# Copyright (c) Microsoft Corporation.
# Licensed under the MIT License.

"""UFO2 automation modules.

Submodules are imported explicitly so desktop UI startup does not pull in the
optional Office/COM providers.
"""

# Register the UIControl receiver used by AppAgent actions. Office receivers
# remain excluded from the Desktop Worker runtime.
from .ui_control import controller as controller

__all__ = ["controller"]
