import asyncio
from dataclasses import dataclass
from pathlib import Path
from typing import Awaitable, Callable, List, Optional, Sequence

from .workspace import TaskWorkspace


WorkerRunner = Callable[[Path], Awaitable[None]]


@dataclass
class WorkerTask:
    """One worker request prepared by the caller."""

    worker_name: str
    request: str
    runner: WorkerRunner


@dataclass
class DispatchResult:
    """The result collected from one worker execution."""

    worker_name: str
    result_path: Path
    result_content: Optional[str] = None
    task_status: Optional[str] = None
    error_kind: Optional[str] = None
    error: Optional[str] = None

    @property
    def success(self) -> bool:
        return self.error_kind is None and self.task_status == "completed"


def _result_status(content: str) -> str:
    for line in content.splitlines():
        if line.startswith("Status:"):
            return line.partition(":")[2].strip().lower()
    return "completed"


async def dispatch_worker(
    workspace: TaskWorkspace,
    worker_name: str,
    request: str,
    runner: WorkerRunner,
) -> DispatchResult:
    """Run one worker and collect the result it writes to its directory."""
    worker_dir = workspace.worker_dir(worker_name)
    workspace.write_request(worker_name, request)
    result_path = worker_dir / "result.md"

    try:
        await runner(worker_dir)
    except Exception as exc:  # A worker failure must not cancel its siblings.
        return DispatchResult(
            worker_name=worker_name,
            result_path=result_path,
            error_kind="worker_error",
            error=str(exc),
        )

    if not result_path.is_file():
        return DispatchResult(
            worker_name=worker_name,
            result_path=result_path,
            error_kind="result_missing",
            error="Worker finished without creating result.md",
        )

    try:
        result_content = result_path.read_text(encoding="utf-8")
    except OSError as exc:
        return DispatchResult(
            worker_name=worker_name,
            result_path=result_path,
            error_kind="result_read_error",
            error=str(exc),
        )

    return DispatchResult(
        worker_name=worker_name,
        result_path=result_path,
        result_content=result_content,
        task_status=_result_status(result_content),
    )


async def dispatch_parallel_worker(
    workspace: TaskWorkspace,
    tasks: Sequence[WorkerTask],
) -> List[DispatchResult]:
    """Run at least two independent workers concurrently."""
    if len(tasks) < 2:
        raise ValueError("dispatch_parallel_worker requires at least two tasks")

    return list(
        await asyncio.gather(
            *(
                dispatch_worker(
                    workspace,
                    task.worker_name,
                    task.request,
                    task.runner,
                )
                for task in tasks
            )
        )
    )
