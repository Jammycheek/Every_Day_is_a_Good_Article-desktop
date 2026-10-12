# Every_Day_is_a_Good_Article-desktop

Windows-first desktop companion PoC. Kotlin Multiplatform core, Kotlin/JVM process runner,
Windows WPD helper and planned Compose Multiplatform UI. The phone remains authoritative.

See [PoC plan](docs/POC-PLAN.md) and [Windows probe guide](docs/WINDOWS-PROBE.md).

Implemented: synthetic session gate with six tests, WPD/USB evidence, direct-child listing,
explicit synthetic round-trip (empty or 64 KiB), bounded streams and process deadlines.
A WPD device is not automatically an MTP device; reported protocol and USB evidence are separate.
Transport diagnostics never create an editing grant.

Not implemented/verified: actual phone results, private Android editing lock,
authenticated session channel, durable encrypted recovery, Compose UI, macOS/Linux adapters.
No Android-specific code or real data is included.

JDK 17 + Gradle 8.12.1: `gradle jvmTest`.
Native Windows build/tests and manual commands are in the guide.
After building the helper: `gradle runWindowsProbe` (local device enumeration only).
For phone-free runner verification: `gradle runWindowsProbe -PprobeCommand=self-test`.
Do not run hardware enumeration in public CI logs; local output can contain device identifiers.
