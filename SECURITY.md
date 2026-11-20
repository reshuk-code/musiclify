# Security Policy

## Supported versions

Only the latest release of each app (desktop and Android) gets security fixes.

## Reporting a vulnerability

**Please don't open a public issue for security problems.**

Report them privately through GitHub: go to the repository's **Security** tab and choose **Report a vulnerability**, or use [this link](https://github.com/reshuk-code/musiclify/security/advisories/new).

Please include:
- what is affected (app, version, platform)
- steps to reproduce or a proof of concept
- the impact you expect

You can expect an acknowledgement within a few days. We'll keep you updated while we work on a fix and credit you in the release notes unless you'd rather stay anonymous.

## Areas of particular interest

- The device-linking and sync protocol (QR pairing key, AES-256-GCM transport)
- The encrypted offline library (key storage, AES-CTR file encryption)
- The match cache service (`services/matchcache`): input validation and abuse
- Handling of imported `cookies.txt` files
