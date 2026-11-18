# WIDGORA — Roadmap

**Current state: the first integration retrieves real data and the application displays it.** An
Uptime Kuma instance can be set up on the television — address and API key, the key encrypted by
the device keystore — its metrics are retrieved over HTTPS, and the home screen shows the
resulting summary together with the time it was obtained, in a design system whose brand colours
were measured from the project's own logo and whose contrast is checked by tests. Anything below
that is not described as implemented is not implemented: there is no widget per monitor, no
detail view, no background refresh, no bridge to a host launcher, and no release has been made.

This roadmap lists intentions, not promises. Items carry the evidence markers used throughout
the project documentation:

| Marker | Meaning |
|---|---|
| `[V]` | **Verified** — confirmed in source code or official documentation |
| `[U]` | **Unverified** — technically reasoned, not yet tested in practice |
| `[D]` | **Dependent** — requires cooperation from a third-party project |
| `[O]` | **Open** — product decision not yet made |

No feature is advertised as available before it works against real data on real hardware.

---

## Principles that shape the order of work

1. **Nothing is built that becomes worthless if a third party declines.** Work that depends on
   another project's cooperation runs in parallel, never on the critical path.
2. **The standalone application is the product.** Host-launcher integration is an additional
   surface.
3. **Real data from the first milestone onward.** A widget that displays fixed sample values is
   not a completed widget.
4. **Breadth comes after depth.** One integration carried through properly teaches more than
   five half-finished ones.

---

## Phase 0 — Survey · complete

- Working directory and toolchain inventory.
- Analysis of the intended host launcher: language, UI technology, SDK levels, licence,
  extension points, security surface `[V]`.
- Reality check of the first integration's available interfaces `[V]`.
- Assessment of further planned integrations and their licensing situation.

## Phase 1 — Architecture and documentation · complete

- Module structure, dependency rules and promotion triggers.
- Domain model: instance lifecycle and widget display states as separate concerns.
- Transport abstraction.
- Bridge contract, draft version 1, with data minimisation.
- Security concept.
- This documentation set: `ARCHITECTURE.md`, `ROADMAP.md`, `SECURITY.md`.

## Phase 2 — Project setup · complete

These are four separate steps and are tracked separately, because conflating them would
misrepresent the state of the project.

| Step | State |
|---|---|
| Private repository created | **done** |
| Local version control initialised | **done** |
| Gradle and Android project structure, four modules, version catalog | **done** |
| First successful build | **done** |

The last row means one specific thing and nothing more: assembling the debug application,
running the unit tests and running lint all completed locally. The application displays a
placeholder and does nothing else.

Also part of this phase:

- Android SDK and a pinned Temurin 21 LTS toolchain. The Gradle wrapper pins the distribution
  by checksum.
- Continuous integration: assemble, unit tests, lint. Kept deliberately lean. The workflow has
  run and passed, both on the pull request and on the default branch after the merge `[V]`.
- Work proceeds through feature branches and pull requests.
- No feature work begins before the build is green.
- The application has been installed and started on an emulator — Google TV, API 36, x86_64 —
  where it displays its placeholder screen `[V]`. Behaviour on real television hardware remains
  unverified `[U]`, as does focus behaviour driven by an actual remote control.

---

## M0 — Standalone application with the first integration

The first milestone that produces something usable. It depends on nobody outside the project.

- Design system with all six widget display states.
- First integration: **Uptime Kuma**, read via its Prometheus metrics endpoint with an API key.
  That project exposes no general management REST API, and its Socket.IO interface is documented
  as unsupported for third-party use and requires a username and password, so it is not used
  `[V]`.
- Displayed: overall status, number of monitors up, number down, individual monitor states, time
  of last update.
- One horizontal widget row plus a detail view, fully operable with a directional pad.
- Multiple instances supported in the data model from the start.
- Credentials entered on the television and stored encrypted.
- Configuration, enabling and visibility as separate, explicit states.

**Completion criterion:** runs on real hardware against a real installation, with real data, with
no third-party cooperation involved.

**Verified** `[V]`: the metric names and labels of the metrics endpoint, including the sliding
window label, the varying tag labels, missing values written as text and the numeric meaning of
its status gauge, settled against a captured response of a running instance and against the
upstream source rather than assumed.

## M1 — Provider Bridge

- Read-only `ContentProvider` exposing prepared display values only.
- Consent model: pre-authorisation in the foreground, deferred consent as fallback. No background
  activity starts `[U]`.
- Caller verification with documented limits.
- Detail rows disabled by default, enabled per instance.
- A small reference consumer application, written within this project, which proves the interface
  works without waiting for anyone.
- Versioned, documented contract.

## M2 — Host launcher integration `[D]`

Runs in parallel with M1 and M3. Never blocks them.

- Proposal to the maintainer of the intended host launcher: one additional home-screen section
  that queries the bridge, adding no dependency to that project and causing it to contact no
  additional network host.
- On interest: a narrow upstream contribution.
- If declined: the standalone application and the Watch Next surface carry the product. No work
  from M0 or M1 is lost.

## M3 — Further integrations

In this order, each carried through with real data and all six display states:

1. **Jellyfin** — resume playback, recently added, library information `[U]`
2. **Seerr** — requests, request status, new recommendations `[U]`
3. **Weather** — current conditions and forecast. Attribution requirements and the
   non-commercial terms of free service tiers are respected `[V]`
4. **Home Assistant** — optional aggregation platform, not a prerequisite. Device states,
   sensors, temperatures `[U]`

## M4 — Dashboard expansion

- **Configurable grid dashboard.** A fixed grid with discrete widget sizes and a move mode
  entered by a button press. Free drag-and-drop placement is not planned, because it is poor
  interaction design with a directional pad `[U]`.
- Row and section configuration.
- Additional widget sizes and information densities.

## M5 — Actions

- Separate write channel with its own consent, enabled per integration.
- Integration-agnostic action contract, so that an action raised in one widget can target a
  different integration.
- Confirmation on the television for consequential actions.

## M6 — Further services

Added once the foundation is stable, roughly in order of usefulness per unit of effort:

- Sonarr, Radarr, Prowlarr
- Synology DSM
- Checkmk
- TMDb and IMDb discovery, including raising a request in a request manager from the widget
- Energy and photovoltaic systems. A vendor-published Modbus TCP protocol document exists and is
  structured into read-only running information and writable parameter registers `[V]`. Two
  practical constraints apply: the protocol document is not distributed from a public vendor
  download page, and enabling the local Modbus server may require installer-level access. Both
  must be verified against the actual installation and firmware before implementation `[U]`. The
  choice between a direct local integration and an intermediate platform is open `[O]`.

## M7 — Community integration SDK

Begins only when the internal integration architecture has proven stable across several
integrations.

- Public integration contract derived from the internal one.
- Declarative widget definitions: integrations supply data and declarations, never executable
  code inside the host application.
- Versioning, compatibility checks, error handling, documentation.

---

## Deferred, with reasons

| Item | Reason |
|---|---|
| Financial market data | No free data source found whose terms permit redistribution in client software. A licensing problem, not a technical one `[U]` |
| YouTube subscription feeds | No official API exposes a user's subscription timeline without an interactive authorisation flow. Per-channel feeds are the only pragmatic route `[V]` |
| Free drag-and-drop grid placement | Poor interaction design with a remote control. A discrete grid with a move mode is planned instead |
| Forking the host launcher | Permitted by its licence, but continuous rebasing against a fast-moving codebase is not sustainable. Documented as a fallback only |
| Overlay or accessibility-based display | Fragile, requires invasive permissions, breaks on host updates |

---

## Known constraints

- **No real-time updates.** Background refresh has a minimum interval of 15 minutes and can be
  delayed further by system power management `[V]`. Widgets show when their data was last updated
  and mark themselves stale rather than pretending to be current.
- **Credential entry on a television is slow.** The first milestone keeps it to one address and
  one key per instance. A companion configuration path is a later, deliberate design task, not an
  afterthought.
- **Remote-control behaviour must be verified on hardware.** Emulators do not reproduce focus
  behaviour faithfully.
- **Licence not yet decided** `[O]`.
