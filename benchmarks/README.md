# OmniAct Benchmarks

This directory contains the small, versioned evaluation surface maintained by
OmniAct. Upstream repositories, service data, VM images, fixtures, browser
profiles, and run outputs stay outside the Git repository.

## Layout

```text
benchmarks/
├─ README.md
├─ environment.yml
├─ sources.yaml
├─ baseline.py
├─ runner.py
├─ verifiers.py
└─ cases/
   ├─ local_browser.yaml
   ├─ local_desktop.yaml
   └─ local_cross.yaml
```

The manifests keep upstream case identifiers and execution metadata. They do
not redistribute upstream fixtures, golden files, VM images, or evaluators.

## Local workspace

Set the external workspace in the project root `.env`:

```dotenv
OMNIACT_BENCH_HOME=E:\workspace\OmniAct-Benchmark
NLTK_DATA=E:\workspace\OmniAct-Benchmark\fixtures\nltk_data
```

The workspace uses the following layout:

```text
OmniAct-Benchmark/
├─ upstreams/
├─ services/
├─ images/
├─ fixtures/
├─ profiles/
└─ runs/
```

## Control environment

Run these commands from the OmniAct repository root:

```powershell
conda env create -f benchmarks\environment.yml
conda activate omniact-bench
python -m pip install -r requirements.txt
```

`omniact-bench` runs OmniAct and coordinates evaluation. Each upstream keeps
its official dependency environment and evaluator. The control environment
does not import those packages directly.

The current upstream environments are intentionally isolated:

| Environment | Purpose |
| --- | --- |
| `omniact-up-webarena` | WebArena-Verified tasks and evaluator |
| `omniact-up-real` | REAL web clones and evaluator |
| `webchorearena` | WebChoreArena tasks and evaluator |
| `omniact-up-waa` | WindowsAgentArena controller and evaluator |
| `omniact-up-tac` | TheAgentCompany evaluation tooling |

Poetry environments, Playwright browsers, Docker data, VM images, and package
caches must also stay in the external workspace or another explicitly chosen
non-system drive. They are not part of the Git repository.

## Baseline runner

Run the three-case pilot, the full Local-35 track, or one case:

```powershell
python -m benchmarks.baseline pilot --bench-home E:\workspace\OmniAct-Benchmark
python -m benchmarks.baseline full --bench-home E:\workspace\OmniAct-Benchmark
python -m benchmarks.baseline LB04 --bench-home E:\workspace\OmniAct-Benchmark --attempt 1
```

The pilot contains `LB04`, `LD02`, and `LX02`. Runs are serial and write the
task workspace, `evaluation.json`, `summary.json`, and `summary.md` below
`runs\pilot-laptop-v1` or `runs\baseline-laptop-v1`. Browser cases use the
official WebArena-Verified evaluator; Desktop and cross-application cases use the
deterministic local verifiers. Benchmark runs do not allow user takeover.

## Current status

`Local-35` is the laptop-oriented execution surface: 15 Browser, 15 Desktop,
and 5 cross-application cases. Its manifests retain upstream identifiers and mark
local adaptations explicitly. `runner.py` provides manifest validation,
requirement checks, reset, deterministic evaluation, and case-owned process
cleanup; `verifiers.py` contains the non-LLM checks used by Desktop and cross
cases.

Each baseline result records its upstream, actual evaluator, score scope, and
environment scope. Summaries group results by those scopes and do not publish a
mixed aggregate as an official upstream benchmark score. Browser cases report
`container_local`; Desktop and cross-application cases report
`host_local_unisolated`. The completed checks are no-model checks only. All 15 Browser cases reset and
return a normal `failure` from the official WebArena-Verified evaluator for a
schema-valid incorrect result. All 20 Desktop/Cross cases reset and correctly
fail from their initial state. The full 139-test project suite and active-source
compilation pass. Ruff passes for `benchmarks`, `tests`, and the modified project
modules; the copied UFO2 source retains its upstream lint findings. The first
real Pilot-3 was diagnostic only and exposed Browser response/HAR and Cross
target-directory integration defects. The Cross evaluator was then reviewed
against its upstream semantics: real `LX02` attempt-2 passed with score `1.0`
using an isolated VS Code profile workspace check. The repaired `LB04` and `LD02`
also passed their individual real attempts with score `1.0`. A complete Pilot-3
run remains optional for publishing one unified baseline report and is not needed
to validate this evaluator repair.

The original Core-35 plan remains the Extended Track. Shopping Admin, GitLab,
VisualWebArena, Windows VMs, and TheAgentCompany services remain intentionally
uninstalled because they require substantial additional disk space,
user-accepted Windows media, or VMware assets. The real Pilot-3, full
heavy-environment validation, and `baseline-laptop-v1` are still pending.
