# WIDGORA — Security

**Project status: planning. No source code exists yet, and therefore no released artefact has a
security posture that can be audited.** This document records the security design before
implementation, so that the reasoning can be reviewed and later checked against the code.

Markers used below:

| Marker | Meaning |
|---|---|
| `[V]` | **Verified** — confirmed in source code or official documentation |
| `[U]` | **Unverified** — technically reasoned, not yet tested in practice |
| `[O]` | **Open** — decision not yet made |

---

## 1. What WIDGORA protects

WIDGORA holds credentials for a user's private infrastructure: monitoring systems, media servers,
network-attached storage, home automation. The sensitive material is not only the credentials
themselves but also the **topology** they imply — hostnames, ports, internal addresses and
service names, which routinely reveal how a private network is laid out.

Three assets, in order of severity if lost:

1. **Credentials** — API keys and tokens for the user's services.
2. **Topology** — addresses, hostnames and service names.
3. **Status data** — which services are up, what is playing, sensor readings.

---

## 2. Threat model

| Threat | Relevance |
|---|---|
| Another application on the television reads WIDGORA's data | The device may run sideloaded software of unknown provenance |
| An application impersonates an approved consumer to read data | See section 4 on the limits of signature verification |
| Credentials readable from device storage or a backup | A television is often shared and rarely treated as a personal device |
| A compromised or hostile service returns malicious responses | Every integration talks to servers the project does not control |
| Credentials leaked through logs, crash reports or the repository | The most common real-world cause of credential exposure |
| A manipulated build of a trusted application is installed | See section 4 |
| Network interception inside a home network | Self-hosted services frequently use self-signed certificates |

Out of scope: physical attacks on the device, a compromised Android system image, and attacks
against the user's services themselves.

---

## 3. Credential handling

- Credentials are **never** placed in source code, resources, build files, logs or commits.
- Credentials are encrypted with an AES-GCM key held in the Android Keystore. The key is
  generated on the device and its material is non-exportable `[V]`. It is not carried off the
  device by backup either, but that follows from the key being bound to the device keystore and
  from this application declaring no backup, not from a documented keystore flag: the platform
  documentation describes no such flag `[V]`.
- The key is AES-256 in GCM mode, and every encryption uses a fresh random initialisation
  vector. That is enforced by the platform rather than by convention: the key is generated with
  randomised encryption required, so an operation that would reuse a vector is refused `[V]`,
  shown on an emulator by instrumented tests that encrypt the same text twice and compare both
  the vectors and the ciphertexts. Behaviour on real television hardware remains unverified
  `[U]`.
- Each integration instance has its own credential entry under its own keystore alias. Removing
  an instance removes its stored ciphertext **and** destroys its key material, so what was
  encrypted for it is unreadable afterwards `[V]`, shown by the same instrumented run, which
  also confirms that a tampered ciphertext or vector is refused and that removing one instance
  leaves another readable. Real hardware remains unverified `[U]`.
- A credential that can no longer be decrypted — after the operating system discarded keystore
  material, for instance — is reported as **unreadable**, which is a state of its own and not
  the same as absent. The screen then says so and asks for the credential again, rather than
  looking as though nothing had ever been configured. It is never an empty credential and never
  a crash.
- A credential is read from the store only at the moment a retrieval needs it, and it is never
  held in a field, in a dashboard state or in anything the screen renders. An instance whose
  credential cannot be read is not retrieved at all; it reports that the credential has to be
  entered again.
- A stored credential is only ever used against the **exact address it was entered for**. Once
  that address is edited, the credential field becomes mandatory again, because sending a saved
  credential to a changed address would hand it to a server the user never typed it for.
- A stored credential is **never read back into a screen**. The settings screen's state has no
  field that could hold it, not even a masked one, so it cannot be rendered, logged or caught in
  a screenshot. The screen knows only whether a credential exists, and an empty input field
  means "keep the stored one". Entry itself is masked.
- The stored configuration of an instance carries no credential at all: its type has no field
  for one, and an address containing credentials, such as `https://user:pass@host`, is refused
  when the instance is saved. A credential belongs in the keystore, not in an address.
- A logging wrapper masks known credential fields. No integration logs raw request or response
  bodies at any level that ships in a release build.
- `androidx.security:security-crypto` is **deprecated** and is not used `[V]`. The platform
  documentation states that all of its APIs were deprecated in the stable 1.1.0 release, that no
  further releases will follow, and that the replacement is direct use of the Android Keystore,
  which is what this project does.
- WIDGORA requests only `INTERNET` and `ACCESS_NETWORK_STATE` for the first milestone. No
  accessibility service, no overlay permission, no system settings permission.

### Transport security

- Certificate validation is **never** globally disabled.
- Self-hosted services commonly present self-signed certificates. The planned accommodation is
  **per-instance certificate pinning**: the user is shown the certificate fingerprint once and
  confirms it explicitly. A pinned certificate applies to that instance only. A changed
  fingerprint causes the fetch to fail and asks the user again, rather than silently accepting it
  `[U]`.
- **Plain HTTP is refused.** The transport rejects any address that is not `https`, before a
  connection is attempted, and the application ships no network security configuration that would
  permit cleartext. Android itself disables cleartext by default for applications targeting API
  level 28 and above `[V]`, so platform and application agree rather than one relying on the
  other.
- Permitting plain HTTP for a single address the user entered deliberately, marked as unencrypted
  in the interface, remains a possible later step. It is not implemented, and it is not free: the
  platform cannot grant cleartext for a host that is unknown when the application is built, so
  such a step would have to permit cleartext application-wide in the network security
  configuration and carry the restriction in application code instead. That trade is to be
  decided deliberately, not inherited.

---

## 4. Why the bridge does not rely on code signatures

This section exists because the obvious mechanism does not work here, and the reason is not
intuitive.

### Signature-level permissions are the wrong tool

Android's `protectionLevel="signature"` grants a permission only to applications signed with
**the same certificate as the application that declares it**. If WIDGORA declared such a
permission, only WIDGORA's own builds would qualify. Any separate host application is signed by a
different party and would be permanently excluded. The mechanism is structurally unsuitable for
granting access to an independently developed consumer, regardless of any other consideration.

### Verifying the caller's certificate is the right tool, with a limit

The bridge instead resolves the caller through `Binder.getCallingUid()`, determines the calling
package, and verifies its signing certificate. A certificate is public information by design;
reading it proves nothing by itself. What the check proves is that the caller was signed with the
private key belonging to that certificate — **provided that private key is secret**.

That proviso does not hold universally.

**What is verified** `[V]`. The intended first host launcher ships a keystore file in its own
public repository, and the matching keystore password and key alias appear in plain text both in
a source file of that repository and in its README. Its build script passes those values to the
Android build as injected signing properties, and its documentation states that this is
intentional, so that every build installs over the previous one.

**What is not verified** `[U]`. Three things were deliberately not established, and are not
claimed:

- that the published keystore file opens with that password and contains a usable private key —
  the binary was not opened and nothing was signed with it;
- that the artefacts distributed through that project's releases are in fact signed with that
  key rather than another one;
- whether the key material was ever different in that project's history.

**Why the conclusion holds either way.** If the published material is usable, then anyone can
produce an application carrying that package name and that signature, and certificate
verification of that host proves nothing against a deliberate attacker. If it is not usable, the
verification is sound for that host but still rests on an assumption this project cannot audit
and cannot monitor over time. Both readings lead to the same engineering decision, which is why
the design does not depend on the answer:

> Certificate verification is treated as an **integrity check against accidental substitution**,
> never as a **boundary against a deliberate attacker**.

Confidentiality therefore rests on layer 1 (exposing very little) and layer 2 (explicit user
consent), described below. Signature verification is a supporting check, not a load-bearing one.

A further implication worth stating: where a launcher's signing material is published, the mere
presence of that launcher on a device is not by itself a trustworthy statement about what that
software is.

---

## 5. Bridge protection, in four layers

The layers are listed in order of how much weight they carry. The strongest does not involve
identity at all.

### Layer 1 — Minimise what is behind the door

The bridge exposes **prepared display values only**. There is structurally no field for a URL, a
token, a hostname or a raw API response. Server-provided error text is never forwarded, because
it routinely contains endpoints and stack traces; only a generic state is exposed.

The worst outcome for an attacker who successfully impersonates an approved consumer is learning
a short label, a state and a number. No credentials, no addresses, no endpoints.

Detail rows — individual monitor or device names — are **disabled by default** and must be
enabled per instance by the user, with an explicit warning, because such names frequently contain
internal hostnames. Row counts and string lengths are capped.

This layer is effective regardless of any signature, permission or allowlist, which is why it is
the foundation rather than an addition.

#### Constraints on `launch_uri`

The one field that could otherwise become an escape hatch is the deep link a host uses to open a
widget. It is constrained on both sides:

- scheme `widgora` only, authority `widget` only
- path `/{widget_id}` only; no query string, no fragment
- no external URLs, no foreign schemes, no caller-supplied intent extras
- the only information carried is the `widget_id`
- no URI permission grants are attached
- the receiving component independently validates that the `widget_id` is one it knows and
  ignores everything else

**A custom scheme is not an identity claim.** Any application on the device may register an
intent filter for the `widgora` scheme. Resolving this URI without further qualification can
therefore open software other than WIDGORA, and a user would have no reliable way to notice the
substitution. A host must bind the intent explicitly to the package
`io.github.jellyforgedev.widgora` before starting it, and must do nothing if no component of
that package can handle it, rather than allowing a fallback resolution.

A host is expected to use the value as given. Because a host cannot be relied upon to do either
of these things, the receiving side validates as well. Neither side trusts the other with more
than an identifier.

### Layer 2 — Explicit user consent

An unknown caller receives an **empty cursor**, never an error and never data.

Consent is granted by the user, in WIDGORA, in the foreground, and is revocable at any time. The
consent record stores the package name and certificate digest that were approved.

An important platform constraint shapes this: a bridge query arrives while WIDGORA is in the
background, and Android 10 and later restrict starting activities from the background. WIDGORA
therefore **must not** attempt to prompt at query time. Two mechanisms are planned:

1. **Pre-authorisation (primary).** The user enables host display inside WIDGORA before it takes
   effect `[U]`.
2. **Deferred consent (fallback).** An unknown caller is recorded as a pending request and
   presented for approval the next time the user opens WIDGORA. Until then the host displays
   nothing `[U]`.

A notification-based prompt is not planned: notification visibility on Android TV is inconsistent
and would require device verification before it could be relied upon `[U]`. Full-screen intents
and overlay windows are rejected as inappropriate for this purpose.

### Layer 3 — Caller identity

Package name and certificate digest are verified on every query. A changed digest revokes consent
and requires re-approval. The limits of this layer are described in section 4: strong against an
unrelated application, weak against deliberate impersonation of a host whose signing material is
published.

### Layer 4 — Actions are a separate gate

Write access — triggering an action such as switching a device — is a **separate channel** with
its own consent, enabled per integration, never granted implicitly together with read access. An
impersonating application must clear a second, independent gate that the user has deliberately
opened for that specific integration. Consequential actions require confirmation on the
television.

---

## 6. Treating external data as untrusted

Every response from every service is untrusted input, including from services the user owns:

- strict deserialisation; unknown fields are ignored, malformed payloads are rejected
- explicit limits on response size, element counts and string lengths
- no interpretation of HTML or markup from a response
- image references accepted only over HTTP or HTTPS, with a size limit
- no text from a response is passed into an intent, a URI or the bridge without validation
- numeric values range-checked before display
- failures classified into user-meaningful states rather than surfaced as raw messages

---

## 7. Plugin and extension security

A future community integration system must not execute third-party code inside the WIDGORA
process. The planned model is **declarative**: an integration supplies data and widget
declarations drawn from a fixed set of components and data models. It does not supply executable
rendering code.

The project keeps these concerns separate by design: integration logic, data retrieval, widget
presentation, user actions, credentials, and permissions.

---

## 8. Build and release security

- WIDGORA's signing key is **never** placed in the repository. It is held outside version control
  and supplied to continuous integration through secrets. This is a deliberate departure from the
  practice of the intended host launcher.
- Release builds are signed only once a keystore is managed this way.
- The repository ignores keystores, credential files and local build configuration by pattern, so
  that an accidental `git add` cannot stage them. Ignore patterns do not prevent a deliberate
  forced add, and are therefore a safeguard against mistakes rather than a control.
- Dependencies are pinned through a version catalog and kept deliberately few.
- No analytics, no crash reporting to a third party, and no telemetry are planned. If this ever
  changes it requires an explicit decision, documentation, and a user-facing opt-in `[O]`.
- WIDGORA contacts only the services the user has configured, plus any service an enabled
  integration documents. The set of contacted hosts is documented per integration.

---

## 9. Reporting a vulnerability

The project is in planning and has no released artefact. Once a repository is published, security
reports should be made privately through its advisory mechanism rather than in a public issue.
This section will be updated with a concrete contact path at that time `[O]`.

---

## 10. Open security questions

| Question | Marker |
|---|---|
| Whether deferred consent is workable in practice, given a host shows nothing until approval | `[U]` |
| Whether notifications are reliably visible on Android TV as a consent prompt | `[U]` |
| Deprecation status of the Jetpack security-crypto library before any use | `[U]` |
| Whether per-instance certificate pinning is comprehensible enough for non-technical users | `[U]` |
| Whether a pairing secret should be added if the bridge ever exposes more than display values | `[O]` |
| How a future companion configuration path authenticates, if one is introduced | `[O]` |
