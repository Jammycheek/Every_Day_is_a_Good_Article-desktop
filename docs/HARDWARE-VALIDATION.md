# Windows hardware validation

2026-10-12, local Windows PC, one user-connected phone. No device identifiers, directory listings or application data are included.

## Read-only detection
- Before connection: WPD device count 0.
- After unlock and file-transfer selection: WPD device count 1; read-only WPD Open successful.
- Driver reports USB transport and protocol MTP: 1.00. This is driver evidence, not independent USB packet capture.
- First probe incorrectly treated a device interface symbolic link as a devnode instance identifier. USB ancestry was false despite the transport declaration.
- Fixed by resolving DEVPKEY_Device_InstanceId through CM_Get_Device_Interface_PropertyW before locating and traversing the devnode. Unknown or malformed properties still fail closed; no string-based guessing.
- Rebuilt locally with Visual Studio 2019 Build Tools and Windows SDK 10.0.19041.0. Both CTest cases passed.
- Fixed probe: USB PnP ancestry true, USB transport declaration true, read-only WPD Open successful, protocol MTP: 1.00.

## Execution environment
Default sandbox launcher fails while enumerating an inaccessible encrypted virtual E: drive. An approved alternative execution path works from the C: workspace; the E: volume was not repaired, mounted, formatted or used for task files. Local execution is now available.

## Pending
Real-phone synthetic transfer passed after the user created and confirmed an empty EveryArticle-PoC folder. A differently cased name was rejected before creation; no write occurred in that rejected attempt.
- 64 KiB fixture: 65536 bytes written, committed, read back and compared byte-for-byte; process exit 0.
- Empty fixture: 0 bytes written, committed, read back and compared; process exit 0.
- Both used uniquely named new files with a Unicode filename suffix, and emitted roundtrip-verified=1.
- No existing phone files were modified or deleted. Two synthetic files remain in the dedicated folder for manual cleanup.
- Transfer success is transport evidence only, not Android application apply/commit or editing-lock evidence.
Later sections record USB-mode switching, interrupted transfer and screen-lock observations. Multiple devices, explicit access denial and private Android session integration remain unverified on hardware.

## Reference
https://learn.microsoft.com/en-us/windows/win32/api/cfgmgr32/nf-cfgmgr32-cm_get_device_interface_propertyw

## Idle disconnect / reconnect
The user unplugged and reconnected the phone between independent read-only probe invocations.
- Before unplug: 1 WPD device, both USB checks true, WPD Open successful.
- After unplug: 0 WPD devices, probe exit 0.
- After reconnect/unlock/file-transfer selection: 1 WPD device, both USB checks true, WPD Open successful, driver protocol MTP: 1.00, probe exit 0.
- The local transport selector happened to remain unchanged. It is not trusted as application identity, nor assumed stable across other reconnects.

This confirms point-in-time re-enumeration around an idle disconnect, not continuous event detection, detection latency, an in-flight transfer failure or Android session invalidation/recovery. The native probe is deliberately independent of the synthetic SessionGate. No new file writes occurred in this test.

## Charging-only mode
With the cable left connected, the user switched the phone from file transfer to charging only.
- Before mode change: 1 WPD device, USB declaration and ancestry true, Open successful, protocol MTP: 1.00.
- After mode change: 0 WPD devices, probe exit 0.

Cable presence in this case is user-confirmed, not independently measured by the WPD-only probe. A zero WPD count therefore cannot distinguish unplugged from charging-only. USB attachment, MTP availability, and application editing-session permission must remain separate observations. No file writes occurred. After the user restored file-transfer mode without unplugging: 1 WPD device, USB declaration and ancestry true, Open successful, protocol MTP: 1.00, probe exit 0. The observed sequence was 1 -> 0 -> 1 across file-transfer / charging-only / file-transfer modes. This verifies fresh read-only enumeration and Open after a mode change; it does not verify an interrupted transfer or Android editing-session renewal.

## Screen lock after file-transfer access was established
The user locked the phone while leaving USB connected and file-transfer mode enabled.
- WPD device count remained 1; USB declaration and ancestry true.
- Fresh read-only WPD Open succeeded; driver protocol MTP: 1.00.
- Direct DEVICE child enumeration succeeded (exit 0) and returned one storage/root object.

No file contents were read or written in this test. This demonstrates continued device/metadata access only, not full file access while locked. Initial connection while already locked, reboot-before-first-unlock and other phones/policies remain untested. Screen lock must not be interpreted as an application editing-session revocation or an MTP disconnection based on these results.

## Unplug during throttled synthetic write
Built code 822ef22d2b1cdd49cf64df30566da832ba90ae73 locally; both native CTest cases passed (including the slow-write path).
Started a new random-named 64 KiB synthetic file in the dedicated folder, writing at most 1 KiB per iteration with a one-second pause. After progress began, the user unplugged USB.
- Last successful IStream write progress: 17408 bytes. This is host/driver write evidence, not guaranteed durable phone bytes.
- The helper reported HRESULT 0x802A0002 and exit code 1.
- No roundtrip-verified success marker was emitted; the incomplete transfer was not reported as verified.
- Revert was attempted best-effort; remote rollback or absence of a partial object is not established.

After the user reconnected, unlocked, and restored file-transfer mode, fresh device/folder enumeration succeeded: one device, both USB checks true, WPD Open successful, protocol MTP: 1.00. The dedicated folder contained the two prior successful synthetic files and no additional synthetic file was visible (listing limit not reached). This is evidence of no visible partial object in this trial, not guaranteed rollback or proof of no hidden/internal remnants. A newly named 65536-byte fixture then passed write/commit/readback, byte equality and exit 0. Three successful synthetic files now remain for manual cleanup. Existing files were neither modified nor deleted. This validates the helper's synthetic transport failure path only, not Android editing locks, persistent unacknowledged application deltas or real-data recovery.

## Reconnect while screen remains locked
The user locked the phone, unplugged USB, and reconnected without unlocking.
- Fresh WPD enumeration returned 0 devices, exit 0.
- No device Open or storage enumeration could be attempted; no files were read or written.

This differs from locking after file-transfer access had been established, where device/metadata access remained available. The observation is limited to this phone and its current USB settings; it does not establish whether the cause was lock policy, USB-mode reset, or another device policy. WPD absence is not proof of cable disconnection. After unlocking without unplugging, the user reported that the USB mode was charging only. The user then changed it to file transfer. Fresh enumeration returned 1 device, both USB checks true, WPD Open successful, protocol MTP: 1.00; DEVICE child enumeration returned 1 object, exit 0. Thus the zero-device observation is confounded by USB mode and cannot establish lock-only denial. Reboot-before-first-unlock remains untested. No file reads/writes were performed in these checks.

## Live transport / PC mock-session bridge
Code 8f86a1021b0647f42a034398b18a4b6546b067b1, Windows CI-built helper and Kotlin CLI, explicit --mock-session fixture. CI passed 25 Kotlin tests, native tests and separate-JVM recovery smoke.
The fixture creates a mock grant and synthetic PC snapshot only on its initial ready observation; it does not implement an Android grant or lock.
Within a single running monitor, the user unplugged and then reconnected the phone with file-transfer access restored:
- Initially: USB_MTP_READY, mock-can-edit=true, snapshot=true, pending=true.
- After unplug: NONE, mock-can-edit=false, snapshot=false, pending=true.
- After reconnect: USB_MTP_READY, mock-can-edit=false, snapshot=false, pending=true.
- The local encrypted pending.dpapi file remained present (338 bytes); no plaintext payload or device identifiers are included here.

This demonstrates real WPD polling driving PC mock-session revocation and snapshot release, without automatic renewal on reconnect. The monitor did not write phone files or apply/acknowledge the synthetic delta. A preceding monitor expired normally before reconnect, so that run is not used as evidence of same-process reconnect behavior. Polling can miss a brief unplug/replug between samples, and detection latency was not measured. Actual Android editing lock, authenticated grant renewal and application-level recovery remain unverified.

## Live USB-mode change / PC mock-session bridge
Using the same code and explicit mock-session fixture, the user left the cable connected and changed file transfer to charging only, then restored file transfer within one running monitor.
- Initially: USB_MTP_READY, mock-can-edit=true, snapshot=true, pending=true.
- Charging only: NONE, mock-can-edit=false, snapshot=false, pending=true.
- File transfer restored: USB_MTP_READY, mock-can-edit=false, snapshot=false, pending=true.

Observed MTP loss revoked the PC mock-session permission and released its synthetic snapshot. Restoring MTP did not renew permission or reload the snapshot; the pending synthetic delta remained recorded by the controller. Cable presence was user-confirmed rather than measured independently. No phone files were written and no Android application lock, grant or acknowledgement was exercised. The polling and private-channel limitations above still apply.

## Lease expiry while MTP remains available
The local tools/LeaseExpirySmoke.java driver invoked the same CI-built Kotlin controller/helper with a 15-second mock grant and existing encrypted synthetic journal. An initial attempt found no MTP device and stopped before granting permission; the user then prepared the phone and a fresh trial passed.
- Initial state: USB_MTP_READY, mock-can-edit=true, snapshot=true, pending=true.
- Expired state: USB_MTP_READY, mock-can-edit=false, snapshot=false, pending=true.
- All sampled transport observations remained ready through five seconds after expiry.
- The synthetic edit was reloaded from the encrypted journal and compared equal to its initial value: pending-reloaded-equal=true.
- lease-expiry-verified=1, process exit 0.

This proves controller expiry and snapshot release despite continued sampled MTP availability, and retained synthetic journal contents. Sampling cannot prove uninterrupted physical attachment between observations. Expiry latency and blocked-probe scheduling were not measured by this synchronous driver. No phone files, real application data or private Android session code were involved; actual app lock and authenticated grant renewal remain unverified.

## Normal process restart with phone connected
After the lease-expiry driver JVM exited normally, tools/RecoveryRestartCheck.java ran in a new JVM using the existing encrypted journal and the same CI-built Kotlin controller/helper.
- The journal reloaded equal to the known synthetic fixture: restart-pending-equal=true.
- Before transport observation: mock-can-edit=false, snapshot=false.
- Real transport observation: USB_MTP_READY; mock-can-edit=false, snapshot=false, pending=true.
- process-restart-recovery-verified=1; process exit 0. The driver also compiled for Java 17.

This verifies synthetic pending-edit persistence across normal process termination and restart while connected, without restoring editing permission or snapshot. No grant, acknowledgement, replay or phone write occurred. Windows reboot, forced termination, interruption during journal replacement and power loss remain untested. Recovery is a pending proposal only; actual Android application recovery remains unverified.

## Forced process termination after completed synthetic save
Using tools/CrashHoldSmoke.java with a fresh local journal directory, a dedicated child JVM observed ready real MTP transport, accepted an explicit mock grant, loaded a synthetic snapshot and saved one synthetic edit through the controller. After its crash-fixture-ready marker (editing/snapshot/pending all true), the parent forcibly stopped only that captured child PID and confirmed termination. A new JVM then ran RecoveryRestartCheck against the same directory.
- The pending edit reloaded equal to the known synthetic fixture.
- New-process real transport was USB_MTP_READY; mock-can-edit=false, snapshot=false, pending=true.
- The encrypted journal SHA-256 matched before/after termination and recovery (digest not published).
- forced-stop-recovery-verified=1; encrypted-journal-unchanged=true; parent exited 0.

This verifies retention after forced termination of a paused PoC JVM with a completed save. The snapshot is absent in the new process; this does not prove forensic erasure of the terminated process's memory/pagefile. No phone files were written and no other application was terminated. Interrupting journal replacement, Windows reboot, power loss and private Android lock/recovery remain unverified.

## Corrupted synthetic journal while mock permission is active
tools/CorruptJournalCheck.java used the same CI-built controller/helper with ready real MTP transport. After verifying the original known synthetic edit, it saved a new owner-restricted encrypted copy, created a mock grant/snapshot, and flipped the copy's final byte. The controller's pending read was rejected.
- corrupt-read-rejected=true; mock-can-edit=false; snapshot=false.
- The damaged copy was preserved byte-for-byte, and the original encrypted journal was unchanged.
- corruption-safety-verified=1; process exit 0; Java 17 compilation succeeded.

No phone files or real data were changed. This verifies fail-closed revocation and preservation for one DPAPI corruption case; it does not restore corrupted contents, test every malformed file, or substitute for interruption-during-save testing. The damaged synthetic copy remains in the ignored local build directory.
