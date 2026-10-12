# Transport to PC session bridge
WindowsTransportReader reads a bounded native devices report without inheriting console output, requiring one opened USB/MTP-declared device. Unknown protocol, missing USB evidence, multiple devices, malformed report, timeout and errors do not enable transport. Raw selectors are never logged.

TransportCoordinator invalidates the PC session on transport loss, changed selector, stale observation or shutdown, while RecoveryController preserves its encrypted synthetic pending delta. It does not grant or renew application editing permission.
Only one event loop owns the controller/coordinator. A separate worker probes at most once per second with no overlapping requests; a 100 ms event-loop tick checks lease/freshness independently of a blocked probe. The PoC rejects native probes after 2 seconds and observations at least 3 seconds old. These are PoC limits, not production timing requirements.
This is polling, not native plug-and-play event subscription; it can miss a sufficiently fast disconnect/reconnect when the selector does not change. Android session identity/lease checks remain required.

## Interactive mock-session fixture
TransportMonitorSmoke <helper.exe> <local-private-journal-directory> <10..120 seconds> --mock-session
On first ready transport ONLY, the explicitly named fixture seeds a fake grant, snapshot and synthetic delta. Production code never does this. Disconnect revokes editing/releases snapshot; reconnect only restores transport, not a grant. Pending data stays protected locally. The fixture never writes phone files or acknowledges/replays its delta.
Log output contains enum/boolean state only. Use a dedicated build directory and do not run this hardware fixture in public CI. Lease and journal tests in CI use fake transport reports.
No private Android lock, application authentication or real-data recovery is implied.

## Connected lease-expiry fixture
After the explicit monitor fixture has saved its synthetic pending edit, run with JDK 17 or later:
```text
java -cp <poc-cli.jar> tools/LeaseExpirySmoke.java <helper.exe> <same-local-private-journal-directory>
```
Keep one phone connected, unlocked and in file-transfer mode. This local fixture uses the CI-built controller with a 15-second mock grant, samples real transport synchronously, ticks the controller, and observes five further seconds after expiry. Any sampled transport loss invalidates the trial. It requires the existing fake-monitor-phone/revision-1 journal and checks that its synthetic pending edit can be reloaded unchanged after expiry. It never writes phone files, renews the grant or acknowledges the delta. Success is lease-expiry-verified=1 and process exit 0.
This driver is separate from the asynchronous monitor: it does not measure expiry latency or verify expiry during a blocked native probe. CI covers the controller's lease logic; hardware execution is manual only.
