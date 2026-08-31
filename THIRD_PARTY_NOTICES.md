# Third-Party Notices

## Benchmark Task Manifests

The task identifiers and original task text under `benchmarks/cases` are
selected from the following public benchmarks. OmniAct does not redistribute
their evaluators, fixtures, website data, VM images, or golden files.

- [WebArena-Verified](https://github.com/ServiceNow/webarena-verified), Apache-2.0
- [REAL / AGI SDK](https://github.com/agi-inc/agisdk), Apache-2.0
- [VisualWebArena](https://github.com/web-arena-x/visualwebarena), MIT
- [WebChoreArena](https://github.com/WebChoreArena/WebChoreArena), Apache-2.0
- [WindowsAgentArena](https://github.com/microsoft/WindowsAgentArena), MIT
- [OSWorld](https://github.com/xlang-ai/OSWorld), Apache-2.0
- [WindowsWorld](https://github.com/HITsz-TMG/WindowsWorld), Apache-2.0
- [TheAgentCompany](https://github.com/TheAgentCompany/TheAgentCompany), MIT

The upstream source revision and execution boundary used by OmniAct are
recorded in `benchmarks/sources.yaml`.

## Browser Use

This project includes modified source code from Browser Use 0.13.8:

https://github.com/browser-use/browser-use

The migrated package is exposed internally as omniact_browser. The upstream
Browser Use attribution remains in the source headers and this notice.

Browser Use is licensed under the MIT License:

```text
MIT License

Copyright (c) 2024 Gregor Zunic

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## UFO²

This project includes modified core source code from Microsoft UFO²:

https://github.com/microsoft/UFO

The migrated desktop runtime is exposed internally as omniact_desktop. The
local Desktop Worker configuration and protocol packages are desktop_config
and desktop_protocol; they are project-local packaging names for the migrated
code, not separate upstream projects. The copied UFO² files retain their
Microsoft copyright and MIT license headers.
