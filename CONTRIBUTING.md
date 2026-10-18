# Contributing to OpenCyvis

Thanks for your interest in contributing!

## Getting Started

1. Fork the repository
2. Clone your fork
3. Create a feature branch
4. Make your changes
5. Run tests
6. Submit a pull request

## Building

### Android
```bash
cd android
./gradlew assembleDebug
```

### Python CLI
```bash
pip install -e .
python -m opencyvis --help
```

## Running Tests

### Android unit tests
```bash
cd android
./gradlew testDebugUnitTest
```

### Python tests
```bash
python -m pytest tests/ -v
```

## Code Style

- Kotlin: follow standard Kotlin conventions
- Python: follow PEP 8
- Write tests for new features
- Keep commits focused and well-described

## Reporting Issues

Use GitHub Issues. Include:
- Device model and Android version
- Steps to reproduce
- Expected vs actual behavior
- Logs if available

## Security

If you find a security vulnerability, please report it privately via email rather than opening a public issue.
