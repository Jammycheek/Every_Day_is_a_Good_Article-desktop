# Fourth review: S1 failure classification and PR #1 scope

PR #1 ends with S1 and documentation for S2/S3. No confirmation UI, authentication channel, new storage recovery feature or power-loss trial is added. No private Android code/data is included; main is unchanged.

## S1: distinguish operational failure from rejected/invalid data

The old quarantine preview treated every read exception as damaged history. It now propagates operational failures and offers no preview, warning, rename or reset. The controller suspends editing on a failed read. Pending-journal reads use the same failure categories, but gain no quarantine API.
Prototype consumers that catch specific parsing exceptions must now handle StorageFormatException (the original parsing cause is retained). No journal encoding, digest, lease or recovery-token schema changes.

| Failure | Result | Quarantine preview |
| --- | --- | --- |
| Helper cannot start, timeout, pipe failure, missing/oversized output, unknown/nonzero exit | ProtectionUnavailableException | Never |
| Native unprotect completes input and rejects ciphertext/key with the dedicated status | DecryptionRejectedException | Only after a second classified rejection/format failure |
| Successful decryption but invalid bounded schema/UTF-16/fields, empty or oversized stored file | StorageFormatException | Only after a second classified rejection/format failure |
| File access/attribute/read failure or non-regular path | Original operational exception | Never |

Native helper exit 3 is reserved for CryptUnprotectData rejection with ERROR_INVALID_DATA, ERROR_INVALID_PARAMETER, NTE_BAD_DATA, NTE_BAD_KEY_STATE or NTE_BAD_KEYSET. Encryption failures, COM startup, IO, resource failures and other DPAPI errors keep generic failure status. JVM interpretation additionally requires completed input transmission and an empty completed output stream; exit 3 during protect is operational failure. An older helper returning generic exit 1 fails closed, without offering quarantine; distribute/rebuild the jar and helper together.

This allowlist deliberately favors retaining history over guessing the cause of an unknown error. A classified rejection is **not proof of irreversible corruption**: missing/mismatched keys can produce rejection too. Repair helper/environment/key access first when possible. Microsoft's [CryptUnprotectData documentation](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptunprotectdata) notes that corruption can produce different errors or even invalid successful output; the parser therefore still validates the decrypted schema. Unknown errors are not converted to data damage.

Before returning a quarantine preview, read again once after 100 ms under directory ownership. If readable on retry, refuse quarantine. If an operational failure occurs on either read, propagate without a preview. Only two data/key/format failures permit a preview. This is a bounded double-check, not proof that an error is permanent and not an automatic retry of mutation. Existing explicit one-use confirmation, encrypted-byte/attribute recheck, warning-before-mutation and pending retention are unchanged.

Regression tests use real DPAPI for healthy data, malformed encrypted/schema data and preservation checks. Dedicated Java child processes simulate helper failure, timeout, empty/oversized replies, incomplete input, rejection followed by success and rejection followed by operational failure. No device or real dataset is accessed. Verification results are recorded below after CI.

## S2: persistent warning remains a declared prototype constraint

The warning is directory-wide and has no expiration/reset API. After any confirmed history loss, **every missing key**, including a genuinely new dataset, needs explicit rebaseline; retained/restored keys still use normal floor checks. The marker remains even after all known keys are baselined. Do not manually delete it to bypass the gate.

Normal acceptance and explicit rebaseline currently differ in local admission policy only. Neither has a stronger wire protocol or intrinsic freshness proof: both take the same mock Grant and require a future authenticated caller. The explicit method name is not proof of a new request. Once request-bound authenticated grants exist, decide whether these paths can share protocol validation, which identities a warning covers, how unknown lost identities are represented, and when/with whose confirmation a warning can clear. PR #1 does not invent an expiration or implement another reset mechanism.

## S3: attribute-only comparison remains a declared limit

For an oversized file that cannot be loaded within the bound, quarantine rechecks fileKey (when available), size and modification time. fileKey may be null; equal size/time then cannot distinguish replacement. R2 excludes cooperating writers only. Same-user external modifications with matching attributes are outside the protection; no content-equality claim is made for this path. Raw filesystem access failures are now operational and no longer enter this fallback.

## Next PR priority

Implement synthetic request-bound grants (nonce, epoch, validity duration and conservative local deadline) and rejection tests for late replies, other requests and stale epochs before further PC storage features or confirmation UI. Phone epoch fencing, trust/pairing, actual lock/status/apply and private integration remain separate acceptance requirements. This document schedules no real-data use or main merge.
