# `:headless` — the SDK

Empty on purpose. This module is gated on the probe returning a go.

Layout, once it starts:

| Path | Holds |
|---|---|
| `browser/` | Public API: `HeadlessBrowser`, `Page`, `Config`, `Capabilities`, errors |
| `browser/core/` | Session, settle engine, timeouts, scheduler. Backend-agnostic |
| `browser/platform/` | `androidx.webkit` backend: offscreen host, interception, injection, bitmap capture |
| `browser/protocol/` | LocalSocket transport, RFC 6455, CDP connection and session |
| `browser/protocol/domains/` | Typed commands, reliable domains only |
| `browser/internal/` | Capability probe, metrics |
| `assets/js/` | Injected page-side instrumentation: mutation observer, fetch/XHR hooks |
| `src/test/` | Framing, settle logic, error mapping. No device |
| `src/androidTest/` | Lifecycle, memory, crash recovery. Real hardware |

Rules that hold from the first commit: `platform/` never depends on `protocol/`,
nothing here imports a consumer, and no item merges without a passing test.
