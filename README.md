<div align="center">

# AirLink

### Hear Windows audio through your Android phone over your LAN

![Source release](https://img.shields.io/badge/release-source-2563eb?style=flat-square)
![Local first](https://img.shields.io/badge/design-local--first-0f766e?style=flat-square)

**Coded by [zaid.dev](https://github.com/gamerock587)** · **invnty**

C# · Java · .NET · Android

</div>

---

## What it does

- Windows audio capture and Android playback
- LAN discovery and pairing
- Encrypted audio packets and reconnect handling

## Quick start

**Requirements:** Windows 10/11, .NET 8+ SDK, JDK 17/21 and Android SDK 35.

```powershell
powershell -File scripts/Build.ps1
```

## Before you run

Source release only: build the Windows server and Android APK locally. Use a trusted LAN. The short pairing code is not designed to resist exhaustive offline guessing. Hardware latency and Android power management vary. See USAGE.md and docs/PROTOCOL.md.

## Documentation

See [USAGE.md](USAGE.md) for the original detailed operating guide.

## Privacy and configuration

This source export excludes local credentials, databases, sessions, logs, recordings,
personal spreadsheets and compiled distributions. Supply your own configuration.
The ignore rules help prevent accidental commits; they do not replace reviewing files.

## Contributing

Describe the problem, include reproducible steps with synthetic data, and keep changes focused.
Never include tokens, private recordings, customer data or personal contact lists in an issue.
Validation and remaining limitations are recorded in [RELEASE_REVIEW.md](RELEASE_REVIEW.md).

## License and attribution

Original application code is licensed under [MIT](LICENSE). Third-party components retain
their own licenses and authorship; see THIRD-PARTY-NOTICES.md and any bundled license files.
