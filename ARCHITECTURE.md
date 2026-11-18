# WIDGORA — Architecture

**Project status: a project skeleton that builds, passes continuous integration and starts on an
emulator. Four Gradle modules, a placeholder screen and a lean continuous integration workflow
exist. None of the components described below is implemented, no integration retrieves data, and
no artefact has been released.** This document describes the intended architecture so that it can
be reviewed before implementation begins. Nothing described here is implemented unless this
document says so explicitly.

---

## Evidence markers

Every technical claim in this document carries one of these markers. They exist because an
architecture document that cannot distinguish verified facts from intentions is not useful.

| Marker | Meaning |
|---|---|
| `[V]` | **Verified** — confirmed in source code or official documentation |
| `[U]` | **Unverified** — technically reasoned, not yet tested in practice |
| `[D]` | **Dependent** — requires cooperation from a third-party project |
| `[O]` | **Open** — product decision not yet made |

---

## 1. What WIDGORA is

WIDGORA is a modular dashboard and integration platform for Android TV and Google TV. It
collects status and content from self-hosted services and home infrastructure and presents it on
a television in a form that is readable from across a room and operable with a remote control.

The product promise:

> **WIDGORA works on its own. On CleanMyTV Home it blends in.**

### Two display scenarios

- **Scenario A — standalone application (the foundation).** WIDGORA ships its own full-screen
  dashboard with its own design system. It depends on no third-party component.
- **Scenario B — native rendering inside a host launcher (the long-term goal).** A launcher
  reads prepared display values from WIDGORA and renders them with its own components.
  Variant **B2** applies: the host owns typography, focus behaviour and basic presentation,
  while WIDGORA keeps its brand identity through accent colours, icons and widget variants.

Scenario B is never a precondition for the product to function. In the standalone application
WIDGORA's design system is fully sovereign.

### Vendor neutrality

No vendor name and no vendor-specific logic belongs in `:model`, `core/data`, `core/security`
or `:bridge`. Everything service-specific lives in `core/integration/<name>/`.

---

## 2. Module structure

Four Gradle modules. A separate module is justified only by an **enforced dependency boundary**,
**testability without Android**, or **future publishing**. Everything else is a package.

```
widgora/
  gradle/libs.versions.toml      version catalog (no build-logic module at MVP scale)

  model/      :model     pure Kotlin/JVM, no Android dependency
                         domain types, state models, WidgetSnapshotSource interface

  core/       :core      Android library
                         data/              cache, scheduling, transport abstraction
                         transport/http/    OkHttp implementation
                         security/          credential store, Keystore wrapper
                         integration/api/   integration contract (seed of a future SDK)
                         integration/kuma/  first integration

  bridge/     :bridge    Android library - security boundary
                         ContentProvider, contract constants, caller verification

  app/        :app       Android application
                         ui/        dashboard rows, detail views
                         design/    design system, widget shells, focus tokens
                         settings/  instance management, consent management
```

### Dependency rules

```
:model    ->  nothing
:core     ->  :model
:bridge   ->  :model          (never :core, never any integration)
:app      ->  :model, :core, :bridge
```

Three rules that must hold:

1. **Nothing depends on `:app`.**
2. **`:bridge` cannot reach integrations.** The read interface `WidgetSnapshotSource` is declared
   in `:model`, implemented in `:core` — by the snapshot repository since the dashboard exists
   `[V]` — and wired by `:app`. A `ContentProvider` is instantiated
   by the system, so it resolves the implementation through a holder owned by the application
   class rather than by depending on `:core` `[U]`.
3. **Integrations never depend on each other and never on a concrete transport.** `:app` wires
   the transport, which keeps every integration testable against a fake transport.

### When packages become modules

Logical boundaries are promoted to Gradle modules only when a concrete trigger occurs. Splitting
earlier buys nothing and costs build and refactoring overhead.

| Package | Trigger for promotion |
|---|---|
| `core/transport/http/` | the first non-HTTP transport is added (Modbus TCP) |
| `core/integration/kuma/` | the third integration lands |
| `app/design/` | a second UI consumer appears |
| `app/ui/grid` | the grid dashboard is introduced |
| `core/integration/api/` | work on a community SDK begins |

A `build-logic` module with convention plugins is introduced when the module count grows.

---

## 3. Domain model

### Two separate state models

These are distinct concerns and are never merged:

**Integration instance lifecycle** (`core/data`)

```
configured  ->  enabled       the user enables the instance
enabled     ->  fetching      a retrieval starts
fetching    ->  enabled       the retrieval succeeded
fetching    ->  failing       the retrieval failed
failing     ->  fetching      a retry starts
enabled     ->  configured    the user disables the instance
fetching    ->  configured    disabled while a retrieval is running
failing     ->  configured    disabled while in failure
```

Every other transition is rejected, including a transition from a state to itself: repeating a
transition into the state a caller already holds hides a logic error instead of surfacing it.

**Widget display state** (`app/design`)

```
loading | ok | empty | error | offline | stale
```

A configured integration is not automatically visible. A visible widget can be `stale` while its
integration is healthy. Every widget renders all six display states.

### Multiple instances

`InstanceId` is part of the domain model from the first line of code. Two Jellyfin servers,
several Home Assistant installations or several Uptime Kuma instances must be configurable,
enablable and displayable entirely independently of one another. Configuration, credentials,
cache entries and refresh intervals are keyed per instance.

### Transport abstraction

`core/data` abstracts **transport**, not "HTTP client". This is deliberate:

- Uptime Kuma exposes Prometheus **text**, not JSON `[V]`
- Home Assistant uses a WebSocket API alongside REST `[U]`
- Sigenergy systems speak **Modbus TCP** `[V]`

An integration declares which transport it requires; concrete transports implement a common
interface. Committing to HTTP/JSON would force a rewrite as soon as the first non-HTTP
integration is added.

---

## 4. Data refresh

Background refresh uses WorkManager. Periodic work has a **minimum interval of 15 minutes** and
can be delayed further by Doze and App Standby `[V]`.

**No real-time guarantee is made.** The refresh model therefore combines:

- background refresh to keep the cache warm
- an active fetch when the dashboard or a host row becomes visible
- a visible "last updated" timestamp on every widget
- transition to `stale` after a configurable age
- manual refresh

Per-instance refresh intervals, rate-limit awareness, offline behaviour and cache retention are
handled in `core/data`.

**What is implemented** `[V]`: retrieval while the application is in the foreground, every 60
seconds, plus a manual refresh. The interval and the staleness threshold of 3 minutes are two
named constants in one place in `:app`. Retrieval stops when the application leaves the
foreground and starts again when it returns, which is also what refreshes the dashboard after a
visit to the settings. Two retrievals of the same instance never run at once: a refresh that
arrives while one is running is dropped rather than queued.

A failed retrieval keeps the last successful snapshot and shows it with its own timestamp,
marked as outdated or unreachable. A failure never looks like a current value. Snapshots live in
memory only; nothing decides yet for how long a stale value may be shown after a restart.

Background refresh with WorkManager is **not** implemented `[U]`.

---

## 5. Provider Bridge

The bridge is the interface a host launcher uses to display WIDGORA data. It is a read-only
`ContentProvider` exported by WIDGORA.

**Authority:** `io.github.jellyforgedev.widgora.bridge`
**Status:** draft. Not implemented. Several mechanisms still require device verification `[U]`.

### Design principle: display values only

The bridge transports **prepared display values and nothing else**. There is structurally no
field for a URL, a token, a hostname or a raw API response. Server-provided error text is never
forwarded, because such text routinely contains endpoints and stack traces; only a generic state
is exposed.

A host launcher therefore never learns where a service runs, and needs no network access of its
own to display WIDGORA data.

### Draft contract, version 1

`content://io.github.jellyforgedev.widgora.bridge/widgets`

| Column | Type | Notes |
|---|---|---|
| `widget_id` | text | opaque, stable identifier |
| `state` | text | one of the six display states |
| `title` | text | short label |
| `primary_value` | text | short summary value |
| `updated_at_epoch_ms` | integer | timestamp of the underlying data |
| `icon_token` | text | symbolic icon name, resolved by the host |
| `accent_token` | text | symbolic colour name, carries WIDGORA brand identity (variant B2) |
| `launch_uri` | text | deep link into WIDGORA only; see the constraints below |

Detail rows are available at
`content://io.github.jellyforgedev.widgora.bridge/widgets/{widget_id}/details` and are
**disabled by default**. They must be enabled per instance by the user, because monitor and
device names frequently contain internal hostnames. Row counts and string lengths are capped.

The contract version is exposed so that a host can detect incompatibility. Unknown columns must
be ignored by consumers, and a host that reads an unsupported version must degrade to showing
nothing rather than failing.

#### Constraints on `launch_uri`

```
widgora://widget/{widget_id}
```

- scheme `widgora` only, authority `widget` only
- path `/{widget_id}` only; no query string, no fragment
- no external URLs, no foreign schemes, no caller-supplied intent extras
- the only information carried is the `widget_id`
- no URI permission grants are attached
- the receiving component in WIDGORA independently validates that the `widget_id` is known and
  ignores everything else

**A custom scheme does not identify the target application.** Any application may register an
intent filter for the `widgora` scheme, so resolving this URI without further qualification can
open software other than WIDGORA, and the user would have no reliable way to tell. The scheme is
a routing convenience, not an identity claim.

A host must therefore bind the intent explicitly to the package
`io.github.jellyforgedev.widgora` before starting it, rather than letting the system resolve the
URI freely. If no component of that package can handle it, the host must do nothing instead of
falling back to another resolution.

The constraint is enforced on the emitting side, through explicit package binding on the host
side, and again on the receiving side, because a host cannot be relied upon to pass the value
through unchanged. The receiver validation remains in place regardless of how the intent was
started.

### Consent model

An unknown caller receives an **empty cursor**, never an error.

A query arrives while WIDGORA is in the background. Android 10 and later restrict starting
activities from the background, so WIDGORA **must not** attempt to prompt the user at query
time. Two mechanisms are planned instead:

1. **Pre-authorisation (primary).** The user enables host display inside WIDGORA, in the
   foreground, before it can take effect `[U]`.
2. **Deferred consent (fallback).** An unknown caller is recorded as a pending request. The next
   time the user opens WIDGORA, the request is presented for approval. Until then the host shows
   nothing `[U]`.

A notification-based prompt is not planned, because notification visibility on Android TV is
inconsistent and would need device verification first `[U]`. Full-screen intents and overlay
windows are rejected as inappropriate for this purpose.

### Caller verification

The provider resolves the caller through `Binder.getCallingUid()` and verifies the signing
certificate of the calling package. A changed certificate digest revokes consent and requires
re-approval.

The limits of this check are documented in `SECURITY.md`. In short: certificate verification is
an integrity check against accidental substitution, not a boundary against a deliberate
attacker, so the bridge's protection rests primarily on exposing very little.

### Actions

Write access — triggering an action such as switching a light — is a **separate channel** with
its own consent, enabled per integration, never granted implicitly with read access. It is not
part of the first milestone. The action contract is designed to be **integration-agnostic** from
the start, because a planned feature requires an action raised in one integration's widget to
target a different integration.

---

## 6. Host launcher integration

The intended first host is CleanMyTV Home. The following is based on reading its public
repository.

**Verified facts** `[V]`

- The launcher is Kotlin with Jetpack Compose and `androidx.tv:tv-material`.
- It targets `minSdk 28`, `targetSdk 36`, `compileSdk 37`.
- It is published under the MIT licence.
- Its home screen composes sections from a list of string identifiers, which makes adding one
  additional section a small, local change.
- It exposes **no widget, plugin or extension interface**. There is no `AppWidgetHost` and no
  third-party data interface.
- Its only exported configuration provider restricts callers to the shell and root user.
- It reads the system Watch Next provider read-only and displays entries whose owning package is
  installed and visible.

**Consequences**

- Native rendering inside that launcher requires a change in the launcher itself `[D]`.
- The intended approach is a narrow upstream contribution: one additional section that queries
  the bridge. This adds **no dependency** to the launcher and causes it to contact **no
  additional network host**, since WIDGORA performs all networking.
- No fork is planned. A fork is technically permitted by the licence but would require continuous
  rebasing against a fast-moving codebase, which is not sustainable for a small team.
- Publishing media items to the Watch Next provider is an additional, independent surface that
  requires no change in any launcher. It suits media content only — not weather or server status
  — and still needs device verification `[U]`.

Until a host integration exists, the standalone application is the product.

---

## 7. Design system

The design system lives in `app/design` and is centrally managed. Individual widgets do not
invent their own appearance.

Requirements:

- dark presentation as the starting point
- legible from several metres away: generous type sizes, restrained information density
- uniform widget shells across all integrations
- explicit, highly visible focus states for D-pad navigation
- consistent rendering of all six display states
- several widget sizes and information densities
- touch input is never assumed

In the standalone application the design system is fully authoritative. Inside a host launcher
(variant B2) typography and focus behaviour follow the host, while `accent_token` and
`icon_token` carry WIDGORA's identity.

**What is implemented** `[V]`: colours, text sizes, spacing, shapes, focus marks and the
overscan margin exist as named tokens in one file, and the tile reads them rather than inventing
values. The brand colours were measured from the logo file by decoding it and grouping its
chromatic pixels, not chosen by eye; that file is neither modified nor copied into the
application. Every colour that carries text or marks a state is checked against the backdrop and
against a surface with the WCAG formula by a test, and no state is distinguished by colour
alone: each carries a word and a mark as well. Text sizes have a floor, also asserted by a test.
The overscan margin follows Google's stated 5 % rule of 48 dp horizontally and 27 dp vertically.

**Localisation.** No display text is assembled in the integration any more. The summary's
wording arrives as whole phrases for a given count, which the application resolves from its
resources — as Android plurals, so a language needing separate forms has them. The counts and
the order of the parts stay in the integration and are tested there; the words do not.

Still open `[U]`: how all of this reads on real television hardware, and a second widget size or
information density.

---

## 8. Build and toolchain

| Item | Value | Status |
|---|---|---|
| Language | Kotlin | — |
| Build | Gradle with Kotlin DSL, version catalog | — |
| Toolchain JDK | Temurin 21 LTS, pinned in the toolchain block | `[V]` within the supported range: AGP 9 requires JDK 17 or newer, Gradle supports JVM 17 to 27 |
| `jvmTarget` | 17 | `[U]` |
| `minSdk` / `targetSdk` | 28 / 36 | `[V]` matching the intended host launcher's reach |
| UI | Jetpack Compose, `androidx.tv:tv-material` | `[V]`. That library ships no text field `[V]`, so text input uses the basic field from Compose Foundation, which is already in the tree through it. No second Material library is added for one component |
| HTTP | OkHttp, in the data module only | `[V]` in use for the first integration, behind the transport interface. No serialisation library yet: the first endpoint answers with text, not JSON |
| Persistence | DataStore, key-value variant, in the data module only | `[V]` in use for instance configuration. The typed variant is not used: it would require a serialisation library, and an instance is a handful of scalar fields |
| Credentials | Android Keystore, AES-256-GCM, non-exportable key, one alias per instance | `[V]` in use: non-exportability from the platform documentation, and the round trip, the fresh vector per encryption, the refusal of a tampered ciphertext and the destruction of key material on deletion from instrumented tests on an emulator. Real television hardware remains `[U]` |
| Background work | WorkManager | `[V]` for its interval limits |
| Dependency injection | none at MVP scale; manual constructor injection | `[V]` in use. The application class builds the repository once and hands it to a screen's state holder through the constructor. A screen depends on a small interface it declares itself, which is what makes its state holder testable without a device |
| Licence | **not yet decided** | `[O]` |

Retrofit is deliberately not used: the first integration returns Prometheus text rather than
JSON `[V]`, and the planned integrations differ enough in shape that typed interface generation
adds indirection without benefit.

---

## 9. Testing approach

- `:model` is pure Kotlin and testable without an emulator. The logic worth testing lives there.
- Unit tests cover state transitions, response parsing and error classification.
- HTTP failure modes are exercised against a local mock server: timeout, 401, 403, 500, empty
  body, malformed payload, oversized payload.
- Flow behaviour is tested with a Flow testing library.
- Remote-control navigation, focus order, several screen resolutions and memory behaviour are
  verified on real hardware, because an emulator does not reproduce focus behaviour faithfully.
- No feature is considered complete if it only looks right without processing real data.
- Mock data and placeholders are clearly labelled as such wherever they appear.

---

## 10. Open technical questions

| Question | Marker |
|---|---|
| Metric names and labels of the first integration's endpoint, including the sliding window label, the varying tag labels, missing values written as text and the numeric meaning of its status gauge: settled against a captured response of a running instance and against the upstream source | `[V]` |
| Whether deferred consent is acceptable in practice, given the host shows nothing until approval | `[U]` |
| Whether notifications are reliably visible on Android TV, as a possible consent prompt | `[U]` |
| Whether a third-party application can publish to the Watch Next provider on the target device | `[U]` |
| Whether a host launcher will offer an interface for native rendering | `[D]` |
| Licence for WIDGORA | `[O]` |
| Whether energy-system integration uses a direct local protocol or an intermediate platform | `[O]` |
