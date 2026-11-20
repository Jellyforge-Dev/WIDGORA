# WIDGORA — Architecture

**Project status: the first integration retrieves real data, the dashboard and a detail screen
display it, and no artefact has been released.** An Uptime Kuma instance can be set up on the
television, its metrics are retrieved over HTTPS, and the home screen shows the counts per state
together with the names of whatever is not working; a detail screen lists every monitor. There is
no widget per monitor, no background refresh and no Provider Bridge. **Nothing described below is
implemented unless this document says so explicitly**, and the markers say which is which.

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
- **Scenario B — display inside a host launcher (the long-term goal).** WIDGORA supplies a
  standard Android app widget, and any launcher able to host widgets can show it.

**Variant B2 is given up.** It held that a host would own typography, focus behaviour and basic
presentation while WIDGORA kept its identity through accent colours and icon tokens. That split
requires a host willing to render WIDGORA's data with its own components, and it is not what an
app widget does: a widget hands over a prepared view hierarchy, so WIDGORA owns everything
inside the box while the host owns the frame around it, its size, and whether focus enters at
all. The design system is therefore authoritative in both scenarios. That is simpler than the
arrangement it replaces, and it gives up the one thing B2 promised — blending into a host's own
look. Section 5a records what replaces it.

Scenario B is never a precondition for the product to function.

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

### Two kinds of prepared data, deliberately separated

This distinction runs through the whole codebase and is worth stating once, here.

| | `WidgetSnapshot` (`:model`) | `MonitorOverview` (`core/data`) |
|---|---|---|
| Read by | WIDGORA's own screens **and** a host launcher through the bridge | WIDGORA's own screens only |
| Holds | title, one short value, timestamp, two symbolic tokens | counts per state, and every item with its name, state and response time |
| Field set | frozen by a test | free to grow |
| Names of monitored things | structurally impossible | yes, that is its purpose |

A monitor name routinely contains an internal host name, which is topology rather than status.
Holding the names in a type that `:bridge` cannot reference — it depends on `:model`, never on
`:core` — makes the boundary a property of the dependency graph instead of something to remember
at each call site `[V]`. `SECURITY.md` describes the consequences.

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

**One package dependency inside `:core` is currently the wrong way round, and is recorded here
rather than hidden** `[V]`. An integration depends on `core/data`, which is the right direction:
the adapter maps its own wire types onto the application's neutral ones. But the snapshot
repository in `core/data` also names the first integration's client directly, because something
has to wire the two and there is only one integration. That second edge is what has to go when
`core/integration/kuma/` becomes its own module at the third integration — the wiring moves to
`:app`, as it already has for the transport. Until then the two packages are mutually dependent,
which a single Gradle module permits and a split would not.

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

### Monitored items, in neutral terms

A third model sits beside the two above and is smaller: the state of one monitored thing, as
`active`, `failed`, `pending`, `in maintenance` or `unknown` `[V]`. It lives in `core/data`, uses
no vendor's vocabulary, and each integration maps its own wire states onto it with an exhaustive
`when`, so that a wire state added later fails to compile rather than quietly becoming one of the
five. `unknown` exists so that an unrecognised state can never be counted as working.

Two orders are defined on it, and they differ on purpose. Counts are read `active, failed`, then
whichever of the other three occur — the first two numbers answer "how is the installation
doing", and they lead. A list of names is ordered `failed, unknown, pending, in maintenance` — it
answers "what do I have to look at", which is a question about urgency, and an unrecognised state
may well be a failure that is not understood yet.

### Certificate data of a monitored item

A monitored item may carry two further values: how many days its certificate has left, and
whether the source considers that certificate valid. They belong to the same application-internal
item type as the name and the state, and to nothing that leaves the application.

What the first integration's endpoint actually provides, read from the upstream source `[V]`:

- Two gauges, `monitor_cert_days_remaining` and `monitor_cert_is_valid`. The unit of the first is
  **days**, as a whole number, stated in the metric's own help text.
- They are written **only when the source has certificate information for that monitor at all**.
  An item without one — a ping check, a plain connection test — produces no row rather than a
  placeholder. Confirmed against a captured response of a running instance: twenty of its
  thirty-five monitors carry the two families and the other fifteen carry neither `[V]`.
- The validity gauge is written as `0` for everything that is not an explicit yes, a missing
  statement included. It therefore means **"not valid as far as the source says"** and is never
  rendered as "expired": those are different claims, and only one of them is supported.
- **The source never clears these two values.** They are removed when the monitor is deleted and
  not otherwise, so a value can outlive the check that produced it and stay until the service
  restarts. A certificate figure is therefore only ever shown together with the time the data was
  obtained, and an item whose own state is not active is marked as such beside its figure — a
  reassuring number nobody has re-checked for weeks is the failure mode worth designing against.

The upstream reading was taken from the project's development branch, so the behaviour of the
version running on any particular installation remains unverified `[U]`. The captured response
settles the shape of the data for one real installation; a deliberate check — switching a
certificate monitor off and seeing whether its row disappears — settles the rest, and belongs
before the feature that depends on it is built.

### One monitored item described twice

Observed against a running Uptime Kuma installation `[V]`: after a monitor is edited, the row
carrying its **previous** label values remains in the metrics response until the service is
restarted, and it does so in every monitor family at once. Four edited monitors produced four
duplicated identifiers across `monitor_status`, `monitor_response_time` and
`monitor_uptime_ratio`. No monitor was without a status and no value was unusual; the response was
simply no longer the shape the reader assumed.

The reader of the time rejected any response describing one identifier twice, so the dashboard
reported "answer not readable" for the whole installation — thirty-five monitors invisible because
four of them had been renamed. That is the price of being strict about something which is not
actually a structural fault, and it is why strictness is now scoped to structure alone.

Repetition is reconciled per family, and the rule has the same shape everywhere: **what the rows
agree on is used; what they disagree on is not guessed.**

| Family | Rows agree | Rows disagree |
|---|---|---|
| Status | that status | `unknown` |
| Response time | that value | no value |
| Uptime ratio | that value, per window | no value, for that window only |
| Certificate validity and days remaining | that value | no value |
| Name, type | that value | the smallest candidate by code point |

Three entries in that table are decisions rather than mechanics:

- **A contradictory status becomes `unknown`, never one of the two candidates.** Taking the better
  of the two would hide an outage, and `unknown` is already a state displayed in its own right
  that never counts as working. The single failure mode that matters here is a monitor silently
  reported as up.
- **A contradictory name resolves to the smallest candidate by code point**, not to the last row.
  The exposition format guarantees no ordering, so a positional rule could display a different
  name after every retrieval with nothing having changed on the server, and a name that flickers
  reads as a defect in WIDGORA. The cost is accepted and stated: after a rename, the older name
  may be the one shown until the service restarts. The state beside it is unaffected either way.
- **An absent label is not a contradiction.** The endpoint writes the string `null` where it has
  no value, which says nothing about what another row claims, so it is simply not a candidate
  rather than a competing one.

What remains a malformed response `[V]`: a line that cannot be parsed, a response with no
`monitor_status` family at all, a monitor the response describes without ever stating its status,
and an uptime sample with no window. Those are faults in the shape of the answer, and a reader
waving them through would let a half-transmitted response pass as a smaller, healthy installation.

#### The row left behind keeps the old state, not only the old name

Observed on the emulator against the same installation `[V]`: with the reconciliation above in
place, the four edited monitors appeared as `unknown` while the service's own interface showed all
four working, a refresh changed nothing, and restarting the service made them working again. Under
the rule, `unknown` for exactly those four means their status rows carried **different** values —
so the stale row keeps the state the monitor had at the moment it was edited. A contradiction is
therefore the ordinary consequence of editing a monitor, not a rare accident.

That makes the answer correct and the screen unhelpful. "Unknown" beside a monitor the user can see
working elsewhere reads as a defect in WIDGORA rather than as a statement about the answer the
service gave. The two roads to `unknown` are therefore kept apart:

| Why the item is `unknown` | What the detail screen says |
|---|---|
| the source described it twice and disagreed | contradictory information from the source, plus what to do about it |
| the source reported a state this version does not know | nothing beyond the state |

The second case gets no explanation on purpose: there is nothing the user can act on, and an
invented sentence would be worse than silence.

The reason travels as a note on the application's own item type, in two halves. The condition is
named in vendor-neutral terms, because any source could contradict itself; the remedy — restarting
the service — is a statement about *this* service and is supplied by the integration that reported
the item, as a resource of its own module. A second integration reporting the same condition can
give different advice without inheriting this one's. The note is not a sixth state: a state change
would move every count, every ordering and the summary the bridge carries, and nothing about the
counts is wrong here. The tile is unchanged for the same reason — a count cannot carry a reason.

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

A failed retrieval keeps the last successful snapshot **and the list of items behind it**, and
shows both with the timestamp of that success, marked as outdated or unreachable. A failure never
looks like a current value, and an empty detail list would be a different claim altogether — that
the installation has no monitors. Both live in memory only; nothing decides yet for how long a
stale value may be shown after a restart.

The detail screen is deliberately not a reason to stop retrieving: it shows the same data as the
tile and should keep it current. Only leaving the foreground or opening the settings stops it.

### A configuration that cannot be read is not an empty one

Reading the stored configuration has three outcomes, not two `[V]`: a list, an empty list, and
"there is a file and it could not be read". The third used to be folded into the second, which
told the user they had never set anything up while their instance, their address and their
encrypted credential were all still on disk. They would have retyped everything to repair
something that was not broken, and nothing would have pointed at the actual fault.

The distinction is cheap to make, because the storage layer already draws it: a missing file reads
as empty preferences, while a damaged one raises an `IOException` — a corrupt preference file
arrives as `CorruptionException`, which is one. Reading never throws into the flow a screen is
collecting, and a credential behind an unreadable file is reported as *unreadable* rather than
*missing*, for the same reason: absence is a claim, and it would be the wrong one.

An unreadable configuration yields no instances to retrieve, so the dashboard has no tiles. What
it has instead is one sentence saying that storage could not be read, which is a different
sentence from "no instance set up yet" and asks for a different action.

#### Writing to a file that cannot be read

Decided and implemented `[V]`: **the failure is reported, and the file is only ever replaced
because the user said so.**

Saving no longer raises. A write that storage refuses — a damaged file, an unwritable medium, key
material the platform will not use — comes back as a result, and the settings screen says which
action did not happen. The route that reached the old crash was the obvious one: read the new
"settings cannot be read" message, enter the instance again, press save. Deleting reports the same
way, and destroys key material only once the entry is actually gone from the file; the other order
would leave a configuration behind whose credential no longer exists.

Replacing the file automatically was rejected. A corrupt file still holds the user's address and
their encrypted credential, and silently discarding both to obtain a working screen decides on
their behalf that their data is worth less than the absence of an error message. Instead the
settings offer, **only while the configuration is unreadable**, a reset that removes the file and
all key material, behind the same confirmation that deleting an instance uses. Every key has to go
together, because the list of which keys exist is inside the file that cannot be read.

**The reset clears the stored values through the store first, and removes the file second.** The
order is not a matter of taste, and the first attempt had it the wrong way round. Shown by the
instrumented test on an emulator `[V]`: with the file deleted first, the clear that followed
reported success and wrote nothing at all. The store re-reads the file when it writes; it found no
file, read an empty set of values, and clearing nothing is not a change — so it skipped the write,
and a store republishes only what it has actually written. The file was genuinely gone, the reset
genuinely reported success, and the repository went on handing out the instance it had read before
the removal for as long as the process lived.

The reasoning behind that order was that removing a file from under an open store is defensible
when the store has never read anything successfully. That is true as far as it goes, and it was the
wrong thing to rely on: `reset` is a repository operation and cannot assume which screen called it
or what the store happens to be holding. Clearing first has no such hole — against a readable file
the clear is a real change, so it is written and published, and the removal that follows cannot
contradict a value that is already empty; against a damaged file the clear fails and the removal
carries the reset alone, which is sound because a failed read is not kept, so the next read finds
no file and reports an empty configuration.

One honest limit remains `[U]`: the behaviour against a *genuinely damaged* file on a device. The
instrumented test resets a readable file, and the unit tests drive the refusal through a double.
Only a hand check with a corrupted file can show the rest.

Background refresh with WorkManager is **not** implemented `[U]`.

---

## 5. Provider Bridge — deferred

**Status: deferred, not abandoned. Nothing of it is implemented, and the route to a host
launcher is now section 5a.** What follows is the design as it stood. It is kept whole, because
it is the reasoning a return to it would need, and it is no longer planned work.

**Why it is deferred.** The bridge carries values rather than a view, which is precisely what a
host needs in order to draw WIDGORA's data with its own components. No host asks for that
today. A standard app widget reaches the same product goal — WIDGORA's data on a host's home
screen — through a mechanism the platform already provides, and with far less surface to
secure: the consent model, the caller verification and the deep-link contract below all have no
equivalent there, because placing a widget is itself the user's decision, taken in the host, in
the foreground. Building both would mean maintaining two contracts for one purpose.

**The condition for its return:** a host launcher that wants to render WIDGORA's data with its
own components. Until then this section is reference material, and it is not maintained against
the rest of the document.

The bridge is the interface such a host launcher would use. It is a read-only
`ContentProvider` exported by WIDGORA.

**Authority:** `io.github.jellyforgedev.widgora.bridge`

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
| `primary_value` | text | short summary value, assembled from marks and digits and carrying no word of any language, because a host shares neither WIDGORA's resources nor its colours. At most 59 characters by construction, which a test asserts against counts no server could report `[V]` |
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

## 5a. App widgets

**Status: partly implemented.** One widget exists and works on a television `[V]`: it shows an
instance's counts, in two layouts that are chosen by the box it is given, with the figures scaled
to fill that box, and it is refreshed in the background every thirty minutes. A debug-only host
exists so that a widget can be placed and operated without waiting for a launcher. What is
decided and **not** implemented is three widget types instead of one, a configuration step per
placed widget, and the rule that replaces the module boundary for monitor names.

WIDGORA supplies standard Android app widgets — an `AppWidgetProvider` per type, each handing the
host a `RemoteViews` hierarchy. Any launcher able to host widgets can display them, which makes
the host side a platform feature rather than an agreement with one project.

| Decision | Choice |
|---|---|
| Implementation | `RemoteViews` directly, not Jetpack Glance |
| Widget types | three, each a provider of its own and its own entry in a host's picker |
| Credentials per widget | **none, ever** — a widget points at an instance set up once in the application |
| Configuration | one step per placed widget, one screen serving all three types |
| Periodic update by the widget framework | off, `updatePeriodMillis="0"` |
| Refresh | WorkManager, every 30 minutes |
| Outdated after | 45 minutes |
| Additional refresh | every foreground fetch also pushes an update to the widgets |
| Monitor names in a widget | types 1 and 2: a switch, off by default. Type 3: the selection is the consent |
| Device feature `android.software.app_widgets` | declared, `required="false"` |
| Sizes | declared per type, and measured rather than estimated |
| A widget that has not been configured | says so; it never shows sample values |

**Why not Glance.** Glance is not directly interoperable with Jetpack Compose UI and uses its
own set of composables `[V]`, so the design system that draws the dashboard cannot be carried
across either way. The presentation is written a second time whichever is chosen, and
`RemoteViews` is what the host actually receives, with nothing between the code and the result.
It also adds no dependency.

**Why periodic updates are switched off.** The widget framework's own mechanism has a floor of
30 minutes and wakes a sleeping device in order to run `[V]`. On a television that means waking
to fetch data nobody is looking at. WorkManager has a floor of 15 minutes and can be delayed
further by power management `[V]`; 30 minutes sits above that floor, so a single deferred run is
ordinary rather than visible. Outdated after 45 minutes — one and a half intervals — means one
skipped run does not mark a widget stale. The time the data was obtained is shown either way.

**Why the device feature is not required.** A device may support widgets or not, and the
standalone application does not depend on them. Observed `[V]`: a television running Android 14
declares `android.software.app_widgets`, while a Google TV system image at API level 36 does
not. Declaring the feature as required would hide WIDGORA from the store on such a device for
the sake of a surface that is an addition rather than the product.

**The click target.** A widget opens WIDGORA and nothing else. The host is handed a
`PendingIntent` built in WIDGORA's own process, carrying an explicit intent to a WIDGORA
component together with that widget's own identifier, and marked immutable, so a host can send
it but neither redirect it nor add anything to it `[V]`. This replaces the `launch_uri` field of
the deferred bridge and is stricter than the rule it replaces: there is no scheme to register,
so the substitution the old rule existed to contain cannot arise. The receiving side still
validates the identifier it is given, for the same reason as before — neither side trusts the
other with more than an identifier.

### Three types, three providers

| Type | What it shows | Monitor names |
|---|---|---|
| **Status** | the counts per state, as marks and digits, for one instance | a switch, off by default; when on, the affected items are named |
| **Certificates** | items whose certificate expires within the horizon, soonest first | a switch, off by default; without it, only how many and how soon |
| **Selection** | items the user picked, with their state, under the counts for the whole instance | inherent: the selection is what grants them |

Three providers rather than one provider with a setting, because a host's picker lists providers.
A user who wants a certificate widget should find one in that list and not place a generic widget
and then convert it. The cost is three sets of metadata and three layout pairs, which is also
three sets of measured minimum sizes — a type showing six rows cannot promise the box that suits
a type showing one line.

The horizon for the certificate type is **30 days**, with emphasis below 7 and anything already
expired sorted first. The figure is a decision and not a derived one: certificates renewed
automatically are usually renewed with 30 days left, so what is still inside that window has
failed to renew and is worth saying. A longer horizon would fill the widget with entries nobody
has to act on — a captured response of a real installation has one monitor at 651 days and one at
4139 `[V]`.

### One configuration step, one screen

Placing a widget runs a configuration step declared as `android:configure`. It asks for:

- **the instance** — pre-selected when only one exists, which today is always. It is asked even
  so, because the question is what makes a widget point at *an* instance rather than at whichever
  one happens to be first, and that is the part the second instance will depend on.
- **whether monitor names may appear** — for the status and certificate types, off by default.
- **which items** — for the selection type only. The list is read from the instance, and what is
  stored is the item's identifier, never its name.

One screen serves all three types and reads the type from the provider that launched it. Three
screens would be three places for the same focus handling, the same instance list and the same
confirmation to drift apart. It is operated with a remote control, which is the same constraint
the settings screen is built to; the rules there apply here unchanged.

**A widget never holds a credential.** There is no field for one in a widget's settings and no
path from a widget to the secret store other than the instance the application has already set
up. That is the whole point of configuring instances once, in the application.

### Where a widget's settings live

A second key-value store, in a file of its own beside the instance store. Separate deliberately,
because the two obey **opposite** rules: an instance file holds an address and an encrypted
credential and may never be replaced on the application's own initiative, while a widget file
holds nothing that cannot be asked again and may be discarded if it is damaged. Two opposite
rules in one file would mean the stricter one losing.

Keys are flat and per `appWidgetId`: the type, the instance, whether names are allowed and — for
the selection type — the chosen identifiers in the order the user put them in. Everything read
back is validated: an unknown type, a missing instance, too many identifiers or one with an
unexpected shape makes the entry unusable, and the widget says it is not configured instead of
guessing.

Removal happens twice over, because once is not reliable. The provider's own deletion callback
removes the entry, and every update additionally sweeps stored identifiers that no longer belong
to a placed widget. The callback can be missed; the sweep cannot.

Two situations are decided rather than left to the code:

- **The instance is gone.** The widget says so and asks for the settings. It is never silently
  re-pointed at another instance: a widget that quietly starts showing a different server is
  worse than one that says something is missing.
- **Selected items are gone.** The remaining ones are shown and the number of missing ones is
  named — without names, which are gone anyway. Their identifiers are **not** removed from
  storage, because an item can come back: this endpoint is already known to drop and restore rows
  across a service restart. Clearing them is offered in the configuration step, where the user
  can see what they are agreeing to.

### Keeping monitor names out of a widget without the module boundary

Until now one boundary did this work on its own: the bridge module depends on the domain module
and never on the data module, so it could not reference the type holding the names even by
mistake. A widget provider lives in the application module and can reach everything, so that
boundary does not apply, and two of the three types need names on purpose. The replacement is
**two render models and a token**:

- A nameless render model, as today, with no field that can hold a name — a fact a test holds, so
  adding one has to be a decision made in review.
- A separate named render model, constructible only through a factory that requires a consent
  token. The token has a private constructor and is produced in one place: the reader of a
  widget's settings, when that widget is allowed names.
- Two build functions, one per model, with no overload accepting both. There is no path on which
  a nameless model reaches the naming renderer or the reverse.

This is weaker than a module boundary and is written down as weaker. What carries the remaining
weight is a set of tests described in SECURITY.md, including one that walks the inflated view
tree of a built widget — the actual hierarchy a host receives — and asserts that none of the
thirty-five names in a captured response of a real installation appears in it.

### What a widget shows at each size

The existing size steps are kept: a compact layout, a full one, and scaling into a large box. The
line budgets below are a starting point to be corrected by the first measurement, not a result.

| | compact | full | large |
|---|---|---|---|
| Status | title, counts, state and time | the same, figures scaled up | plus up to 3 affected items named |
| Certificates | how many within the horizon, and the smallest figure | up to 3 rows | up to 6 rows |
| Selection | the instance's counts plus 2 rows | plus 4 rows | plus 8 rows |

More entries than rows produces the first *n* in the type's own order followed by a count of the
rest. A widget draws at most twelve rows whatever its size, so that an installation with a
thousand monitors cannot build a hierarchy that has to cross a process boundary.

Every empty case is a sentence rather than blank space: nothing expiring within the horizon,
no monitor checking a certificate at all, no item selected. The minimum sizes of each type are
measured inside a real host view at several font scales, because a host does not hand a widget
the box it declared — that is how the first type's declared minimum turned out to be one it could
not keep.

### Refresh, in practice

**Implemented** for the one widget type that exists. A `CoroutineWorker` does exactly what the
foreground loop does — retrieve, then push to the widgets — on a periodic request of 30 minutes
with a network constraint, kept rather than replaced when it already exists. It is enqueued when
the first widget appears and cancelled when the last one goes, and every start of the application
brings the queue back in line with the widgets actually placed, because a broadcast can be missed
and an update can be installed over a version that queued nothing.

Three things are worth recording about how it came out:

- **The scheduling decision and the outcome decision are one small object with no Android type in
  it**, so both can be exercised without a device. Each has a wrong answer that would be invisible
  on a television: work queued for a widget nobody placed retrieves every half hour for nothing,
  and a retry reported for an unreachable server retries with backoff instead of letting the
  widget say what happened.
- **The library's default initialisation is used as it comes.** It registers itself through App
  Startup, joining a provider four other initializers already share. A custom configuration would
  mean removing its initializer from the merged manifest, and nothing here needs one.
- **The library writes into the merged manifest**: three permissions and six components, two of
  them exported behind permissions an ordinary application cannot hold. All of it is listed, with
  its exported status, in SECURITY.md — including the one permission that is present and unused.

Nothing about the widget framework's own periodic update changed: `updatePeriodMillis` stays zero,
for the reason given above, and the staleness threshold stays 45 minutes — one and a half runs,
which a test now holds against the interval so the pair cannot drift apart.

No extra rule guards against overlapping with the foreground fetch, because one already exists:
two retrievals of the same instance never run at once, and the later one is dropped.

A failed retrieval is **pushed, not hidden**: an unreachable instance is news, and the widget says
so rather than keeping a comfortable older picture. The run itself then ends successfully, since
the next periodic run is the retry; only a technical abort before the push asks to be retried.

What power management does to this on a television is the open part `[U]`. Periodic work is
deferred in low-power states and does not run at all while a device is off, so a widget can carry
an old timestamp until the first run after waking. One more thing is worth stating plainly: a
widget's state is decided when an update is pushed, so a widget pushed as current keeps reading
as current until something pushes again. The 30-minute run is what bounds that, and the 45-minute
staleness threshold is one and a half runs for exactly this reason.

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
- **The intended approach is no longer an upstream contribution to that launcher.** Its
  maintainer has stated that a standalone application is the right shape, that ordinary Android
  app widgets would let any launcher display WIDGORA, that the launcher could host them once it
  hosts widgets at all, and that a test build would be welcome once a widget works. No date is
  attached to any of it `[D]`. WIDGORA therefore supplies a widget — section 5a — and hosting is
  that project's own work. It still adds **no dependency** to the launcher and still causes it
  to contact **no additional network host**, because WIDGORA performs all networking.
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

**Marks instead of words for the counts** `[V]`. A tile shows each count as one character and a
number — a filled triangle up for working, a triangle down for failed, and a diamond, square or
circle for the three states that appear only when they occur. All five are from the Unicode block
Geometric Shapes, which the platform text font covers, so none can become a missing-glyph box on
a television; none is an emoji, because an emoji is drawn from a separate font with its own
coverage and carries a colour that would fight the state colour. The five outlines differ in shape
alone, so the marks still separate the states for a viewer who cannot tell the colours apart, and
every number additionally carries its phrase in words for a screen reader. There is no switch
between words and marks: two presentations of the same number is two things to keep correct.

Words were the previous presentation and were replaced because they did not fit. A summary long
enough to name five states in a sentence either overflows a tile or has to shrink, and the length
depends on the language, which makes it a defect that appears only in translation.

**The state line names its subject** `[V]`. Every surface shows one line about the retrieved
**data** — how old it is, or why there is none. It says nothing about the monitored services, and
it used to look as though it did: a successful, fresh retrieval read "OK Current" in the green of
a working monitor, directly beside a count of two that were not working. Three rules now hold the
line apart from the counts, each asserted by a test:

- **Every word names its subject.** "Loading data", "Data current" and "Data outdated" for the
  statements about what is shown and how old it is; "No monitors in this instance" where the
  answer held nothing, which names what that means rather than describing the answer; and
  "Instance not reachable", "Key rejected", "Answer not readable", "Retrieval failed" for the
  failures, each naming the thing that failed. Only the wordings that left their subject unstated
  were changed; the ones already naming the key, the answer or the retrieval were not touched.
- **No mark claims that anything is well.** The mark for current data is `=` — the figures are
  what the source last reported, and nothing beyond that. It is not a tick. No mark of this line
  comes from the Unicode block the five monitor marks are drawn from, and the test rules out the
  whole block rather than the five characters currently in use.
- **No state of this line is green.** Current data is drawn in the muted foreground, the colour
  the timestamp below it already uses: a quiet fact rather than an achievement, at 11.79 : 1
  against the backdrop and 10.86 : 1 against a surface. Green stays the colour of a monitor that
  is up.

Word and mark remain mandatory on every surface, and colour is never the only channel. On the
widget's compact variant, where this line shares its row with the time, the longer wordings mean
the word is the part that may shorten — never the mark and never the time, which an instrumented
measurement holds at three font scales.

**Affected items are named** `[V]`. Below the counts a tile lists up to three names of the things
that are not working, then "+N more". Each name is shortened by code points, never by char index,
so that a name ending in a character outside the basic plane cannot be cut in half. A tile with
everything working shows no list at all: a line saying so would occupy the space that matters on
the one day it is not true.

**The tile is focusable and opens a detail screen** `[V]`. The detail screen lists every monitor
with its mark, name, state in words and response time where one was measured, the ones needing
attention first. Its rows are focusable without being actionable: a directional pad can only
scroll a list by moving focus through it, so focusability is what makes the list reachable at all,
and each row therefore carries a visible focus mark of its own.

A row whose item carries a note shows it on a second line, in the colour of the state it explains.
It is the only place in the application that explains a single item, and the only place that needs
to: see "The row left behind keeps the old state" in section 3 for what is explained and why the
tile is left alone.

**What the centre key does depends on the tile** `[V]`, and so does whether the tile can be
focused at all. Three cases, applied in this order:

| Tile shows | Centre key | Focusable |
|---|---|---|
| a notice — switched off, credential unusable, stored entry invalid | opens the settings | yes |
| a list of items, current or kept from the last success | opens the detail screen | yes |
| neither — nothing retrieved yet, or an installation with no monitors | nothing | **no** |

A notice is configuration the user has to change, and the settings screen is the only place to
change it; offering a list that is absent or left over from before the problem would be the wrong
destination. The third row is why this is a rule and not just "is there a notice": a tile with
nothing behind it is skipped by the focus rather than being focusable-but-inert, because an
element that accepts the focus and then does nothing reads as a fault in the application, and a
screen saying "nothing retrieved yet" is a dead end the viewer has to back out of. The same rule
also closes the detail screen by itself if the instance it is showing loses its list while open.

**The focus mark is neutral, and it is one token** `[V]`. A thick white border, used identically
on the tile, the detail rows and the text fields, together with a slight enlargement where there
is room for one. It is deliberately not the brand colour, for three reasons in order of weight: a
focus mark says where the remote is pointing, which is not a statement about the product, and
spending the brand colour on it leaves nothing to mark the primary action; white is as far as this
palette gets from the resting border, and the entire job of the mark is that focused and unfocused
are told apart across a room — 20.19 : 1 against the backdrop where the brand violet reaches
7.25 : 1, against a resting border at 3.81 : 1; and a neutral carries no hue, so it cannot be
mistaken for one of the state colours. Both ratios are asserted by tests, as is the gap between
the focused and the resting border.

**The enlargement needs room, and gets it** `[V]`. A focused element grows about its centre, so an
element of width *w* grows *w* × (scale − 1) / 2 past each edge. A lazy list clips its own bounds,
which meant the mark was cut off exactly where the focus starts and ends up — the first and last
position. The headroom is one token, applied as content padding by the row of tiles, and a test
holds it against the widest focusable element, so a wider tile or a stronger enlargement cannot
outgrow it unnoticed. Detail rows and text fields take the border alone and no enlargement: they
already span the full width, so growing them would push their edges into the overscan margin, and
no amount of padding buys room that is not there.

**Line heights are part of the typography tokens** `[V]`. This was a defect, not a refinement.
`Text` from `androidx.tv.material3` takes its line height from the ambient text style, so
overriding only the size left a 44 sp value in a line box built for body text, and a text long
enough to wrap drew its second line on top of the first. A size is no longer obtainable without
its line height, and a test holds the ratio.

**Localisation** `[V]`. A snapshot's value carries no language at all. The words a screen shows —
the state of a monitor, the count phrases a screen reader announces, the overflow line — are
resolved where they are drawn, from the resources of the device's language, as Android plurals so
that a language needing separate forms has them.

Still open `[U]`: how all of this reads on real television hardware; whether the directional pad
scrolls the detail list as intended; and a second widget size or information density.

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
| That an edited monitor leaves its previous row in the response until the service restarts, in every family at once | `[V]` |
| That the row left behind carries the monitor's former **state** as well as its former labels, so an edit produces a contradiction rather than a harmless repetition | `[V]` |
| That the endpoint's two certificate gauges are written only where certificate information exists, that its validity gauge means "not valid as far as the source says" rather than "expired", and that neither is ever cleared when a check stops producing one | `[V]` against the upstream source and a captured response |
| Whether the version of that service running on a given installation behaves as its development branch does, in particular whether a disabled certificate check leaves its last figure standing | `[U]` |
| How long a television in a low-power state defers a periodic refresh, and what a widget therefore shows when the device wakes | `[U]` |
| Whether the broadcasts that start and stop the periodic refresh reach the provider on the target device. The documentation states only that the filter must accept the update action and says nothing either way about the other two, so both are declared and the delivery is to be confirmed by placing and removing a widget | `[U]` |
| Whether to strip the unused `FOREGROUND_SERVICE` permission the work library contributes, given it is declared for a fallback path WIDGORA never takes | `[O]` |
| The minimum sizes of the second and third widget type, measured inside a real host view at several font scales rather than estimated | `[U]` |
| Whether a directional pad scrolls the detail list as intended, and whether focus lands on its first row | `[U]` |
| ~~Whether the state line's `=` for current data and `–` for empty data are told apart from three metres, given that both are horizontal strokes~~ — confirmed on a television: `=` is clearly legible from three metres. The two never appear together in any case, being states of one line, and the word beside the mark names which it is | `[V]` |
| ~~Whether the lengthened wordings of the state line still fit the widget's compact row on a television without the word shortening in the common cases~~ — confirmed on a television: "= Data current" is not shortened at any widget size | `[V]` |
| ~~Whether the neutral focus mark and the enlargement are visible and uncropped at the ends of the row on a television~~ — confirmed on the emulator; real hardware still open | `[V]` |
| ~~What saving should do when the configuration file exists but cannot be read: replace it, or report the failure~~ — **decided**: report it, never replace automatically, and offer a reset the user confirms | `[V]` |
| That a reset of a readable configuration leaves nothing behind on a device — and that the removal must follow the clear, not precede it, because a store republishes only what it has actually written | `[V]` |
| How a *damaged* preference file behaves on a device when written to and when reset, as opposed to through a test double | `[U]` |
| Whether the explanation offered for contradictory data is understood, and whether a restart of the service is advice the user can act on | `[U]` |
| ~~Whether the focus treatment the TV component library applies to its own buttons matches the project's neutral mark, since those buttons are not styled here~~ — checked on the emulator: they no longer light up in the brand colour | `[V]` |
| Whether deferred consent is acceptable in practice, given the host shows nothing until approval | `[U]` |
| Whether notifications are reliably visible on Android TV, as a possible consent prompt | `[U]` |
| Whether a third-party application can publish to the Watch Next provider on the target device | `[U]` |
| Whether a host launcher will offer an interface for native rendering | `[D]` |
| Licence for WIDGORA | `[O]` |
| Whether energy-system integration uses a direct local protocol or an intermediate platform | `[O]` |
