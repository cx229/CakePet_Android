# CakePet shared debug signing

This directory contains a **development-only** signing key:

- Keystore: `cakepet-shared-debug.jks`
- Alias: `cakepet-debug`
- Password: the standard debug-only value `android`
- Certificate SHA-256: `07:A4:88:AC:C6:20:65:2E:DF:BF:62:33:CC:07:CB:EC:9C:35:D9:80:42:C9:68:06:3A:40:7D:55:31:88:28:29`

All `assembleDebug` builds on GitHub Actions and local checkouts use this same key.
Consequently, debug APKs with the same application ID and increasing versionCode
can replace one another without deleting app data.

**Security boundary:** This private key is intentionally checked into the development
repository, so it **must never be used for production releases**, signing an official
`com.cx.cakepet` APK, or authentication. Debug builds use the distinct package ID
`com.cx.cakepet.debug`; production builds retain `com.cx.cakepet` and must use a
separate private release signing key.

The first installation of the new debug package is independent of earlier
`com.cx.cakepet` test/official installs. The two apps have separate data.
Do not delete this key or regenerate it, or future debug APKs will lose upgrade
compatibility with existing debug installations.
