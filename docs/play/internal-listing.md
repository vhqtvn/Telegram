# Google Play internal testing — listing prep (VHGram / `vn.vhn.telegram`)

Paste-ready values + operator checklist for the Play Console **internal testing**
track ONLY. This is not a publish checklist: internal testing has no review, no
public store listing, and no distribution beyond testers you explicitly add.
Everything that would be needed only for a real store listing is listed as
NOT-required at the bottom.

Companion wiring: `.github/workflows/release.yml` uploads the signed AAB to the
internal track when the `upload_to_play` dispatch input is set, or automatically
when the run was triggered by a `v*` tag. The upload requires the
`PLAY_SERVICE_ACCOUNT_JSON` repo secret (checklist step 3).

## Listing fields (paste-ready)

| Console field | Value |
|---|---|
| App name | `VHGram` |
| Package name | `vn.vhn.telegram` — fixed by `APP_PACKAGE` in `gradle.properties`; must match exactly when creating the app. Note: the package name is permanently claimed by the Play account that first uploads it (moving an app to a different developer account is a separate, gated transfer process — the name itself is never reusable by another app). |
| Short description | `Unofficial Telegram client for personal use. Uses the Telegram API.` (67/80 chars; names both the unofficial status and the Telegram API usage — the transparency Telegram's API terms expect from fork builders) |
| Full description | see block below |
| Contact email | `OPERATOR_EMAIL@example.com` ← **operator: replace with a real address before use** |
| Category | `Communication` — only if the console demands one; the internal track usually does not |

### Full description (paste-ready, 4 lines)

```text
VHGram is a personal, unofficial fork of the open-source Telegram for Android
client, built for the maintainer's own devices and a small circle of internal
testers. It uses the Telegram API (with its own registered api_id) and is not
affiliated with or endorsed by Telegram. It is not distributed publicly; this
listing exists only so internal testers can install builds conveniently.
```

## Operator checklist (console + repo steps, in order)

1. **Create the app** — Play Console → All apps → Create app: name `VHGram`,
   app (not game), default language. The package `vn.vhn.telegram` binds to
   your Play account at the first AAB upload (transfer to another account is a
   separate gated support process) — only do this when the internal track is
   actually wanted.
2. **Play App Signing enrollment** — prompted at the first AAB upload; AAB
   uploads require it. Choose **"Export and upload a key from Java keystore"**
   and run the console-provided `pepk` command against the operator keystore
   (`.local/config/android-keys/release.keystore`, alias `telegram-fold`).
   Exporting the EXISTING key makes the Play-delivered APK's signing key (and
   its SHA-1) identical to locally-built APKs, so the `vh-telegram` Firebase
   project's registered SHA-1 keeps working (FCM push etc. keep authenticating).
   Letting Google *generate* a new key instead changes the delivered SHA-1 and
   breaks Firebase auth until the new SHA-1 is added to the Firebase console.
   Sanity check after enrollment: Play Console → Release → Setup → App signing
   should show the same SHA-1 as `keytool -list -v` on the telegram-fold key.
3. **Service account + secret** — Play Console → Users and permissions →
   invite a service account (linked via Google Cloud IAM per the console's
   wizard) and grant it at minimum **Release apps** (release access to this
   app). Download its JSON key to the repo root as `play-service-account.json`
   (that exact path is gitignored — the file must never be committed), then
   from the repo root (targets the `origin` GitHub repo):

   ```
   gh secret set PLAY_SERVICE_ACCOUNT_JSON < play-service-account.json
   ```

   The JSON's contents go ONLY into that GitHub secret — nowhere else.
4. **Internal testing track** — Testing → Internal testing → create/reuse the
   track → add testers (individual emails or a Google Group) → copy the
   **opt-in link** the console shows and share it with the testers.
5. **First upload** — Actions → Release build → Run workflow → tick
   *"Also upload the AAB to the Play internal testing track"*. (Or push a
   `v*` tag — tag runs upload automatically.) Play rejects re-uploads of an
   already-used `versionCode`: the afat build produces
   `APP_VERSION_CODE * 10 + 9` (afat `abiVersionCode` is 9, so 7089 → 70899),
   so each new upload needs a bumped `APP_VERSION_CODE` (the upstream sync
   bump does this naturally). Do this manual dispatch BEFORE cutting any `v*`
   tag — tags auto-upload, and the wiring's first real run should be the
   controlled one.
6. **Tester install** — opening the opt-in link on Android offers VHGram
   (`vn.vhn.telegram`) from Play. It installs **alongside any official
   Telegram** (different `applicationId`): separate app, separate data, both
   can stay logged in independently.

## NOT required for the internal testing track

Needed only if this ever graduates to a real store listing — none of these
block internal-tester installs:

- Data safety form
- Content rating questionnaire
- Screenshots / feature graphics
- Privacy policy URL
- Target audience / ads declarations, app access instructions

## Known-open cautions

- The launcher icon is still upstream Telegram's. The registry rule
  (`docs/planning/local-changes.md` → fork product identity) is that it MUST be
  replaced before ANY store distribution — and enabling even the internal
  testing track is distribution. If testers are enrolled before the icon is
  replaced, that is an explicit operator decision to accept the exposure for a
  small private circle; widen nothing beyond it until the icon is replaced
  (Telegram terms forbid official-logo reuse by forks).
- The CI Play upload step has never run against the real Play API (Actions
  cannot execute locally). The first dispatch with `upload_to_play` enabled —
  after the secret exists and these console steps are done — is the real test;
  keep it a manual dispatch, before any `v*` tag is cut.
