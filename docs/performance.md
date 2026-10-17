# Released/candidate performance observations

Windows / Java 21, 2026-10-17. The baseline sources are the released core 1.1.0
commit `9f26823` and Rack 1.0.0 commit `6db3d19`. Both were rebuilt in ignored
build directories; their source files in the working trees were not replaced.
Identical benchmark harnesses ran in separate JVMs, alternating baseline/candidate
three times without other test suites running concurrently.

## Core

Median of three process observations. Factory measurements use five samples of
2,000 lazy session create/close operations after 1,000 warmups. One-shot evaluations
average 20 calls after three warmups. Reused-session evaluation averages 1,000 calls
after 50 warmups. One-shot figures include the new runtime each public call owns.

| Operation | Released | Candidate |
| --- | --- | --- |
| Lazy BIF session create/close | 45.85 μs | 51.11 μs |
| One-shot Ruby evaluation | 202.30 ms | 198.62 ms |
| Reused-session evaluation | 146.20 μs | 147.24 μs |

Native BIF dispatch plus the expanded option validation adds approximately 5 μs to
the lazy factory sample. Runtime execution is comparable. Native wrappers cache
only activation-local Java bridges; request contexts are never cached. Direct
JRuby execution/conversion and runtime synchronization remain Java.

## Rack

Each observation warms 256 requests, then sends 3,200 small custom Rack requests at
client concurrency eight. Medians across three runs:

| Workers | Released requests/s | Candidate requests/s | Released heap increase | Candidate heap increase |
| --- | --- | --- | --- | --- |
| 1 | 840 | 874 | 26.3 MB | 26.7 MB |
| 2 | 1,309 | 1,313 | 31.0 MB | 29.9 MB |

The repeated observations show no consistent HTTP throughput regression. The old
80-request sample was too short and noisy for that comparison, so the committed
benchmark now uses the longer workload. Heap samples include the shared test host
and GC effects; they are not isolated per-runtime memory guarantees.

This is a local custom-Rack observation, not Rails production capacity or an SLA.
Measure the intended framework, request mix, streaming duration and proxy chain
before choosing workers, queues, body limits or database pool sizes.

## Reproduce

Core's `benchmark` task accepts `benchmarkModuleRoot` for extracted sibling module
directories and `benchmarkOutput` for its JSON output. Rack's `benchmark` task uses
the installed candidate modules; use the same harness in a released-source checkout
to compare versions. Raw local observations are in each repo's `build/performance`.
Keep comparison runs sequential and use repeated measurements.
