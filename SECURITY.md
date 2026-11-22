# WIDGORA — Security

**Project status: the first integration is implemented and no artefact has been released.**
Credential storage, transport security and the handling of untrusted responses exist in code and
are described below as implemented where that is the case. The Provider Bridge does **not** exist
yet; everything stated about it is design. This document records the security reasoning so that it
can be reviewed and checked against the code, and every claim carries a marker saying which of
the two it is.

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

## 3. Credential and topology handling

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
- The same reasoning applies one level up, to the stored configuration itself `[V]`. A
  configuration file that exists and cannot be read is reported as **unreadable**, never as an
  empty configuration, and a credential behind such a file is reported as unreadable rather than
  missing. Reporting absence would be a false statement about the user's own data: it would say
  nothing had ever been set up while their instance, their address and their encrypted credential
  were all still on disk, and it would invite them to retype a credential to repair something that
  was not broken. Reading never throws into the screen either.
- Writing over a file that cannot be read is now settled `[V]`: **the failure is reported and the
  file is never replaced on the application's own initiative.** Saving and deleting return an
  outcome instead of raising, and the screen names the action that did not happen. Replacing a
  damaged file automatically was rejected on exactly these grounds — such a file still holds the
  user's address and their encrypted credential, and discarding both to obtain a working screen
  would decide for them that their data matters less than the absence of an error message.
- The one operation that destroys credentials the user did not name individually is a **reset they
  ask for** `[V]`. It is offered only while the configuration is unreadable, behind the same
  confirmation as deleting an instance, and it removes the configuration file together with **all**
  key material belonging to the application. All of it has to go, because the list of which aliases
  exist is inside the file that cannot be read; leaving them behind would leave key material
  nothing could ever name again. Nothing calls this automatically, and no failed read or failed
  write leads to it. Deleting a single instance still destroys that instance's key material only,
  and only once the entry is actually gone from the file. Enumerating aliases cannot reach another
  application's keys: the keystore an application sees holds only its own entries.
- A reset reports success only when the stored values are really gone `[V]`. The first
  implementation removed the file before clearing the values through the store, which on a device
  left the application serving the configuration it had already read while reporting the reset as
  done — a false assurance about data the user believed destroyed. The order is now the other way
  round, and an instrumented test holds it. The key material is unaffected by the order: it is
  destroyed first, and nothing of it survives either path.
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
  means "keep the stored one".
- Entry is **masked, and can be revealed on request** `[V]`. A switch beside the field shows what
  was typed in this session and nothing else. It reads nothing to do so: the only value it can
  uncover is the one already in the screen's state, and a test holds that by counting the reads
  storage receives while the switch is used — a stored credential is still never fetched. The
  switch starts off, it goes off again once a successful save has emptied the field, and it goes
  off when the screen is left. Its label is a verb, never a value, so neither the label nor any
  description derived from it can carry the credential.
- **No input field on that screen lets the keyboard suggest or learn** `[V]`. The credential, the
  address and the instance name are all declared to the keyboard as password fields, so that it
  offers no word completions for them and takes nothing they contain into its dictionary. The
  address is included deliberately: it can name an internal host, which is topology and not to be
  kept anywhere outside this application. Measured on a device, as `EditorInfo.inputType` reported
  by the input-method service: `0x91` for the name, the address and a revealed credential — that
  is `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` — and `0x81` for a hidden
  credential, the ordinary password variation. This followed a finding on a television: an earlier
  version declared the revealed credential as ordinary text so that it would stay readable, and the
  keyboard answered with a strip of word suggestions. The limit worth naming `[U]`: withholding
  suggestions for a password field is what the keyboards the platform ships do, not something the
  platform enforces, and the flag that would state the intention outright —
  `IME_FLAG_NO_PERSONALIZED_LEARNING` — cannot be set from this toolkit at all.
- The reason that switch exists is that the credential is typed with a remote control on an
  on-screen keyboard, where a mistake in a forty-character key would otherwise be found only when
  the connection test fails. The risk it accepts is the obvious one — a key in clear text on a
  television in a room with other people in it — which is why it is off unless asked for and does
  not stay on. A refused save is the one case where it is left as the user set it, because nothing
  was written and nothing was cleared, and they may be about to correct what is still in the field.
- The stored configuration of an instance carries no credential at all: its type has no field
  for one, and an address containing credentials, such as `https://user:pass@host`, is refused
  when the instance is saved. A credential belongs in the keystore, not in an address.
- A logging wrapper masks known credential fields. No integration logs raw request or response
  bodies at any level that ships in a release build.
- `androidx.security:security-crypto` is **deprecated** and is not used `[V]`. The platform
  documentation states that all of its APIs were deprecated in the stable 1.1.0 release, that no
  further releases will follow, and that the replacement is direct use of the Android Keystore,
  which is what this project does.
- WIDGORA asks for `INTERNET` and `ACCESS_NETWORK_STATE`, and for nothing else of its own. No
  accessibility service, no overlay permission, no system settings permission.
- One further permission appears in a built package, and it is named here because a list that
  quietly omits something is worth less than one that explains it `[V]`. A component library the
  application depends on declares and requests a permission of its own, derived from the
  application's identifier and ending in `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. It is
  defined at signature level, it exists so that the library's own internally registered receivers
  cannot be reached from other applications, and it grants nobody access to anything outside this
  application. WIDGORA's own code neither declares nor requests it, and it cannot be removed
  without removing the library.

### What the background refresh adds to the package

The periodic refresh behind the widgets is built on the platform's work scheduler, and that
library writes into the merged manifest. Three permissions and six components appear in a release
package that WIDGORA's own manifest does not mention. They are listed in full `[V]`, read out of
the merged release manifest before and after the library was added, because an inherited manifest
entry nobody looked at is exactly what a document like this exists to prevent.

| Permission | Why the library asks for it |
|---|---|
| `WAKE_LOCK` | held while a run is in progress, so the device does not sleep mid-retrieval |
| `RECEIVE_BOOT_COMPLETED` | the library's reschedule receiver re-registers work after a restart |
| `FOREGROUND_SERVICE` | for expedited work only, which WIDGORA never asks for — see below |

All three are normal permissions: granted at install time, never prompted for, and none of them
grants another application anything. Two are load-bearing here — without the first a run can be
cut off halfway, and without the second a television switched off at the mains comes back with no
refresh scheduled at all.

`FOREGROUND_SERVICE` is the one that is present and **unused**. The library declares it for the
service it would start if work were requested as expedited, and WIDGORA requests none: the request
carries an interval and a network constraint and nothing else. It is named here rather than
quietly inherited, and whether to strip it from the merged manifest is an open question and not a
decision already taken — removing a permission a library declares for its own fallback path is the
kind of change that fails only in the situation it was there for.

| Component | Exported | Gate |
|---|---|---|
| `androidx.work.impl.background.systemjob.SystemJobService` | **yes** | `android.permission.BIND_JOB_SERVICE` |
| `androidx.work.impl.diagnostics.DiagnosticsReceiver` | **yes** | `android.permission.DUMP` |
| `androidx.work.impl.foreground.SystemForegroundService` | no | — |
| `androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver` | no | — |
| `androidx.work.impl.background.systemalarm.RescheduleReceiver` | no, and `enabled="false"` | — |
| `androidx.room.MultiInstanceInvalidationService` | no, `directBootAware="true"` | — |

Two of the six are exported, and neither opens anything to another application `[V]`:

- The job service **has to** be exported, because the party that binds it is the system's job
  scheduler, in another process. What keeps everyone else out is the permission on it:
  `BIND_JOB_SERVICE` is held by the system and cannot be granted to an ordinary application.
- The diagnostics receiver is gated by `DUMP`, which an installed application cannot hold either;
  what can send it is the shell. That is the same shape as the profile installer's receiver, which
  was in the package before this change. What it reports when asked is a list of work:
  identifiers, worker class names, unique names and states. It carries no address, no credential
  and no monitor name — because the request it reports on has no input data to leak, which is the
  second reason that request is built empty. The first is below.

**What the work database holds.** The unique name of the request, the worker's class name, the
interval and the network constraint. Nothing is handed to the worker: it reads the instances the
application has already stored, through the same repository and the same keystore-backed secret
store as the dashboard, at the moment it needs them. An address or a credential placed in input
data would be a second copy of exactly the material this section keeps in one place.

**Initialisation stays the default** `[V]`. The library registers itself as an App Startup
initializer, and the merged manifest shows it joining a provider four other initializers already
share — emoji handling, process lifecycle, the profile installer and the HTTP client. No custom
configuration is used, and that is a saving rather than an omission: the documented way to supply
one is to remove the library's initializer node from the manifest, and with a provider shared five
ways that is a manifest edit with four unrelated things to get wrong. Nothing WIDGORA needs asks
for it — the worker has the constructor the default factory expects, and the library's own logging
level is left alone.

### Where monitor names live

A monitor name is topology, the second of the three assets. "nas.kitchen.lan" names a machine and
says where it stands. The application has to show such names — a list of monitors with their
states is the point of the detail screen — and must still never hand them to anybody else. The
separation is therefore made structural rather than left to care at each call site `[V]`:

- The names, the counts and the individual items live in an application-internal type in
  `core/data`. The dashboard and the detail screen read it.
- A `WidgetSnapshot` — the type the bridge carries — has **no field that can hold a name**. Its
  field set is frozen by a test, so adding one has to be a deliberate decision made in review.
- `:bridge` depends on `:model` and never on `:core`. It therefore cannot reference the type that
  holds the names, even by mistake. The boundary is enforced by the dependency graph, not by a
  convention.
- The summary value a snapshot does carry is assembled from marks and digits — one character per
  state, followed by a count. It contains no name, no word of any language and nothing a server
  wrote. Tests assert this against a response that names real hosts: the mapping's own test
  checks a synthetic name, and the test that reads an anonymised recording of a running
  installation checks every one of its 35 names against the resulting snapshot.
- Monitor names are not logged.

When the bridge is implemented, its optional detail rows remain a separate, per-instance decision
by the user, for the reason given in section 5.

### A widget may name a monitor only by consent

**Implemented** `[V]`, with the four tests below in place and green on an emulator. Real
television hardware remains unverified `[U]`.

App widgets are the route that exists today, and they break the arrangement above in one specific
place. The guarantee above rests on a **module boundary**, and a widget provider lives in the
application module, where everything is reachable. Two of the three widget types are also meant
to show names: one lists the monitors behind a failure, and one shows a set the user picked, for
which a row without a name would be a row nobody can place.

So the structural guarantee is replaced, and the replacement is **weaker**. That is stated here
rather than glossed over, because a security document that describes a weaker measure in the
language of the stronger one it replaced is worse than no document. A module boundary is checked
by the build: code that reaches across it does not compile, and no amount of carelessness gets
past it. What follows is checked by the type system within one module, by review, and by tests —
all three of which a determined author can work around, which the dependency graph did not allow.

What replaces it:

- **Two render models, not one with a flag.** One has no field that can hold a name, and a test
  holds its field list. The other can carry names. The two build functions take one model each
  and there is no overload accepting both, so a nameless model cannot reach the naming renderer
  or the reverse.
- **A consent token with a private constructor**, and the one function that produces it takes a
  widget's stored settings and returns nothing unless they say yes. So the only material a token
  can be made from is a real consent: there is no expression anywhere in the application that
  yields one otherwise, and adding one would mean editing that file, which is a visible,
  deliberate change in review. The point is not that it is unforgeable — within a module nothing
  is — but that it cannot be produced absent-mindedly.
- **The two renderers have different names**, not one name with two signatures, so there is no
  call site at which the wrong model could be passed and resolved by overload selection.
- **The named model's own `toString` prints the number of names and not the names.** It is the
  one type in the chain allowed to hold them, which is exactly why its textual form must not
  carry them: a log line, an assertion message or a crash report is not a widget. A test asserts
  it.
- **Off by default**, per placed widget, for the counts and certificate types. Nothing is global:
  consent given for one widget says nothing about another.
- **With consent, a name now crosses in every size step, and that is a widening** of what this
  section described before. It used to say that the two smaller steps carried no name at all,
  because the names did not fit there as a list; they now give up their *title* line instead,
  which carries the first affected item's name. Recorded as a change rather than quietly
  replaced: the earlier sentence was a true statement about a narrower behaviour, and a security
  document that lets a widening pass as a detail is the one nobody can rely on. What bounds it is
  that it is still **one** name — the first affected one, and only where something is actually
  wrong — in one line, only with a stored yes, and that the device test asserts no second view
  carries a name.
- **Until this round the largest box carried one name and not the list, whatever this section
  said.** The step built for the names was never chosen on a device: its size was handed to the
  platform as the box a host offers, while the platform compares against that box minus the
  padding its own host view takes, so the step asked for more height than the largest box can
  give. What a television actually drew was the full variant with one name in its title.
  Correcting it therefore *increases* what crosses in that one box — from one name to up to three,
  which is what this document had been describing as though it were already so. The claim was true
  of the intent and false of the behaviour, and keeping those apart is what a security document is
  for; ARCHITECTURE.md §5a carries the arithmetic and the evidence.
- **For the selection type, the selection is the consent**, and the configuration screen says so
  in words — that the monitors chosen there will appear by name in that widget. Consent the user
  did not understand they were giving is not consent, so it is spelled out at the moment it is
  given rather than recorded in a setting they would have to go looking for.
- Monitor names are still not logged, and a host still receives nothing but what the widget draws.

**The two list types are implemented now, and they widen what crosses in a third way.** Both draw
up to eight monitor names, which is more than any status widget ever shows, and both reach a
renderer only through a third render model — built by two factories that each require a consent
token, with a private constructor and a `toString` that prints counts rather than names, exactly
as the named model does. Three things bound it:

- **Each type's consent is stored, and each is a real answer.** The certificates type has no
  switch: a list of certificates is a list of the hosts they belong to, so a version of it that
  may not name them has nothing to show. The screen states that names will be shown and saving is
  the agreement, which is recorded as the same stored yes the switch records — so one rule reads
  consent back for all three types. For the selection type a **non-empty** selection is the
  consent, and an empty one is not: that second half is what stops "the selection is the consent"
  from becoming "this type may always name monitors". An empty selection cannot be saved either.
- **A certificates widget names TLS hosts specifically**, which is a narrower and more useful
  disclosure than a list of failures: it says which of the user's machines terminate TLS and when
  each certificate runs out. That is topology in the same sense a host name is, and it is why this
  type asks rather than assumes, and why its wording attributes everything to the source instead
  of claiming it.
- **A list widget without a stored consent draws no names at all.** It is handed the nameless
  model and shows what a status widget with nothing to report shows. That is the honest outcome
  rather than a contradiction: such an entry has neither a selection nor a yes behind it.

Four tests carry the weight the boundary used to carry. They are listed because the measure is
only as good as they are:

1. The field list of the nameless render model is asserted, so gaining a name field has to be a
   decision made in review.
2. The pipeline is run for a widget **without** consent against an anonymised recording of a
   running installation, and each of that recording's 35 monitor names is asserted to appear
   neither in the model nor in its textual form.
3. The same pipeline is run **with** consent and the names must appear. Without this half, the
   test above could pass because the pipeline is broken rather than because it is careful.
4. On a device, the built hierarchy is inflated and every text and content description in it is
   collected and checked against the same 35 names, and the same is done after a round trip
   through the serialisation a host receives. This is the test that matters: that hierarchy is
   the thing that actually crosses into another process, and the ones above only describe what
   was intended to be put into it.

The fourth test asks two different questions since the title line was given to the first affected
item, and the second one is the harder of the two. Without consent it still asks whether **any**
of the 35 names appears, in any step. With consent, in the two steps that have no line for a list,
it asks whether **exactly one** appears and whether it is the right one: the name the title line
was given, in that line, and no name in any other view. Counting is done per view rather than per
name, because names in a real installation are substrings of one another — a shorter name
contained in the one on display appears too, and in the same string. A second *view* carrying a
name is the finding; a second match inside the one line that is supposed to carry one is not.

**A further test, outside those four, is what made the claim above true.** All four build the
hierarchy for a named size step and then examine it, which answers what a step carries and says
nothing about which step a box is given — and that is where the behaviour diverged from this
document. The additional test names no step: it hands the platform the whole set, lays it out in a
real host view at each box a host may offer, and reads out of the inflated hierarchy which variant
arrived. It was run against the unfixed code and failed `[V]`. Without it, the four tests above
would keep passing on a widget nobody can obtain.

Two things about those tests are worth recording, because both were corrections `[V]`:

- **The recording describes only working monitors**, and a status widget names the ones that are
  not working. Run against it unchanged, the tests asserting that no name appears would have
  passed because there was nothing to name — proving nothing at all. Every item is therefore
  treated as failed, which is the one condition under which a name could reach a widget.
- **The counter-check caught a test, not the code.** The first version of the device test built a
  model with no data in it; the widget then reports that it is loading and draws neither the
  counts nor the names, and the half asserting that names *do* cross the parcel failed for a
  reason that had nothing to do with the boundary. A suite whose negative halves pass while its
  positive half is broken is a suite reporting care it never exercised.

### What the configuration step exposes, and what it does not

The step that asks these questions is an **exported activity**, and it has to be: the party that
starts it is the host launcher, in another process, and an activity a launcher cannot start is a
configuration step that never runs. Exported means any application on the device can start it,
with any identifier in the intent.

What that could achieve is narrow and is named rather than waved away. The screen writes one entry
into WIDGORA's own per-widget settings file, keyed by the identifier it is handed. An arbitrary
identifier would therefore write an entry for a widget belonging to somebody else — or for no
widget at all, which the orphan sweep collects on the next update. It is noise rather than harm,
and it is still a write on behalf of a caller with no business asking for one, so the identifier
is **checked against the widget manager rather than believed**: it has to name a widget whose
provider is in this package and of a type this version recognises. A provider of this package
that this version has no mapping for is refused as well, deliberately — the screen's questions
depend on the type, so an unknown type means it does not know what it is asking about, and
defaulting to the one type that exists would be guessing.

What is **not** reachable through it is a property of the screen rather than of that check, and
that is the more important half:

- There is no address, no credential and no monitor name on it. The questions are which instance
  — by the name the user gave it — and whether names may appear.
- A widget's settings have no field for a credential or an address, which a test asserts, so
  there is nothing for a write to put one into.
- No text field exists on the screen at all, so nothing typed can be captured and no keyboard is
  involved.

The result contract is part of the protection rather than only of the behaviour: `RESULT_CANCELED`
is set before anything that could fail, and `RESULT_OK` is sent only after a write that reported
success. A host is never told a widget is configured while nothing reached the file.

**What the shipped package actually exports**, read out of the merged release manifest rather
than inferred from the source `[V]`:

| Component | Exported | Gate |
|---|---|---|
| `MainActivity` | yes | none — it is the launcher entry |
| `widget.config.WidgetConfigActivity` | **yes** | none; the identifier is checked instead, as above |
| `widget.CountsWidgetProvider` | no | — |

The three further exported components are the ones a library contributes, each behind a permission
an ordinary application cannot hold, and they are listed earlier in this section. The widget host
of the debug build contributes nothing: `BIND_APPWIDGET` does not appear in the release manifest
at all, and neither does the host activity.

### Certificate data is topology as well

How long a certificate still has is not a secret in itself, but the list of monitors that have
one is: it says which of the user's hosts terminate TLS, which is the same kind of statement as a
name. It is therefore treated the same way — it lives in the application-internal item model, it
is never part of what the deferred bridge would carry, and it reaches a widget only under the
consent rule above.

Two properties of the source make a certificate figure a claim that has to be qualified, and both
are design constraints rather than trivia `[V]`:

- The source **never clears** such a figure. It is removed when the monitor is deleted and not
  otherwise, so a number can outlive the check that produced it. A certificate figure is
  therefore never shown without the time the data was obtained, and an item whose own state is
  not active is marked beside its figure.
- Its validity flag is written as "no" for everything that is not an explicit yes, a missing
  statement included. It is rendered as "not valid as far as the source says" and never as
  "expired", because only the first of those two is supported by what was received.

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

**Status: the bridge is deferred.** The route to a host launcher is a standard Android app
widget instead, and the rules protecting that route are written down together with the code that
implements it. The four layers below describe the deferred design. They are kept because a
return to the bridge would need them, and they describe nothing that exists.

Two consequences are worth stating here now, because they change what has to be protected rather
than merely postponing it:

- **Consent stops being WIDGORA's to manage.** Placing a widget is an act the user performs in
  the host, in the foreground, through the system. The pre-authorisation and deferred-consent
  mechanisms of layer 2 have no equivalent on that route and need none, and the background
  activity-start restriction that shaped them does not arise.
- **A host sees whatever a widget displays.** A `RemoteViews` hierarchy carries its text into
  the host's process. Layer 1 — exposing very little — therefore becomes the only layer carrying
  weight, and it has to hold **without** the module boundary described in section 3, which made
  monitor names structurally unreachable from the bridge. A widget provider lives in the
  application module and can reach them. What takes the place of that boundary is now implemented
  and written down in section 3, under "A widget may name a monitor only by consent": two separate
  render models, a consent token that can only be made from a stored yes, consent given per placed
  widget, and four tests. It is weaker than the boundary it replaces, and section 3 says so in as
  many words.
- **One component of this route is exported, and it is not the widget.** The widget provider is
  not exported; the configuration step is, because a launcher has to be able to start it. What
  that opens, what is checked, and what the screen does not hold in the first place are in
  section 3, under "What the configuration step exposes, and what it does not".

The layers are listed in order of how much weight they carried. The strongest does not involve
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
- a response that describes the same item twice is reconciled rather than trusted or discarded
  `[V]`: values the rows agree on are used, and a contradiction is resolved towards the safe
  answer, never the convenient one. A contradictory status becomes `unknown`, which is displayed
  as its own state and never counted as working, because the one outcome that must not be possible
  is an outage silently presented as healthy. Contradictory numbers are dropped rather than
  averaged or picked from. What a response says about itself does not decide this; the reader
  does.
- the explanation shown for such a contradiction carries nothing the service wrote `[V]`. It is
  assembled from the application's own resources — a neutral sentence chosen by a condition, plus
  one sentence belonging to the integration — so no response text, no monitor name and no host name
  can reach the screen through it.

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

The repository is private and no artefact has been released, so there is nothing deployed to
report against yet. Once a repository is published, security reports should be made privately
through its advisory mechanism rather than in a public issue. This section will be updated with a
concrete contact path at that time `[O]`.

---

## 10. Open security questions

| Question | Marker |
|---|---|
| Whether deferred consent is workable in practice, given a host shows nothing until approval | `[U]` |
| ~~What replaces the module boundary that kept monitor names unreachable, now that a widget provider can reach them~~ — **implemented**, see section 3: two render models, a consent token that can only be made from a stored yes, consent per placed widget, four tests green on an emulator. Weaker than the boundary, and recorded as weaker | `[V]` |
| Whether the consent question reads as a question about privacy on a television, where the line explaining it competes with a remote control and a sofa | `[U]` |
| Whether a user who allowed names expects one of them in the widget's **title** line, now that the two smaller size steps put it there rather than showing no name at all. The consent screen says so in words — "in a small widget, the first of them takes the place of the title" — because "may names appear" and "may a name replace the title" are not obviously the same question; whether that sentence is read and understood from a sofa is what remains open | `[U]` |
| Whether a host launcher on the target device honours `RESULT_CANCELED` by removing the placement, which is what the step relies on for every way out that is not a successful save | `[U]` |
| Whether a host that applies no padding of its own reaches the naming step in a smaller box than the largest declared one, now that the threshold is expressed as the size a widget is offered rather than the box a host announces. Functionally harmless — the step was measured to fit in the smaller of those two figures — but it means the list of names can appear in a box this project has not pictured it in, and only a host that behaves that way would show it | `[U]` |
| Whether the exported configuration activity is reachable in practice by another application on the target device, and whether anything but a launcher ever starts it | `[U]` |
| Whether a user reading the configuration screen understands that choosing monitors for the third widget type is what allows their names to be displayed in it | `[U]` |
| Whether a certificates widget's consent is understood as given by pressing save, there being no switch to turn on — and whether the sentence stating it is read on a television at all | `[U]` |
| Whether showing which hosts terminate TLS, and when each certificate runs out, is a disclosure a user would weigh differently from a list of failing monitors, given both are granted by one agreement per widget | `[O]` |
| Whether the keyboards a television ships with withhold suggestions and dictionary learning for every field this application declares as a password field, which is the behaviour those fields rely on rather than something the platform enforces | `[U]` |
| Whether notifications are reliably visible on Android TV as a consent prompt | `[U]` |
| Deprecation status of the Jetpack security-crypto library before any use | `[U]` |
| Whether per-instance certificate pinning is comprehensible enough for non-technical users | `[U]` |
| Whether a pairing secret should be added if the bridge ever exposes more than display values | `[O]` |
| How a future companion configuration path authenticates, if one is introduced | `[O]` |
