# Windows native transport probe
This helper is independent of SessionGate. It cannot grant an editing session or lock the phone.
C++ is restricted to the Windows WPD boundary; the application and common core remain Kotlin.
Compose UI and Kotlin process integration are still pending.

## Build / automatic checks
Windows 10/11 x64, Visual Studio 2022 C++ tools and Windows SDK, CMake:
```powershell
cmake -S native/windows -B build/native -A x64
cmake --build build/native --config Release
ctest --test-dir build/native -C Release --output-on-failure
```
CI compiles the actual WPD calls and tests bounded memory streams (0/1/65536 bytes), corruption, truncation, oversize rejection and SHA-256. It does not have a phone.

## When requested: connect a disposable-test phone
1. Create an empty folder named EveryArticle-PoC on the phone. No journals/photos needed.
2. Connect a data-capable USB cable, unlock, choose file transfer. No USB debugging / ADB needed.
3. Run devices. Its local device-id is an ephemeral selector; keep it private.
4. Run children with that exact device-id and DEVICE, then with returned storage/folder IDs to reach your selected EveryArticle-PoC folder. Each call lists only direct children (limit 256), never recursively reads files.
5. Select its exact object ID, then invoke roundtrip with the explicit synthetic-write flag.
```powershell
.\build\native\Release\wpd-probe.exe devices
.\build\native\Release\wpd-probe.exe children '<device-id>' 'DEVICE'
.\build\native\Release\wpd-probe.exe children '<device-id>' '<storage-object-id>'
.\build\native\Release\wpd-probe.exe roundtrip '<device-id>' '<EveryArticle-PoC-object-id>' --synthetic-write 64k
.\build\native\Release\wpd-probe.exe roundtrip '<device-id>' '<EveryArticle-PoC-object-id>' --synthetic-write empty
```
Commands above are templates, not actual identifiers. Re-enumerate IDs after every reconnect. A name match is only a write-scope guard, never app identity.
Output may contain device IDs and directory names; do not paste raw output into this public repository. Report only flags, HRESULT, size, hash and test case.
Successful roundtrip creates ONE new random-named synthetic file with a Unicode suffix, reads only that new object, checks byte equality and computes SHA-256. It does not overwrite/delete existing objects. Remove the synthetic files manually afterwards.

## Interpretation and faults
wpd-device-count does not count all USB devices. Charging-only can produce no WPD device and is not proof of no USB cable.
usb-declared and usb-pnp-ancestor must both be true before writing. Unknown/unresolvable ancestry fails closed. This may reject otherwise functional devices; do not weaken the guard without investigating.
reported-protocol is the driver's raw declaration, NOT verified MTP classification. WPD can also expose PTP or other transports. Verify MTP mode in the Windows/phone settings and record protocol locally.
A fresh read-only Open does not prove write capability. roundtrip-verified proves only this fixture's read/write path, never Android application commit.
Each process has a 30-second hard deadline (exit 124). On failure/timeout no success is emitted. A write can leave a partial synthetic file; inspect the dedicated folder manually. Never retry automatically.
Test unplug during transfer, USB-mode switch, lock-screen, access denial, multiple devices. Preserve errors as errors, not disconnected or success.
No Android edits/real data, snapshot ingestion, encryption, durable recovery or session integration in this helper.

## Download and exit status
The successful Windows CI run publishes wpd-probe-windows-x64 (7-day retention), containing the executable and this guide. Build it locally when artifacts expire.
Treat a test as successful only when BOTH roundtrip-verified=1 and exit code 0 are present. Timeout/other nonzero exit means failure even if earlier progress was printed.
Device output uses Unicode on a Windows console and UTF-8 when redirected.
The local agent runtime is currently blocked by the MXC E: launcher error; do not connect a phone until a local execution path is ready and the user is notified.
