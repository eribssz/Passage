# Passage

A clean, modern Android client for **FortiGate SSL-VPN** (tunnel mode).

- Multiple gateways, each with several `IP:port` addresses: starts with the last one that
  worked, retries it on mobile blips, then fails over to the next
- Optional fixed address per gateway
- Certificate pinning: trust a gateway's certificate once, get warned if it ever changes
- Quick Settings tile for one-tap connect / disconnect
- Two-factor code prompt (FortiToken, e-mail, SMS)
- Export / import gateways as JSON (passwords are never exported)

## How it works

Passage implements the SSL-VPN protocol in Kotlin (`core/`): it signs in at
`/remote/logincheck`, reads the tunnel settings from `/remote/fortisslvpn_xml`, opens
`/remote/sslvpn-tunnel` and runs PPP (LCP/IPCP) over the TLS connection. The Android app
(`app/`) hands the resulting IP packets to a `VpnService` interface.

> FortiOS 7.6.3 removed SSL-VPN tunnel mode in favour of IPsec. Passage works with
> gateways that still offer SSL-VPN tunnel mode.

## Build

```
./gradlew :core:test :app:assembleRelease
```

`core` tests run the full sign-in → tunnel → PPP flow against a mock gateway.
Release signing reads `PASSAGE_KEYSTORE` / `PASSAGE_KS_PASS` (defaults to `keys/passage.jks`,
which is not in the repo).

Every push to `main` builds a signed APK in GitHub Actions and publishes it under
[Releases](../../releases/latest). The workflow needs two repository secrets:
`PASSAGE_KEYSTORE_B64` (the keystore, base64-encoded) and `PASSAGE_KS_PASS`.

## Disclaimer

Passage is an independent project. It is not affiliated with, endorsed by or supported by
Fortinet. Fortinet, FortiGate, FortiClient, FortiOS and FortiToken are trademarks of
Fortinet, Inc., used here only to describe compatibility. Use it only with gateways you are
authorised to connect to, and in line with your organisation's policies.

## License

MIT — see [LICENSE](LICENSE). Provided as is, without warranty of any kind.
