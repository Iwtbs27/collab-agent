from __future__ import annotations

import json
import os
import platform
import subprocess
from pathlib import Path


def _chrome_user_data_dir_for_executable(executable_path: str | None) -> Path | None:
	if executable_path is None:
		return None

	system = platform.system()
	path = Path(executable_path)
	if system == 'Darwin':
		app_path = str(path)
		base = Path.home() / 'Library' / 'Application Support'
		if 'Chromium.app' in app_path:
			return base / 'Chromium'
		if 'Google Chrome Canary.app' in app_path:
			return base / 'Google' / 'Chrome Canary'
		if 'Google Chrome.app' in app_path:
			return base / 'Google' / 'Chrome'
	if system == 'Linux':
		name = path.name
		base = Path.home() / '.config'
		if name in {'chromium', 'chromium-browser'}:
			return base / 'chromium'
		if name in {'google-chrome', 'google-chrome-stable'}:
			return base / 'google-chrome'
	if system == 'Windows' and path.name.lower() == 'chrome.exe':
		return Path(os.path.expandvars(r'%LocalAppData%\Google\Chrome\User Data'))
	if system == 'Windows' and path.name.lower() == 'msedge.exe':
		return get_edge_user_data_dir()

	return None


def get_edge_user_data_dir() -> Path:
	"""Return the regular Microsoft Edge user-data directory."""
	return Path(os.environ['LOCALAPPDATA']) / 'Microsoft' / 'Edge' / 'User Data'


def find_edge_executable() -> str | None:
	"""Find the stable Microsoft Edge executable."""
	if platform.system() == 'Windows':
		paths = (
			Path(os.environ.get('PROGRAMFILES(X86)', '')) / 'Microsoft' / 'Edge' / 'Application' / 'msedge.exe',
			Path(os.environ.get('PROGRAMFILES', '')) / 'Microsoft' / 'Edge' / 'Application' / 'msedge.exe',
			Path(os.environ['LOCALAPPDATA']) / 'Microsoft' / 'Edge' / 'Application' / 'msedge.exe',
		)
		for path in paths:
			if path.is_file():
				return str(path)
	return None


def _read_profile_state(user_data_dir: str | Path) -> dict:
	state_path = Path(user_data_dir) / 'Local State'
	if not state_path.is_file():
		return {}
	try:
		return json.loads(state_path.read_text(encoding='utf-8'))
	except (json.JSONDecodeError, OSError):
		return {}


def get_last_used_profile(user_data_dir: str | Path) -> str | None:
	"""Return the directory name of the last active Chromium profile."""
	profile = _read_profile_state(user_data_dir).get('profile', {})
	last_used = profile.get('last_used') if isinstance(profile, dict) else None
	return last_used if isinstance(last_used, str) else None


def list_chromium_profiles(user_data_dir: str | Path) -> list[dict[str, str]]:
	"""List profile directory names and their display names."""
	profile = _read_profile_state(user_data_dir).get('profile', {})
	info_cache = profile.get('info_cache', {}) if isinstance(profile, dict) else {}
	if not isinstance(info_cache, dict):
		return []
	return sorted(
		[
			{'directory': directory, 'name': info.get('name', directory)}
			for directory, info in info_cache.items()
			if isinstance(info, dict)
		],
		key=lambda item: item['directory'],
	)


def find_chrome_executable() -> str | None:
	"""Find Chrome/Chromium executable on the system."""
	system = platform.system()

	if system == 'Darwin':
		for path in (
			'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
			'/Applications/Chromium.app/Contents/MacOS/Chromium',
			'/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary',
		):
			if os.path.exists(path):
				return path

	elif system == 'Linux':
		for cmd in ('google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser'):
			try:
				result = subprocess.run(['which', cmd], capture_output=True, text=True)
				if result.returncode == 0:
					return result.stdout.strip()
			except Exception:
				pass

	elif system == 'Windows':
		for path in (
			os.path.expandvars(r'%ProgramFiles%\Google\Chrome\Application\chrome.exe'),
			os.path.expandvars(r'%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe'),
			os.path.expandvars(r'%LocalAppData%\Google\Chrome\Application\chrome.exe'),
		):
			if os.path.exists(path):
				return path

	return None


def get_chrome_profile_path(profile: str | None, executable_path: str | None = None) -> str | None:
	"""Get Chrome user data directory, or return a specific profile directory name."""
	if profile is not None:
		return profile

	if browser_user_data_dir := _chrome_user_data_dir_for_executable(executable_path):
		return str(browser_user_data_dir)

	system = platform.system()
	if system == 'Darwin':
		return str(Path.home() / 'Library' / 'Application Support' / 'Google' / 'Chrome')
	if system == 'Linux':
		base = Path.home() / '.config'
		for name in ('google-chrome', 'chromium'):
			if (base / name).is_dir():
				return str(base / name)
		return str(base / 'google-chrome')
	if system == 'Windows':
		return os.path.expandvars(r'%LocalAppData%\Google\Chrome\User Data')

	return None


def list_chrome_profiles() -> list[dict[str, str]]:
	"""List available Chrome profiles with their display names."""
	user_data_dir = get_chrome_profile_path(None, executable_path=find_chrome_executable())
	if user_data_dir is None:
		return []

	return list_chromium_profiles(user_data_dir)
