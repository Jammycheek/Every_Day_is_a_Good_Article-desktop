# Hardware validation procedure: one-edit round trip (synthetic data only)

Nothing in this document has been executed on hardware yet. Record results only after running them; never mark a
step passed from automated tests. Use a separate validation build of the phone app (separate application ID) and
synthetic data only. Do not use the E: drive; the desktop app stores its data under `%LOCALAPPDATA%\EveryArticleDesktop`.

## Before connecting

1. PC (Windows 10/11, JDK 17, Visual Studio Build Tools): build the helper
   (`cmake -S native/windows -B build/native -A x64` and `cmake --build build/native --config Release`), set
   `EVERYARTICLE_HELPER` to `build\native\Release\wpd-probe.exe`, then start the app with `gradle :desktop:run`.
2. Phone: install the validation build from the private repository's PC-link branch. Create 2 synthetic topics and
   3 synthetic articles (short text, one with a title). No real data, no photos needed.
3. Phone: in internal storage, create a folder `EveryArticleSync` at the top level (Files app or the folder picker).
4. Phone: Settings → Link with PC → Choose folder → select `EveryArticleSync`. Keep this screen open during tests.
5. Connect USB, unlock the phone, choose **File transfer**. Do not change accounts, passwords or power.

## Steps and expected results

| # | Action | Expected |
| --- | --- | --- |
| 1 | Phone: Start pairing. PC: Start pairing | Both show the same 6 digits |
| 2 | Confirm on both | Both list the other as paired; no key/ID printed in logs |
| 3 | PC: Connect and import | PC lists the 3 synthetic articles; phone shows "PC is editing", other screens show the lock overlay |
| 4 | Phone (other screen): try to edit/save an article | Blocked by overlay; if reached, save is refused and the draft is kept |
| 5 | PC: edit one article body, wait 2 s, submit | PC shows "applied (revision N)"; phone article shows the new text; lock released |
| 6 | Repeat 3, edit, then unplug USB before submitting | PC: session ended, snapshot cleared, pending shown as protected; phone: lock ends at expiry without the PC |
| 7 | Reconnect, Connect | Pending draft restored in the editor (same base); submit applies once |
| 8 | Repeat 3–5 but switch to charging-only right after pressing Submit | PC shows "no reply" and does not resend; after restoring file transfer, Connect queries: APPLIED → cleared, or NOT_APPLIED → user resubmits. The article changes exactly once |
| 9 | Pending draft on PC (unplugged), edit the same article on the phone after the lock expires, reconnect | PC shows CONFLICT; phone text is not overwritten; discard needs a confirmation dialog |
| 10 | Force-stop the phone app during a session, reopen, PC submits | Not applied (session from an old epoch); reconnect → NOT_APPLIED → user resubmits → applied once |
| 11 | Restore a synthetic backup on the phone while the PC holds a pending edit | PC shows "phone data was replaced" (REJECTED); nothing applied |
| 12 | Connect, type one character and **immediately** press "end editing" (within 0.5 s) | Reconnect: the editor shows the text including that last character |
| 13 | Connect, type, unplug USB within 0.5 s of the last keystroke; wait for "connection lost"; plug in and Connect | The last keystroke is present (saved, or restored from "unsaved input" at the same base). If the phone changed meanwhile: "unsaved input" notice with save-as-text, nothing applied |
| 14 | With "unsaved input" shown, close the window | Dialog: save as text / discard and close / stay open. Nothing closes silently |
| 15 | Quit the app; back up `%LOCALAPPDATA%\EveryArticleDesktop\pairing\pairing.dpapi`, replace it with any small synthetic file; start | "Cannot read pairing data" screen, no crash; move-aside keeps `pairing-unreadable-*.dpapi`; pending edit still listed; re-pair works |
| 16 | Export a quarantined pending archive to a FAT32/exFAT USB stick (synthetic) | Refused as unprotected destination unless explicitly allowed; to NTFS: allowed with restricted ACL |

Record for each step: date, app commits, Windows/phone versions, observed messages, and whether the phone-side
article and operation count match. Also record the time from PC write to phone reply (MTP visibility latency).
