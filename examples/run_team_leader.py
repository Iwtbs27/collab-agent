import asyncio
import os
import sys
from datetime import datetime
from pathlib import Path

from dotenv import load_dotenv


PROJECT_ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))


async def main() -> None:
    from collab_agent import (
        BrowserWorker,
        CLIWorker,
        CodexRunner,
        DesktopWorker,
        OpenAIModelClient,
        TaskWorkspace,
        TeamLeader,
    )

    load_dotenv(PROJECT_ROOT / ".env", override=False)
    client = OpenAIModelClient(
        api_key=os.environ["TEAM_LEADER_API_KEY"],
        base_url=os.environ["TEAM_LEADER_BASE_URL"],
        model=os.environ["TEAM_LEADER_MODEL"],
    )
    browser_worker = BrowserWorker(
        api_key=os.environ["BROWSER_WORKER_API_KEY"],
        base_url=os.environ["BROWSER_WORKER_BASE_URL"],
        model=os.environ["BROWSER_WORKER_MODEL"],
        session_mode=os.environ["BROWSER_WORKER_SESSION_MODE"],
    )
    desktop_worker = DesktopWorker(
        max_steps=int(os.environ.get("DESKTOP_WORKER_MAX_STEPS", "50"))
    )
    try:
        task_id = datetime.now().strftime("task-%Y%m%d-%H%M%S-%f")
        workspace = TaskWorkspace.create(PROJECT_ROOT / "tasks", task_id)
        user_request = input("请输入任务：\n> ").strip()

        worker_runners = {"browser": browser_worker}
        if os.environ.get("DESKTOP_WORKER_ENABLED", "false").lower() == "true":
            worker_runners["desktop"] = desktop_worker
        cli_worker = None
        if os.environ.get("CLI_WORKER_ENABLED", "false").lower() == "true":
            configured_directory = os.environ.get("CLI_WORKER_WORKING_DIRECTORY", "")
            working_directory = Path(configured_directory) if configured_directory else None
            cli_worker = CLIWorker(
                CodexRunner(
                    executable=os.environ.get("CODEX_EXECUTABLE", "codex.cmd"),
                    sandbox=os.environ.get("CODEX_SANDBOX", "workspace-write"),
                ),
                working_directory=working_directory,
                max_turns=int(os.environ.get("CLI_WORKER_MAX_TURNS", "3")),
            )
            worker_runners["cli"] = cli_worker

        leader = TeamLeader(client, worker_runners=worker_runners)
        leader_task = asyncio.create_task(leader.run(workspace, user_request))

        while not leader_task.done():
            waiting = {
                **{
                    f"browser:{worker_id}": request
                    for worker_id, request in browser_worker.waiting_requests.items()
                },
                **{
                    f"desktop:{worker_id}": request
                    for worker_id, request in desktop_worker.waiting_requests.items()
                },
            }
            if not waiting:
                await asyncio.sleep(0.25)
                continue

            for worker_id, request in waiting.items():
                worker_type, _, actual_worker_id = worker_id.partition(":")
                print(f"\n{worker_type.title()} Worker {actual_worker_id} 等待人工接管")
                print(f"原因：{request['reason']}")
                print(f"操作：{request['instruction']}")
            command = (
                await asyncio.to_thread(
                    input,
                    "命令 takeover/resume/cancel <worker-id>：\n> ",
                )
            ).strip().split()
            if len(command) != 2:
                print("命令格式错误")
                continue
            action, worker_key = command
            worker_type, separator, worker_id = worker_key.partition(":")
            if not separator:
                worker_type, worker_id = "browser", worker_key
            worker = browser_worker if worker_type == "browser" else desktop_worker
            if action == "takeover":
                await worker.takeover(worker_id)
            elif action == "resume":
                await worker.resume(worker_id)
            elif action == "cancel":
                await worker.cancel(worker_id)
            else:
                print(f"未知命令：{action}")

        result = await leader_task
        print(f"\nTask 目录：{workspace.task_dir}")
        print(f"\n最终结果：\n{result}")
    finally:
        await browser_worker.close()
        await desktop_worker.close()
        await client.close()


if __name__ == "__main__":
    asyncio.run(main())
