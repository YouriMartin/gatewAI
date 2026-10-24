# Security policy

## Reporting a vulnerability

Report it privately through GitHub:
**[Report a vulnerability](https://github.com/YouriMartin/gatewAI/security/advisories/new)**
(the repository's *Security* tab → *Report a vulnerability*). Please do not open a
public issue, pull request or discussion for it.

Include what you can of: the affected version (the image tag, or `build.version` from
`GET /actuator/info`), the configuration involved (without secrets), the steps to
reproduce, and the impact you see. gatewAI is maintained by one person, so the
commitment is modest and stated as such: an acknowledgement within 7 days, an
assessment within 30, and a fix or a mitigation before any public disclosure, which
is coordinated with you. Credit is given in the advisory unless you prefer otherwise.

## Supported versions

Before 1.0.0, only the **latest released minor version** receives security fixes;
upgrade to it before reporting if you can. The `main` branch is a development line,
not a supported release.

| Version | Supported |
|---|---|
| latest `0.x` release | yes |
| older releases | no |
| unreleased `main` | no |

## Verifying what you run

Every release image is built by `.github/workflows/release.yml` from the tagged
commit, with an SBOM and a signed build-provenance attestation. Check that the image
you pulled is the one this repository built:

```bash
gh attestation verify oci://ghcr.io/yourimartin/gatewai:<version> --repo YouriMartin/gatewAI
```

What is in scope, what the gateway does and does not protect, and how dependencies
are scanned: [`docs/technical/security.md`](docs/technical/security.md).
