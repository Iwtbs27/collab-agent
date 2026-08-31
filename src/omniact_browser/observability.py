from collections.abc import Callable
from typing import Any, TypeVar


F = TypeVar("F", bound=Callable[..., Any])


def _no_op_decorator(*_args: Any, **_kwargs: Any):
    def decorator(function: F) -> F:
        return function

    return decorator


observe = _no_op_decorator
observe_debug = _no_op_decorator
