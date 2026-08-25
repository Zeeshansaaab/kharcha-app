# Kharcha

An offline expense tracker for Pakistani bank and wallet SMS. Reads your existing
inbox, keeps reading new messages, groups spending by month, and learns merchant
categories from a single tap. Nothing leaves the device — there is no network
permission in the manifest, and adding one would be a regression.

## Getting an APK without installing anything

Push this folder to a GitHub repo. The workflow in `.github/workflows/build.yml`
runs on every push to `main`, builds a debug APK on GitHub's runners, and attaches
it to the run.

```
git init && git add . && git commit -m "Kharcha"
git remote add origin git@github.com:YOU/kharcha.git
git push -u origin main
```

Then **Actions > Build APK > latest run > Artifacts > kharcha-debug**. Unzip,
transfer to your phone, allow install from unknown sources, done. The APK is
signed with the standard debug key — fine for your own device, useless for
distribution.

If the first run fails, the workflow uploads the Gradle reports as a second
artifact so you can read the compile errors without a local toolchain. Compile
errors on the first attempt would not be surprising; this project has never been
built.

Android Studio is still the better loop once you start tuning the parser, since
CI round-trips are minutes and a local build is seconds.

## After install

1. Grant SMS permission on first launch. The historical import runs
   automatically and takes a few seconds for a few thousand messages.
2. For SadaPay and JazzCash, also enable the listener under
   **Settings > Notifications > Device & app notifications > Kharcha**. Android
   gives no in-app way to grant this; send the user to
   `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.
3. Fonts fall back to the system faces so the project builds with no font
   configuration. For the intended pairing, drop Bricolage Grotesque and IBM Plex
   .ttf files into `res/font/` and edit the three lines in `ui/Theme.kt`.

Sideloading your own signed build is fine. Google Play restricts `READ_SMS` to
default-SMS-handler apps, so this could not be published as-is — only relevant if
you ever want it on the store.

## Tuning the parser

This is the part that decides whether the app is useful, and it is the part only
you can finish, because it depends on how your five institutions word their
alerts.

`parse/SmsParser.kt` holds three tables: `ACCOUNTS` maps sender IDs to account
names, `IGNORE` drops OTPs and marketing, and `DEBIT`/`CREDIT` extract the amount.
The shipped patterns are generic. Expect roughly half your messages to parse on
the first run.

Work the loop: add a screen that lists messages where `parse` returned null,
read twenty of them, widen a pattern, reinstall. Three rounds should get you past
95%. Keep the real message text in `Txn.body` — it is stored precisely so you can
re-parse history after improving a pattern without losing anything.

## Decisions worth knowing about

**Money is `Long` paisa, never `Double`.** Floating point silently loses rupees
over thousands of transactions.

**Deduplication by fingerprint.** A card swipe often fires both an SMS and an app
notification. `account + direction + amount + minute` hashes to one key, and the
unique index makes the second insert a no-op. This is also why re-running the
import is harmless.

**Transfers are detected, not counted.** `detectTransfers` pairs a debit against a
credit of equal value in a different account within fifteen minutes and flags
both. Without it, moving money from HBL to SadaPay reads as spending. Widen the
window if your transfers settle slowly.

**Categorising is a rule, not a label.** Tapping a transaction writes a
`merchant -> category` rule and applies it across all history at once. A hundred
rules will cover most of what you spend.

## Not built yet

- The unparsed-messages screen described above. Build this first; you need it.
- Statement import, for accounts that alert inconsistently. The month totals will
  under-report until every account is reliably parsed.
- Cash is a black hole. An ATM withdrawal is one transaction of unknown purpose.
  Treat the Cash category as a known blind spot rather than trying to fix it.
- Backup. There is no export yet, and `allowBackup` is off. Add a plain CSV
  export before you rely on this for anything.
