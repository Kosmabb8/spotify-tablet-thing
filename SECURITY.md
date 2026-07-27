# Security policy

## Reporting a vulnerability

Please do not include passwords, access tokens, Spotify credentials, device serial numbers, Wi-Fi details, private logs, signing material, or personal data in a public issue.

Until a private security-contact channel is configured for the repository, use GitHub's **Report a vulnerability** private advisory flow on the repository Security tab. If that feature is unavailable, open a minimal public issue asking the maintainer to enable private reporting without disclosing technical details.

## Supported version

Security fixes target the latest release. This is a personal, non-commercial controller and is provided without warranty.

## Secrets and local data

- The app uses Authorization Code with PKCE and never needs a Spotify Client Secret.
- OAuth tokens are AES-GCM encrypted with an Android Keystore key.
- Android backup/device transfer is disabled for application data.
- `.env`, signing keys, keystores, local SDK configuration, APKs, logs, backups and provisioning state are ignored by Git.
- Release signing material belongs in GitHub encrypted Actions secrets or an offline local keystore; it must never be committed.
- Lyrics are disabled by default. Enabling them sends the current track, artist, album and duration to `lrclib.net` over HTTPS. Spotify tokens, Client ID, device identifiers and account names are not included.
