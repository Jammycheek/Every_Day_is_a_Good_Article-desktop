# Follow-up: linking without an open screen and fast phone handoff

Status: requirements and design checks, not implemented by PR #6. No lease constants or wire messages are changed here.

## Requested behavior

After initial pairing and folder permission, ordinary Connect/import should work without keeping the phone's PC-link
screen displayed. When moving back to the phone, avoid waiting minutes: aim for the few seconds required by the system.
Keep the phone authoritative. PC drafts remain protected, and switching devices must not silently submit a draft.

## Separate the paths

- Normal end-edit: first preserve the newest accepted PC input, stop PC editing, deliver an authenticated release,
  and confirm phone-side completion. The present release is best-effort and has no acknowledgement: sending it does
  not prove the phone processed it. A missing reply must remain an uncertain handoff, with protected PC input.
- Cable/file-transfer loss: evaluate phone-side transport notifications as a reason to revoke a session, never as
  evidence granting editing rights. Invalidation and phone unlock must use the same write gate as apply/renew/local
  writes. Specify how queued acquisition, renewal and submission messages from before the loss are rejected, even
  after reconnect; merely clearing the visible lock is insufficient.
- Missed notifications/process or communication failure: retain an independently expiring phone lease as a fallback.
  Determine its length, renewal cadence and PC allowance from measurements. Do not shorten the five-minute constant
  to a few seconds while leaving the fifteen-second allowance and synchronous MTP worker unchanged.

The measured 5.4-8.3 seconds is a whole import, not one heartbeat round trip. Measure each mailbox operation, queued
work, message visibility, phone processing and expiry observation before choosing a short lease. Decide whether the
current MTP mailbox is sufficient for the required latency, without silently introducing another transport.

## Required implementation/verification in both repositories

1. Choose a supported phone lifecycle for receiving messages without this screen. Verify foreground/background,
   screen lock, app/process restart and OS restrictions. Show a clear connection/editing status and a way to stop it.
2. Specify release acknowledgement and idempotency, delayed responses, and preservation of the PC's latest input.
3. Define connection/session generation handling under one phone write gate. Race delayed acquisition, renewal and
   apply against disconnect, expiry, local edits and reconnect. No revoked session may resume or overwrite phone edits.
4. Measure normal release and abrupt-loss handoff separately, from the user's action to the phone actually accepting
   local writes. Agree a numeric few-second target and fallback behavior; report maxima as well as typical latency.
5. Exercise slow I/O, lost release/ack, charging-only, repeated USB flaps, busy database, sleep and background suspension.
   Keep an explicit slow/uncertain state; never claim a successful transfer solely because a timeout elapsed.

Android-specific implementation belongs only in the private repository. Public changes must remain platform-neutral
contracts, synthetic tests and desktop adapters. This follow-up does not authorize merging either existing draft.
