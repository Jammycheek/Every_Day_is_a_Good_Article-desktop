# Connection feedback and bounded reply polling

## Report and observations (2026-10-16)

During synthetic hardware validation at desktop `5c0c398`, the user reported intermittent failures and a slow
transition from Connect/import to the editor. The screenshot showed USB/MTP unavailable, no grant, a protected
unsubmitted edit, and `No USB/MTP phone`. The phone's PC-link screen was opened partway through the attempt.
This screen polls only while open, but opening it late does **not** explain an unavailable WPD selector by itself.

Read-only checks afterwards found one USB/MTP phone ready in all six samples (76–123 ms). Mailbox listing succeeded
in all four samples (164–219 ms). These measurements do not reproduce or explain the earlier probe failure.
No pending edit, pairing, or phone message was removed during these checks.

## Changes

- Each authenticated request's reply budget starts before sending and includes send, receive, decode/delete and
  sleep time on an injected monotonic clock. The old loop counted only 250 ms sleeps, multiplying a nominal
  20-second wait when MTP polling was slow. A response completing at or after the budget is not returned as timely.
- The five connection steps (USB, phone reply, pending reconciliation, permission, import) appear with an
  indeterminate progress bar. The phone-screen requirement is visible during its response wait.
- A connect click is claimed synchronously before entering the worker queue, so repeated clicks do not queue
  another connection attempt. The button and pairing removal are disabled during connection.
- A failed connection releases any acquired grant and clears the snapshot before permitting a retry. Protected
  pending edits and unsaved-input handling retain the existing rules.
- A failed WPD observation is described as **unconfirmed connectivity**, not proof of a physically unplugged cable.
  The UI distinguishes no enumerated device, ambiguous devices, unavailable MTP access and a failed probe.
  A missing selector no longer produces an unexplained English error.

## Limits and unchanged safety rules

The reply budget is per request, not one deadline for the entire multi-request import. It does not preempt a
native call already blocked in WPD: helper calls still have their own 30-second limit (device probing: 2 seconds),
and pipe collection/cleanup adds bounded overhead. A late return is rejected on the next clock check. Connect
failure cleanup may also send a best-effort release. Do not promise an absolute 20-second total UI wait.

A lost or invalid probe still suspends editing immediately. This change does not retain an old selector or keep
editing during uncertain connectivity. Requests are authenticated and nonce-bound as before. No protocol, Android
source, permission-grant validation, automatic edit resubmission, or journal-format changes are included.

The original intermittent USB failure remains unconfirmed. The foreground requirement is unchanged: open the
phone's PC-link screen **before** Connect/import and keep it open. A screen opened during the reply budget can
still answer; it must not be categorically blamed for all connection failures.

### Follow-up: failure captured and manual retry (2026-10-16)

The next screenshot showed a failed probe and a disabled Connect/import button. The disabled button was a UI
dead end: the action itself already performs a fresh probe before sending anything, so it must be enabled even
when the last background probe was unsuccessful. It remains disabled while a connection is queued or in progress.

During diagnosis, device enumeration found one USB descendant, but WPD open did not finish within a 3.5-second
diagnostic window in either normal or isolated LOCALAPPDATA environments. The blocked diagnostic child processes
were terminated; neither phone data nor protected edits were changed. The app was closed through its normal
save/release path to stop periodic probing. After the user reconnected USB, WPD open finished in 129 ms. The actual
Java WindowsTransportReader then returned USB_MTP_READY in all three samples (201, 90 and 112 ms).

This captures the WPD-open stall symptom and its recovery, not the underlying Windows/phone cause. The retry button
does not bypass transport checks or allow editing while transport is unconfirmed. Its regression case first rejects
an unavailable transport without sending, then accepts a fresh successful check, preserving pending text without
applying it to the phone. Final UI import and pending-text restoration remain user verification steps.

## Regression coverage

Synthetic tests cover slow sends, slow receives, a reply exactly at the deadline, slow deletes, slow empty polls,
opening the phone screen partway through a request, connection stage publication, unavailable transport preserving
pending text, snapshot failure followed by a successful reconnect, and duplicate connection clicks.

On Windows/JDK17, desktop-core 37 tests (including nine new tests) passed with zero failures/skips; protocol 19
tests also passed. The Compose UI compiled and the launch classpath was generated. In a controlled comparison,
only the polling function was replaced by its old sleep-counter algorithm (keeping the clock/test injection);
all four slow-I/O tests failed. The fixed source was restored and the full suites passed afterwards.

Real-hardware retry with this change is still required. The earlier 126 root recovery tests and native helper
were unchanged; their earlier successful execution is not represented as a fresh run for this change.

## Follow-up: property retrieval isolated (2026-10-16)

The user retried three times and still saw an ERROR probe. No further click retries were requested. Diagnostic
stage tracing corrected the earlier assumption about `Open`: `Open` and `Content` completed, and a 12-second
diagnostic window expired while retrieving device properties. The shared property reader used `GetValues` with a
NULL key collection, requesting every attribute. [Microsoft's API contract](https://learn.microsoft.com/en-us/windows/win32/api/portabledeviceapi/nf-portabledeviceapi-iportabledeviceproperties-getvalues)
confirms that NULL requests all attributes.

All WPD property calls now pass nonempty, explicit key collections. The connection probe retrieves transport and
protocol together once; mailbox traversal requests only the names, types, parents or sizes it needs. Missing or
unsupported required attributes still fail closed, and USB ancestry, USB declaration and MTP evidence remain
mandatory. No selector is reused after a failed probe.

Without another USB reset, the selective-property native probe completed in 98 ms and the application's Java
reader returned READY three times (219, 112, 118 ms). Native CTest 2/2 passed. Optional diagnostic stages contain
only fixed labels and process IDs, with no device IDs or message content. Set EVERYARTICLE_PROBE_TRACE=1 and
optionally EVERYARTICLE_PROBE_TRACE_FILE to a local path; production launches leave these unset.

Full read-only import validation then passed the transport check but timed out in the first authenticated status
request's native stream Write. No edit was submitted. A temporary-name publishing prototype did not resolve that
write stall and was removed; existing wire and publish behavior remain unchanged. Some failed synthetic message
objects or ignored .part objects may remain from diagnostics. No user file was deleted.

The app remains normally closed, with its pending edit protected. A fresh USB reset with **only** the selective
property helper installed is the next controlled comparison. Do not mark the full import or user-visible recovery
as passed from the probe-only results. The broader native property change is not covered by the earlier JVM run;
its actual mailbox/stream validation remains pending.

## Follow-up: successful full imports with COM release ordering

After the requested USB reset, the selective-property build completed the status-request write, but the next
short-lived client stalled in Open. The old Device destructor explicitly called Close while its Content interface
was still held. The destructor now releases Content first and then the Device COM reference, allowing WPD to close
at final release instead of forcibly invalidating live child interfaces. This follows the lifecycle described in
[IPortableDevice::Close](https://learn.microsoft.com/en-us/windows/win32/api/portabledeviceapi/nf-portabledeviceapi-iportabledevice-close).

No further USB reset was needed before testing this change. A local Java validation driver used the actual
PcSyncClient, WpdMailbox, DPAPI pairing and existing protected journal. It called initialize, connect and release;
it never called saveDraft, submit, resubmit, discard or forget. It compared the loaded pending RecoveryEdit before
and after each connection and printed no article content, peer IDs or keys.

Three consecutive runs in separate JVMs succeeded:

| Run | Status through snapshot | Articles | Pending edit equal before/after |
| --- | --- | --- | --- |
| 1 | 8,294 ms | 6 | yes |
| 2 | 5,423 ms | 6 | yes |
| 3 | 5,626 ms | 6 | yes |

Each run acquired and released a real synthetic edit grant, so normal verified history/epochs advanced. The pending
edit did not change and no article edit was submitted. Native CTest 2/2 passed. This provides evidence of recovery
with the revised implementation; it does not prove how the underlying Windows/Android stack entered the bad state,
nor establish behavior on other devices. UI import and editor-text restoration still require the user's view.
