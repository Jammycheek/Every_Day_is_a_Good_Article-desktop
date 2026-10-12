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
