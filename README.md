# Kharcha

An offline expense tracker for Pakistani bank and wallet SMS. Reads your
existing inbox, keeps reading new messages, groups spending by month, and learns
merchant categories from a single tap.

Nothing leaves the device. There is no `INTERNET` permission in the manifest,
and adding one would be a regression.

## Getting an APK

Push this folder to a GitHub repo. The workflow builds a debug APK on GitHub's
runners and attaches it to the run.

```
git init && git add . && git commit -m "Kharcha"
git remote add origin git@github.com:YOU/kharcha.git
git push -u origin main
```

Then **Actions > Build APK > latest run > Artifacts > kharcha-debug**. Unzip,
transfer to your phone, install.

Play Protect will block it. That's the right call on its part — a sideloaded app
requesting `READ_SMS` is the signature of a banking trojan. Tap **More details >
Install anyway**, or turn the scanner off in Play Store > Play Protect > settings
while you install.

On Android 13 and up, sideloaded apps have notification-listener access greyed
out under a restricted-setting block. To enable SadaPay and JazzCash capture:
Settings > Apps > Kharcha > **⋮ > Allow restricted settings**, then grant the
listener under Notifications > Device & app notifications.

## First run

1. Grant SMS permission. The import runs automatically over your whole inbox.
2. Open **Settings** (the gear beside the month arrows) and scroll to the
   bottom. The import report tells you what happened.

Read the three counters:

- `known` is 0 → no sender rule matched. Find the real sender ID in your
  Messages app and add it under **Senders**. It is often a short code like
  `8558`, not a name.
- `known` is healthy but `imported` is low → the wording rules don't fit. Read
  the "did not parse" samples below the counters.

Then work the loop: copy a failing message into the **Test a message** box,
read the verdict, add a keyword under **Debit and credit rules**, test again.
Rescan when the test box goes green.

Adding a bank is the same three steps: a sender pattern, whatever debit wording
it uses, done. No rebuild.

## Decisions worth knowing about

**Money is `Long` paisa, never `Double`.** Floating point silently loses rupees
over thousands of transactions.

**Deduplication by fingerprint.** A card swipe often fires both an SMS and an app
notification. `account + direction + amount + minute` hashes to one key and the
unique index makes the second insert a no-op. This is also why re-importing is
harmless and runs on every launch.

**Transfers are detected, not counted.** A debit paired against a credit of equal
value in a different account within fifteen minutes is flagged as internal.
Without it, moving money from HBL to SadaPay reads as spending. Widen the window
in `detectTransfers` if your transfers settle slowly.

**Categorising is a rule, not a label.** Tapping a transaction writes a
`merchant -> category` rule and applies it across all history at once.

**One parser path.** `SmsParser.explain()` makes every decision. The importer,
the test box and the transaction sheet all call it, so what the test box shows
is exactly what the importer did — there is no second implementation to drift.

**Tap any transaction** to see the original message, the sender, and which rule
fired. That's how you catch a mis-parse, like an amount grabbed from a balance
figure instead of the charge.

## Not built yet

- Export. There is none, and `allowBackup` is off. Add CSV export before you
  rely on this.
- Real Room migrations. The database uses `fallbackToDestructiveMigration`,
  which is fine while everything rebuilds from SMS in seconds and wrong the
  moment you keep anything you'd miss.
- Statement import, for accounts that alert inconsistently. Month totals
  under-report until every account parses reliably.
- Cash is a black hole. An ATM withdrawal is one transaction of unknown purpose.
  Treat the Cash category as a known blind spot rather than trying to fix it.
- Fonts fall back to system faces. For the intended Bricolage Grotesque and IBM
  Plex pairing, drop the .ttf files into `res/font/` and edit three lines in
  `ui/Theme.kt`.
