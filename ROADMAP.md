# WIDGORA — Roadmap

**Current state: the first integration retrieves real data, and both a summary and a detail view
display it.** An Uptime Kuma instance can be set up on the television — address and API key, the
key encrypted by the device keystore — its metrics are retrieved over HTTPS, and the home screen
shows one count per state as a mark and a number, with the names of whatever is not working
beneath and the time the data was obtained. The tile is focusable and opens a screen listing every
monitor with its state and, where one was measured, its response time. The design system's brand
colours were measured from the project's own logo, and its contrast and line heights are checked
by tests. Anything below that is not described as implemented is not implemented: there is no
widget per monitor, no background refresh, no bridge to a host launcher, and no release has been
made.

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
  of last update. Done `[V]`, with one addition that was not foreseen: the counts are shown as
  marks rather than words, because a summary long enough to name five states in a sentence
  overflows a tile and does so differently in each language.
- One horizontal widget row plus a detail view, fully operable with a directional pad. The row
  and the detail view exist `[V]`; whether a real remote drives them as intended is unverified
  `[U]`.
- Multiple instances supported in the data model from the start.
- Credentials entered on the television and stored encrypted.
- Configuration, enabling and visibility as separate, explicit states.

**Completion criterion:** runs on real hardware against a real installation, with real data, with
no third-party cooperation involved.

**Verified** `[V]`: the metric names and labels of the metrics endpoint, including the sliding
window label, the varying tag labels, missing values written as text and the numeric meaning of
its status gauge, settled against a captured response of a running instance and against the
upstream source rather than assumed.

## M1 — App widgets

The route to a host launcher, and the replacement for the Provider Bridge that was planned here
before. WIDGORA supplies a standard Android app widget, so the host side is a platform feature
rather than an agreement with one project.

- An `AppWidgetProvider` per type, handing the host a `RemoteViews` hierarchy, built directly
  rather than through Jetpack Glance. Done for the first type `[V]`.
- All six display states. A widget that has not been configured says so; it never shows sample
  values. Done `[V]`.
- Two layouts chosen by the box the host gives, with the figures scaled to fill it, and minimum
  sizes measured inside a real host view rather than estimated. Done for the first type `[V]`.
- A widget host included in debug builds only, so that a widget can be seen and operated without
  waiting for a launcher that hosts one. Done for one provider `[V]`; extended to all three, and
  to starting the configuration step, as part of this milestone.
- Opening a widget opens WIDGORA and nothing else. Done `[V]`.
- The device feature for app widgets is declared as not required, because the standalone
  application does not depend on it. Done `[V]`.
- **Three widget types**, each its own entry in a host's picker: the counts of an instance;
  certificates expiring within 30 days, soonest first; and a user-chosen set of items with the
  instance's counts above them.
- **A configuration step per placed widget**, one screen serving all three types: which instance
  (pre-selected while there is only one), whether monitor names may appear — off by default for
  the first two types — and, for the third, which items. Operated with a remote control. A widget
  never holds a credential of its own.
- **Monitor names reach a widget only by consent.** For the third type the selection itself is
  that consent, and the screen says so. The module boundary that used to make names unreachable
  does not apply here; what replaces it is written down in SECURITY.md, including that it is
  weaker.
- **Refresh through WorkManager every 30 minutes**, with the widget framework's own periodic
  update switched off. A widget counts as outdated after 45 minutes, and every foreground fetch
  pushes an update as well. One dependency is added for this and nothing else.
- Certificate data carried as far as the application's own item model, with the two properties of
  the source that shape the display: it reports nothing at all for an item without a certificate,
  and it never clears a figure once written.

**Completion criterion:** all three widget types showing real data inside a host, configured and
operated with a remote control.

## M2 — Display in a host launcher `[D]`

Runs in parallel with M1 and M3. Never blocks them.

- A signed test build for the maintainer of the intended host launcher, once a widget works.
- Hosting is then that project's own work: a launcher that hosts widgets displays WIDGORA's
  without WIDGORA contributing code to it, and without that project contacting any additional
  network host.
- If it does not happen: the standalone application carries the product, and any other launcher
  able to host widgets can display the same widget. No work from M0 or M1 is lost.

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
| Provider Bridge — a read-only `ContentProvider` exposing prepared display values to a host | Deferred, not abandoned, and nothing of it was built. It carries values rather than a view, which only helps a host that wants to draw WIDGORA's data with its own components; no host asks for that today. A standard app widget reaches the same goal through a platform mechanism and with much less to secure. **It returns if a host launcher wants to render WIDGORA's data itself.** The design and its security reasoning are kept in `ARCHITECTURE.md` and `SECURITY.md` for that case |
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
