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
USB-mode switching, disconnect during transfer, denied access, multiple devices, screen locking, and private Android session integration remain unverified. Detection success does not prove any of those behaviors.

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
