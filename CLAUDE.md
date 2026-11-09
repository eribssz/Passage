# CLAUDE.md

Android client for FortiGate SSL-VPN. What it does and how it builds: `README.md`.

## Rules
- Work on `main`. Every push to `main` builds a signed APK in GitHub Actions and publishes a GitHub Release. For a change that doesn't touch the app (docs only), put `[skip ci]` in the commit message so no release is made.
- The APK file name always carries the version, in the CI artifact and the Release (`Passage-<version>.apk`). Keep it that way in anything new that produces a build.
- The signing key is never in the repo. CI reads it from the repository secrets `PASSAGE_KEYSTORE_B64` and `PASSAGE_KS_PASS`. Linkscope (`eribssz/Linkscope`) is signed with the same key.
- This repo is public. Never commit a personal email address, gateway addresses, usernames or passwords. Commit with a noreply identity.
- Hand builds to the owner as an attachment in the conversation or as the Release link. Don't save them to his PC unless he asks.
- Code, comments, strings and file names are in English.
