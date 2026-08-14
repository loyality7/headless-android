## What this changes

<!-- One or two sentences. What behaviour is different after this merges? -->

Closes #

## Why

<!-- The reason the change is needed, not a restatement of the diff. If it
     closes an issue that already explains this, one line is enough. -->

## Evidence

<!-- Paste the real output. Not "tests pass". -->

```
./gradlew test
./gradlew lint
```

- [ ] Unit tests written alongside the implementation, green before this was opened
- [ ] Instrumented tests run on a device, where the change can only be verified there
- [ ] Any claim about memory or leaks is backed by a measurement in this PR

## Notes for the reviewer

<!-- Deviations from the issue's scope, deliberate simplifications and their
     ceilings, anything you decided that the issue did not settle. Say so here
     rather than leaving it to be discovered. -->
