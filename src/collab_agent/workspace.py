import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Union


PathInput = Union[str, Path]


def _validate_name(name: str, label: str) -> None:
    if not isinstance(name, str) or not name or name in {".", ".."}:
        raise ValueError(f"{label} must be a non-empty name")
    if "/" in name or "\\" in name:
        raise ValueError(f"{label} cannot contain a path separator")


class TaskWorkspace:
    """Manage the files and directories belonging to one task."""

    def __init__(self, task_dir: PathInput):
        self.task_dir = Path(task_dir)

    @classmethod
    def create(cls, root_dir: PathInput, task_id: str) -> "TaskWorkspace":
        """Create a new task directory and its standard subdirectories."""
        _validate_name(task_id, "task id")
        task_dir = Path(root_dir) / task_id
        task_dir.mkdir(parents=True, exist_ok=False)

        workspace = cls(task_dir)
        workspace.inputs_dir.mkdir()
        workspace.workers_dir.mkdir()
        workspace.final_dir.mkdir()
        workspace.task_path.touch()
        workspace.trace_path.touch()
        return workspace

    @property
    def task_path(self) -> Path:
        return self.task_dir / "task.md"

    @property
    def trace_path(self) -> Path:
        return self.task_dir / "trace.jsonl"

    @property
    def trajectory_path(self) -> Path:
        return self.task_dir / "trajectory.md"

    @property
    def inputs_dir(self) -> Path:
        return self.task_dir / "inputs"

    @property
    def workers_dir(self) -> Path:
        return self.task_dir / "workers"

    @property
    def final_dir(self) -> Path:
        return self.task_dir / "final"

    @property
    def final_result_path(self) -> Path:
        return self.final_dir / "result.md"

    def worker_dir(self, worker_name: str) -> Path:
        """Create and return a worker directory with its artifacts directory."""
        _validate_name(worker_name, "worker name")
        path = self.workers_dir / worker_name
        path.mkdir(exist_ok=True)
        (path / "artifacts").mkdir(exist_ok=True)
        return path

    def request_path(self, worker_name: str) -> Path:
        _validate_name(worker_name, "worker name")
        return self.workers_dir / worker_name / "request.md"

    def result_path(self, worker_name: str) -> Path:
        _validate_name(worker_name, "worker name")
        return self.workers_dir / worker_name / "result.md"

    def write_task(self, content: str) -> None:
        self.task_path.write_text(content, encoding="utf-8")

    def append_trace(self, event: str, **details: Any) -> None:
        record: Dict[str, Any] = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": event,
            **details,
        }
        with self.trace_path.open("a", encoding="utf-8") as file:
            file.write(json.dumps(record, ensure_ascii=False))
            file.write("\n")

    def read_trace(self) -> list[Dict[str, Any]]:
        return [
            json.loads(line)
            for line in self.trace_path.read_text(encoding="utf-8").splitlines()
            if line
        ]

    def write_trajectory(self, content: str) -> None:
        self.trajectory_path.write_text(content, encoding="utf-8")

    def copy_input(self, source: PathInput) -> Path:
        """Copy one user-provided file into the task's inputs directory."""
        source_path = Path(source)
        if not source_path.is_file():
            raise FileNotFoundError(source_path)

        destination = self.inputs_dir / source_path.name
        if destination.exists():
            raise FileExistsError(destination)
        shutil.copy2(source_path, destination)
        return destination

    def write_request(self, worker_name: str, content: str) -> None:
        self.worker_dir(worker_name)
        self.request_path(worker_name).write_text(content, encoding="utf-8")

    def read_result(self, worker_name: str) -> str:
        return self.result_path(worker_name).read_text(encoding="utf-8")

    def write_final_result(self, content: str) -> None:
        self.final_result_path.write_text(content, encoding="utf-8")
