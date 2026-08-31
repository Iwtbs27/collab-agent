import asyncio
import json
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal
from urllib.parse import urlsplit

from omniact_browser import Agent, BrowserProfile, BrowserSession
from omniact_browser.browser.chrome import find_edge_executable, get_edge_user_data_dir, get_last_used_profile
from omniact_browser.browser.profile import BrowserChannel
from omniact_browser.llm.openai.chat import ChatOpenAI
from pydantic import BaseModel, Field

from .network_recorder import NetworkRecorder


SessionMode = Literal['inherited', 'clean']


class BenchmarkAgentResponse(BaseModel):
	"""Final response schema accepted by WebArena-Verified."""

	task_type: Literal['RETRIEVE', 'MUTATE', 'NAVIGATE'] = Field(
		description='RETRIEVE returns information, MUTATE changes state, and NAVIGATE opens a requested page.'
	)
	status: Literal[
		'SUCCESS',
		'ACTION_NOT_ALLOWED_ERROR',
		'PERMISSION_DENIED_ERROR',
		'NOT_FOUND_ERROR',
		'DATA_VALIDATION_ERROR',
		'UNKNOWN_ERROR',
	] = Field(description='SUCCESS only when the requested objective was fully achieved; otherwise use the matching error.')
	retrieved_data: list[str | int | float | bool | dict[str, Any] | None] | None = Field(
		default=None,
		description='Observed values for RETRIEVE tasks; use null for MUTATE, NAVIGATE, or an error without results.',
	)
	error_details: str | None = Field(default=None, description='Null on success; otherwise a concise failure reason.')


def _benchmark_handoff(response: BenchmarkAgentResponse) -> str:
	lines = [f'Browser Agent reported `{response.status}` for the `{response.task_type}` task.']
	if response.retrieved_data is not None:
		lines.extend(['', 'Retrieved data:', '', '```json', json.dumps(response.retrieved_data, ensure_ascii=False, indent=2), '```'])
	if response.error_details:
		lines.extend(['', f'Error details: {response.error_details}'])
	return '\n'.join(lines)


def _append_trace(path: Path, event: str, **details: Any) -> None:
	record = {
		'timestamp': datetime.now(timezone.utc).isoformat(),
		'event': event,
		**details,
	}
	with path.open('a', encoding='utf-8') as file:
		file.write(json.dumps(record, ensure_ascii=False, default=str))
		file.write('\n')


def _replace_images(value: Any, screenshot_path: str | None) -> Any:
	if isinstance(value, dict):
		if value.get('type') == 'image_url' and screenshot_path:
			return {'type': 'image_url', 'image_url': {'url': screenshot_path}}
		return {key: _replace_images(item, screenshot_path) for key, item in value.items()}
	if isinstance(value, list):
		return [_replace_images(item, screenshot_path) for item in value]
	return value


def _serialize_messages(messages: list[Any], screenshot_path: str | None) -> list[dict[str, Any]]:
	serialized = []
	for message in messages:
		if hasattr(message, 'model_dump'):
			value = message.model_dump(mode='json')
		else:
			value = {'role': 'unknown', 'content': str(message)}
		serialized.append(_replace_images(value, screenshot_path))
	return serialized


def _format_message_content(content: Any) -> str:
	if isinstance(content, str):
		return content
	parts = []
	for part in content or []:
		if isinstance(part, dict) and part.get('type') == 'text':
			parts.append(str(part.get('text', '')))
		elif isinstance(part, dict) and part.get('type') == 'image_url':
			image_url = part.get('image_url', {})
			parts.append(f"[Screenshot: {image_url.get('url', '')}]")
		else:
			parts.append(json.dumps(part, ensure_ascii=False, default=str))
	return '\n'.join(parts)


def _render_trajectory(trace_path: Path, trajectory_path: Path) -> None:
	events = [json.loads(line) for line in trace_path.read_text(encoding='utf-8').splitlines() if line]
	lines = ['# Browser Worker Trajectory', '']
	for event in events:
		kind = event['event']
		if kind == 'worker_started':
			lines.extend(['## Task', '', event['request'], ''])
		elif kind == 'turn':
			lines.extend([f"## Turn {event['turn']}", '', '### Model Input', ''])
			for message in event['messages']:
				lines.extend([f"**{message.get('role', 'unknown')}**", '', _format_message_content(message.get('content')), ''])
			lines.extend(['### Assistant Content', '', event.get('response_content') or '(empty)', ''])
			lines.extend(['### submit_browser_decision', '', '```json'])
			lines.append(json.dumps(event['tool_call']['arguments'], ensure_ascii=False, indent=2))
			lines.extend(['```', '', '### Browser Action Result', ''])
			for result in event['results']:
				text = result.get('extracted_content') or result.get('error') or result.get('long_term_memory') or '(empty)'
				lines.extend([str(text), ''])
		elif kind == 'waiting_for_user':
			lines.extend(
				[
					'## Waiting For User',
					'',
					f"Reason: {event['reason']}",
					'',
					f"Instruction: {event['instruction']}",
					'',
				]
			)
		elif kind == 'worker_resumed':
			lines.extend(['## User Resumed Worker', ''])
		elif kind == 'worker_cancelled':
			lines.extend(['## User Cancelled Worker', ''])
		elif kind == 'worker_finished':
			lines.extend(['## Finished', '', f"Status: {event['status']}", ''])
		elif kind == 'worker_failed':
			lines.extend(['## Worker Error', '', event['error'], ''])
	trajectory_path.write_text('\n'.join(lines).rstrip() + '\n', encoding='utf-8')


class BrowserWorker:
	"""Thin project runner around the migrated Browser Use Agent."""

	def __init__(
		self,
		api_key: str,
		base_url: str,
		model: str,
		*,
		session_mode: SessionMode = 'inherited',
		max_steps: int = 100,
		benchmark_mode: bool = False,
	):
		if session_mode not in ('inherited', 'clean'):
			raise ValueError("session_mode must be 'inherited' or 'clean'")
		self.api_key = api_key
		self.base_url = base_url
		self.model = model
		self.session_mode = session_mode
		self.max_steps = max_steps
		self.benchmark_mode = benchmark_mode
		self._active: dict[str, Agent] = {}
		self._active_dirs: dict[str, Path] = {}
		self._snapshot_dir: Path | None = None
		self._snapshot_profile = 'Default'
		self._snapshot_lock = asyncio.Lock()

	@property
	def active_worker_ids(self) -> tuple[str, ...]:
		return tuple(self._active)

	@property
	def waiting_requests(self) -> dict[str, dict[str, Any]]:
		return {
			worker_id: agent.takeover_request
			for worker_id, agent in self._active.items()
			if agent.state.paused and agent.takeover_request is not None
		}

	async def __call__(self, worker_dir: Path) -> None:
		await self.run(worker_dir)

	async def _ensure_edge_snapshot(self) -> tuple[Path, str]:
		async with self._snapshot_lock:
			if self._snapshot_dir is None:
				edge_path = find_edge_executable()
				if edge_path is None:
					raise RuntimeError('Microsoft Edge was not found')
				user_data_dir = get_edge_user_data_dir()
				self._snapshot_profile = get_last_used_profile(user_data_dir) or 'Default'
				snapshot = BrowserProfile(
					executable_path=edge_path,
					channel=BrowserChannel.MSEDGE,
					user_data_dir=user_data_dir,
					profile_directory=self._snapshot_profile,
					headless=False,
					enable_default_extensions=False,
				)
				self._snapshot_dir = Path(snapshot.user_data_dir)
			return self._snapshot_dir, self._snapshot_profile

	async def _create_profile(self, worker_dir: Path) -> BrowserProfile:
		edge_path = find_edge_executable()
		if edge_path is None:
			raise RuntimeError('Microsoft Edge was not found')

		artifacts_dir = worker_dir / 'artifacts'
		downloads_dir = artifacts_dir / 'downloads'
		downloads_dir.mkdir(parents=True, exist_ok=True)
		profile_args: dict[str, Any] = {
			'executable_path': edge_path,
			'channel': BrowserChannel.MSEDGE,
			'headless': False,
			'keep_alive': False,
			'enable_default_extensions': False,
			'downloads_path': downloads_dir,
		}
		if self.session_mode == 'inherited':
			snapshot_dir, profile_name = await self._ensure_edge_snapshot()
			worker_profile_dir = Path(tempfile.mkdtemp(prefix='browser-use-user-data-dir-'))
			try:
				shutil.copytree(snapshot_dir, worker_profile_dir, dirs_exist_ok=True)
			except Exception:
				shutil.rmtree(worker_profile_dir, ignore_errors=True)
				raise
			profile_args['user_data_dir'] = worker_profile_dir
			profile_args['profile_directory'] = profile_name
		return BrowserProfile(**profile_args)

	@staticmethod
	def _available_files(worker_dir: Path) -> list[str]:
		task_dir = worker_dir.parents[1]
		paths: list[Path] = []
		inputs_dir = task_dir / 'inputs'
		if inputs_dir.is_dir():
			paths.extend(path for path in inputs_dir.rglob('*') if path.is_file())
		for sibling in (task_dir / 'workers').iterdir():
			if sibling == worker_dir or not sibling.is_dir():
				continue
			paths.extend(path for path in sibling.rglob('*') if path.is_file())
		return [str(path) for path in paths]

	async def run(self, worker_dir: Path) -> None:
		worker_dir = Path(worker_dir)
		worker_id = worker_dir.name
		request_path = worker_dir / 'request.md'
		request = request_path.read_text(encoding='utf-8')
		artifacts_dir = worker_dir / 'artifacts'
		screenshots_dir = artifacts_dir / 'screenshots'
		screenshots_dir.mkdir(parents=True, exist_ok=True)
		trace_path = worker_dir / 'trace.jsonl'
		trajectory_path = worker_dir / 'trajectory.md'
		trace_path.write_text('', encoding='utf-8')
		_append_trace(trace_path, 'worker_started', worker_id=worker_id, request=request, session_mode=self.session_mode)

		profile = await self._create_profile(worker_dir)
		session = BrowserSession(browser_profile=profile)
		llm = ChatOpenAI(
			api_key=self.api_key,
			base_url=self.base_url,
			model=self.model,
			default_headers={'User-Agent': 'collab-agent'},
		)
		agent_options: dict[str, Any] = {
			'task': request,
			'llm': llm,
			'browser_session': session,
			'file_system_path': str(artifacts_dir),
			'available_file_paths': self._available_files(worker_dir),
			'use_vision': True,
			'enable_signal_handler': False,
		}
		if self.benchmark_mode:
			agent_options['output_model_schema'] = BenchmarkAgentResponse
		agent = Agent(
			**agent_options,
		)
		self._active[worker_id] = agent
		self._active_dirs[worker_id] = worker_dir
		turn = 0
		run_started = False
		benchmark = None
		if self.benchmark_mode:
			benchmark = json.loads((worker_dir / 'benchmark.json').read_text(encoding='utf-8'))
		hosts = {urlsplit(url).netloc for url in benchmark['start_urls']} if benchmark else None
		network_recorder = NetworkRecorder(allowed_hosts=hosts) if self.benchmark_mode else None

		async def on_step_end(current_agent: Agent) -> None:
			nonlocal turn
			turn += 1
			history_item = current_agent.history.history[-1]
			screenshot_ref = None
			screenshot_source = history_item.state.screenshot_path
			if screenshot_source:
				screenshot_path = screenshots_dir / f'turn-{turn}.png'
				shutil.copy2(screenshot_source, screenshot_path)
				history_item.state.screenshot_path = str(screenshot_path)
				screenshot_ref = screenshot_path.relative_to(worker_dir).as_posix()

			model_output = history_item.model_output
			arguments = model_output.model_dump(exclude_none=True, mode='json') if model_output else {}
			results = [result.model_dump(exclude_none=True, mode='json') for result in history_item.result]
			_append_trace(
				trace_path,
				'turn',
				turn=turn,
				url=history_item.state.url,
				screenshot=screenshot_ref,
				messages=_serialize_messages(current_agent.last_model_input_messages, screenshot_ref),
				response_content=current_agent.last_model_response_content,
				tool_call={
					'id': current_agent.last_model_tool_call_id,
					'name': current_agent.last_model_tool_name,
					'arguments': arguments,
				},
				results=results,
			)
			if current_agent.state.paused and current_agent.takeover_request:
				_append_trace(
					trace_path,
					'waiting_for_user',
					reason=current_agent.takeover_request['reason'],
					instruction=current_agent.takeover_request['instruction'],
				)
			_render_trajectory(trace_path, trajectory_path)

		try:
			await session.start()
			if network_recorder is not None:
				await network_recorder.start(session)
			await session.hide_window()
			if benchmark is not None:
				await session.set_extra_headers(benchmark['extra_headers'])
				for index, url in enumerate(benchmark['start_urls']):
					await session.navigate_to(url, new_tab=index > 0)
			run_started = True
			history = await agent.run(max_steps=self.max_steps, on_step_end=on_step_end)
			status = 'completed' if history.is_done() and history.is_successful() is True else 'failed'
			message = history.final_result() or 'Browser Agent stopped without a final result.'
			if network_recorder is not None:
				structured_response = history.structured_output
				if structured_response is None:
					raise RuntimeError('Browser Agent did not produce a structured benchmark response')
				response_text = structured_response.model_dump_json()
				message = _benchmark_handoff(structured_response)
				await network_recorder.write(artifacts_dir / 'network.har')
				(artifacts_dir / 'agent-response.txt').write_text(response_text, encoding='utf-8')
			self._write_result(worker_dir, status, message)
			_append_trace(trace_path, 'worker_finished', status=status, result_file='result.md')
			_render_trajectory(trace_path, trajectory_path)
		except Exception as error:
			_append_trace(trace_path, 'worker_failed', error=f'{type(error).__name__}: {error}')
			_render_trajectory(trace_path, trajectory_path)
			if not run_started:
				await session.kill()
			raise
		finally:
			self._active.pop(worker_id, None)
			self._active_dirs.pop(worker_id, None)

	@staticmethod
	def _write_result(worker_dir: Path, status: str, message: str) -> None:
		artifacts = sorted(path.relative_to(worker_dir).as_posix() for path in (worker_dir / 'artifacts').rglob('*') if path.is_file())
		artifact_lines = [f'- `{path}`' for path in artifacts] or ['- None']
		content = [
			'# Browser Worker Result',
			'',
			f'Status: {status}',
			'',
			'## Handoff',
			'',
			message,
			'',
			'## Artifacts',
			'',
			*artifact_lines,
			'',
		]
		(worker_dir / 'result.md').write_text('\n'.join(content), encoding='utf-8')

	def _get_active(self, worker_id: str) -> Agent:
		agent = self._active.get(worker_id)
		if agent is None:
			raise ValueError(f'Browser Worker is not active: {worker_id}')
		return agent

	async def takeover(self, worker_id: str) -> None:
		agent = self._get_active(worker_id)
		if not agent.state.paused:
			raise ValueError(f'Browser Worker is not waiting for user takeover: {worker_id}')
		await agent.browser_session.show_window()

	async def resume(self, worker_id: str) -> None:
		agent = self._get_active(worker_id)
		if not agent.state.paused:
			raise ValueError(f'Browser Worker is not paused: {worker_id}')
		await agent.browser_session.hide_window()
		worker_dir = self._active_dirs[worker_id]
		_append_trace(worker_dir / 'trace.jsonl', 'worker_resumed')
		agent.resume()

	async def cancel(self, worker_id: str) -> None:
		agent = self._get_active(worker_id)
		worker_dir = self._active_dirs[worker_id]
		_append_trace(worker_dir / 'trace.jsonl', 'worker_cancelled')
		agent.stop()

	async def close(self) -> None:
		for agent in list(self._active.values()):
			agent.stop()
		if self._snapshot_dir is not None:
			shutil.rmtree(self._snapshot_dir, ignore_errors=True)
			self._snapshot_dir = None
