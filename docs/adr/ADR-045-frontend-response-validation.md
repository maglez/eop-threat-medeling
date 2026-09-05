# ADR-045: Response DTOs are parsed at the browser boundary by hand-written parsers, and a contract violation is a 502 `ContractViolationError`

**Status:** Accepted (amended 2026-08-19 by EOP-109 — §4 gained a follow-up note whose substance is that `TrickDto.ledSuit` was narrowed to `StrideCategory` and parsed through an `optionalEnum` helper, together with two scope calls that were **declined**: a `Rank` mirror, and a `capturedBySuit` key-type tightening — the latter because §2 bounds the violation *message* rather than forbidding a key check, so a narrowed index type would be unenforced rather than unenforceable)
**Date:** 2026-08-19
**Deciders:** @tech-lead, @architecture-guardian, @security-auditor, @ui-builder

## Context

Every JSON-returning fetch helper in `ui/src/api.ts` ended with a **type assertion**:

```ts
return (await response.json()) as SessionStateDto;
```

An assertion is a compile-time claim, not a runtime check. TypeScript unions are erased
when the bundle is emitted, so a payload carrying `role: "ROOT"` or `status: "WHATEVER"`
flowed into React state unchallenged, and every comparison against it silently evaluated
`false`. Ten helpers did this: `fetchCards`, `createSession`, `joinSession`, `getSession`,
`startGame`, `getLeaderboard`, `fetchHand`, `getTrickState`, `playCard`, `resolveTrick`.

EOP-105 made the enum *members* correct and added a build-time parity gate
(`EnumMirrorParityTest`) holding the Java enum, `docs/api/openapi.yml` and `ui/src/api.ts`
in three-way agreement. It also introduced four runtime-preserved mirrors with type guards
— `isPlayerRole`, `isSessionStatus`, `isConnectionStatus`, `isStrideCategory` — but wired
**none of them into production code**; they were exercised only by `ui/src/api.test.ts`.
@security-auditor graded the result MEDIUM/MAJOR: the server→browser trust boundary was
byte-for-byte as unvalidated after that change as before it.

The deferral was defensible on three grounds, all of which still hold and all of which
bound how much this ADR is allowed to claim:

1. **Every client-side comparison already fails closed.** Each production comparison is a
   *positive* test for a specific member (`role === 'FACILITATOR'`,
   `status === 'LOBBY' | 'IN_PROGRESS' | 'COMPLETED'`, `connectionStatus === 'CONNECTED'`),
   so an unknown value degrades to no privilege granted and no transition fired.
2. **Nothing on the client is authoritative.** There is no authentication (ADR-015), and
   the sole privilege decision is enforced in the domain entity (`GameSession.start` →
   `PlayerRole.canStartPlay()`), with the role derived server-side from the opaque player
   token and never submitted by the browser.
3. **A parse buys little against the strongest threat.** `ui/Caddyfile` serves the bundle
   and the API from one origin (ADR-017), so a server able to inject a bogus enum can
   already serve arbitrary JavaScript.

So this decision is not a security fix. It closes a **maintenance** hazard — four exported
guards that read as protection to the next maintainer while protecting nothing — and it
turns a class of silent misbehaviour (a comparison quietly evaluating false, forever) into
a loud, diagnosable failure. That is the honest justification, and the mechanism is chosen
to be proportionate to it rather than to an imagined attacker.

## Decision

### 1. Hand-written per-DTO parsers, inside `ui/src/api.ts`

Each response shape gets a `parseX(value: unknown): X` function in the same module as the
interface it validates. No schema library. No generated validators.

| Option | Verdict |
|---|---|
| **Hand-written parsers** (chosen) | ~12 shapes, one module, zero new dependencies. Consumes the four existing guards, so EOP-108's AC-3 is satisfied by *adoption* — nothing is duplicated and nothing is deleted. |
| Schema library (Zod, Valibot) | Rejected. `ui/package.json` has exactly **three** production dependencies (`react`, `react-dom`, `govuk-frontend`). Adding a fourth to the shipped bundle for twelve shapes is disproportionate, and EOP-110 is already open against six CVEs in the `ui/` dev toolchain — appetite for new supply-chain surface is at its lowest. The trade is genuinely close on ergonomics and this option should be re-opened if the shape count roughly doubles or if nested/recursive shapes appear. |
| Generate validators from `docs/api/openapi.yml` | Rejected here, but this decision *changes the balance* — see §4 and the ADR-009 amendment. Adopting it is a stack decision requiring a generator toolchain, not something to bolt onto this story. |

### 2. Bounded strictness — and the bound is part of the decision

A parser validates:

- the value is a non-null object (or an array where an array is contracted);
- every **required** field is present and of the contracted `typeof` (`string`, `number`,
  `boolean`);
- every **enum-typed** field passes its existing `is*` guard;
- every contracted array is actually an array, with each element parsed recursively;
- optional fields, when present, are validated; when absent, they stay absent.

A parser deliberately does **not** validate value *formats*: no UUID regex, no ISO-8601
date parsing, no join-code shape, no numeric range checks. That line is drawn on purpose.
Format validation would add code and test surface for no gain against any of the three
grounds above — the client neither authenticates nor authorises, and a malformed timestamp
renders as a bad string rather than escalating anything. Stating the bound explicitly also
stops the codebase from over-claiming: after this change the DTOs are **structurally and
enumerably** validated at the boundary, not "fully validated".

### 3. Error contract — `ContractViolationError extends ApiError`, `status = 502`

```ts
export class ContractViolationError extends ApiError {
  constructor(message: string) { super(502, message); this.name = 'ContractViolationError'; }
}
```

A parser that rejects **throws**. Three properties motivate this:

- **It fails closed.** A malformed payload never reaches React state, so the "comparison
  silently evaluates false" failure mode is eliminated rather than relocated.
- **502 is semantically exact.** An upstream response was invalid. Reusing
  `response.status` would *lie*: the server said `200`.
- **Zero component churn.** Every component already does
  `catch (e) { if (e instanceof ApiError) … }` and renders `e.message` through
  `ErrorSummary`. Subclassing keeps all of that working unchanged while remaining
  precisely assertable in tests.

Two alternatives were considered and rejected:

- **Degrade to a partial render** (drop the offending field, render the rest) — rejected: it
  reintroduces the exact defect, an out-of-contract value producing a plausible-looking UI.
- **A standalone error class not extending `ApiError`** — rejected: every existing
  `instanceof ApiError` catch would miss it, and an uncaught throw inside a `useEffect`
  surfaces as a blank screen.

The thrown message names the DTO and the offending field (for example
`SessionStateDto.status: expected one of LOBBY, IN_PROGRESS, COMPLETED, ABANDONED`). It
does **not** include the payload, so a response body cannot be laundered into a rendered
string.

**This binds map *keys* as well as values, and that is not a hair-split.** The keys of
`LeaderboardRowDto.capturedBySuit` are chosen by the server, so they are payload as much as
the values are. `requireNumberRecord` therefore does **not** delegate to `requireNumber`,
whose message interpolates the key it is handed: it validates each entry inline and reports
only the field — `LeaderboardDto.rows[0].capturedBySuit: expected every value to be a finite
number`. The first implementation of this ADR did delegate, and @security-auditor demonstrated
that a payload key such as `<img src=x onerror=alert(1)>` was reflected verbatim into a message
that renders into a GOV.UK `ErrorSummary`. It was not exploitable — React escapes the message
to a text node and no `dangerouslySetInnerHTML` exists anywhere in `ui/` — but a rule that says
"never the payload" must hold for keys, or it is a rule about values wearing a broader claim.
The cost is a coarser diagnostic: the message says which field, not which entry. That is the
right trade, and it is the second time this ADR chooses diagnostic precision away in exchange
for not echoing server-chosen strings.

`requireNumberRecord` also builds its result with `Object.create(null)` rather than `{}`, so a
`__proto__`, `constructor` or `toString` key in the payload cannot shadow an
`Object.prototype` member and leave the parsed record non-coercible for a future consumer.
No global pollution was possible either way — assigning a *number* to `__proto__` invokes the
inherited setter, which ignores non-object values — so this is defence in depth against a
consumer that does not exist yet rather than a fix for a live defect.

### 4. Consequential scope calls

- **`CardDto.suit` is narrowed from `string` to `StrideCategory`** and parsed with
  `isStrideCategory`. Parsing every enum around it while leaving this one asserted would be
  incoherent, and the guard already existed. This closes the first of EOP-109's three items;
  that ticket shrinks to its remaining two bare-`string` fields.
  > **Follow-up, EOP-109 (2026-08-19): those remaining items are now resolved, and only one of
  > them was a narrowing.** `TrickDto.ledSuit` was the last field whose contract schema `$ref`s a
  > mirrored enum while its type was bare `string`; it is now `StrideCategory`, parsed through a
  > new `optionalEnum` helper that delegates to `requireEnum` once a value is present, so
  > optionality weakens presence but never membership. The field is genuinely optional on the wire
  > (`TrickDto` is `@JsonInclude(NON_NULL)`; `ledSuit` is unset until the first card of a trick is
  > played), which is why `requireEnum` alone would have been wrong. `CardDto.rank` was **not**
  > narrowed, and that is an accepted drift rather than fidelity: the contract *does* `$ref` `Rank`
  > there, so the wide type is genuinely less specific than the contract. It is left wide because the
  > client never compares or orders a rank — the contract supplies `rankValue: integer` "used for
  > comparison", the card face renders from `rankSymbol`, and `card.rank` reaches exactly one
  > consumer, `cardImagePath(suit, rank)`, which returns `null` for anything it does not recognise
  > and whose every call site null-checks. An out-of-contract rank therefore degrades to a missing
  > image, not a wrong comparison. ADR-009's EOP-109 amendment records the matching rejection of a
  > `Rank` mirror and the evidence behind it. A tightening of `LeaderboardRowDto.capturedBySuit` to
  > `Readonly<Record<StrideCategory, number>>` was considered and declined, because
  > `requireNumberRecord` deliberately never inspects keys (§2's key-secrecy rule), so the narrower
  > index type would be an unenforced compile-time claim — the very thing this ADR exists to
  > remove. Every field whose contract schema is a *mirrored* enum is now both typed against that
  > mirror and membership-checked by a parser; `rank` sits outside that claim because `Rank` has no
  > mirror, not because `rank` is narrow.
- **`startNewGame` and `dealHands` gain no parser.** Neither reads a body — `dealHands`
  returns `204 No Content`. Adding a parser to a void helper would be theatre.
- **`subscribeToSession` gains no parser.** The SSE stream is a *doorbell*: a `data:` frame
  signals "state changed" and the caller re-fetches through `getSession`, which is parsed.
  No DTO is decoded from the stream, so there is no shape to validate.
- **ADR-009's codegen rejection is re-evaluated, not reversed** (EOP-108 AC-5). Its third
  revisit trigger — "runtime validation of responses is adopted (a parse rather than an
  assertion)" — has now fired, which is why the amendment exists. The trigger is
  acknowledged and codegen is still declined for the reason in §1: a generator is a stack
  decision, and the parsers it would replace are now written and tested. The amendment
  records that the *next* trigger to fire should tip the balance rather than be argued
  afresh.

## Consequences

**Positive**

- The four `is*` guards have production call sites. The seam EOP-105 built is now load-bearing.
- Out-of-contract enum values fail loudly at the boundary with a diagnosable message,
  instead of silently making every comparison false.
- Ten previously untested helpers gain coverage. Before this change `ui/src/api.test.ts`
  exercised only `subscribeToSession`, `dealHands` (then named `dealCards` — renamed by
  EOP-67) and the mirrors — the ten JSON helpers had no module-level tests at all.
- No new production dependency; bundle size effectively unchanged.

**Negative — stated plainly**

- **The parsers are hand-maintained, and nothing detects a missing field check — and because
  a parser *reconstructs* its object rather than passing the payload through, the
  consequence is worse than a missing check.** Each parser returns a fresh literal built
  only from the fields it reads, so a field added to a DTO interface but not to its parser
  does not merely go unvalidated: it is **dropped**, and arrives `undefined` at runtime
  while the interface still claims it is a `string`. A required field added this way
  typechecks everywhere and is absent everywhere. That is a strictly larger failure than the
  `as` assertion it replaced, which at least passed unknown fields through untouched. It is
  the same class of hazard as the enum drift EOP-105 fixed, one level down, and it is *not*
  covered by `EnumMirrorParityTest` — that test compares enum member lists, not DTO field
  sets. Enforcement is review only, and the `drops a field the server sends but no parser reads` case in `api.test.ts`
  pins the behaviour so it is at least documented rather than surprising. This is the
  strongest argument available for codegen and it is the trigger to watch.
- **This is not a security improvement**, and must not be cited as one. All three grounds
  for EOP-105's deferral still hold; a same-origin server that can inject a bogus enum can
  serve arbitrary JavaScript.
- **Strictness is bounded** (§2), so "the DTOs are validated at the boundary" is only true
  structurally and for enums. Formats are unchecked.
- **Test fixtures now have to be contract-complete.** Any test that stubs global `fetch`
  and returns a partial object will fail where it previously passed. That affects
  `CreateSessionForm.test.tsx`, `JoinSessionForm.test.tsx`, `LobbyScreen.test.tsx` and
  `api.test.ts`. Tests using `vi.spyOn(api, …)` — `GameScreen.test.tsx`,
  `GameOverScreen.test.tsx` — bypass the parsers entirely and are therefore *not* evidence
  that the parsers work. That asymmetry is a permanent property of spying on the API module.
- **A parse is one more failure mode in the happy path.** A server-side DTO change that is
  legitimate but unreleased on the client now breaks the screen with a 502 instead of
  degrading. That is the intended trade, but it makes client and server versions more
  tightly coupled than assertions did.

> **Amended 2026-09-05 (EOP-174).** A security audit of the full stack dismissed the
> identity token's presence in `sessionStorage` as an accepted design decision and cited
> *this* ADR as the authority. That citation is wrong, and the correction is recorded here
> so the next reviewer does not have to relitigate it.
>
> **Credential storage is outside this ADR's boundary.** What this ADR governs is one
> direction of one boundary: the shape of a response body arriving from the API, parsed in
> `ui/src/api.ts` rather than asserted. It says nothing about where the client *keeps* a
> credential, and the Decision above is explicit that even within its own scope it is not a
> security control — so it cannot be the authority for a security dismissal of anything,
> let alone of a question it never addressed.
>
> **The ADR that owns the question is [ADR-015](ADR-015-player-identity.md)**, whose
> 2026-08-20 amendment (EOP-107) re-examined `sessionStorage` against precisely this XSS
> exposure and upheld it, stating the exposure being accepted in those terms. The audit's
> conclusion was therefore right on the merits and wrong in its citation. Cite ADR-015 for
> credential storage; cite this ADR only for response parsing.
>
> Nothing in the Decision or the Consequences above changes. This amendment adds a scope
> boundary that was implicit and got misread.

## Related

- [ADR-009](ADR-009-frontend-react-typescript.md) — front-end stack; hand-maintained DTO
  mirror; codegen rejection, amended by this ADR (EOP-108 AC-5)
- [ADR-015](ADR-015-player-identity.md) — no authentication; opaque player token;
  why `fetch` rather than `EventSource`
- [ADR-017](ADR-017-frontend-delivery-topology.md) — single origin, hence no CORS and hence the
  bounded threat model in §2
- [ADR-022](ADR-022-agent-model-tier-governance.md) — review independence for the DoD gates
  that raised this defect
- `.opencode/rules/error-handling.md` — server-side RFC 9457 contract, extended by this ADR
  with the client-side boundary contract
- [`docs/architecture/C4-Diagrams.md`](../architecture/C4-Diagrams.md) — the Level-3 component
  view, where response validation is now listed as a wire concern `api.ts` owns, and the
  browser→API sequence, which gained the out-of-contract-`200` branch
- [`docs/architecture/runtime-view.md`](../architecture/runtime-view.md) — the `refreshSession`
  exit enumeration; this change adds a fourth *outcome* without adding a fourth
  `onSessionEnd()` trigger
- EOP-105 (enum mirror parity), EOP-108 (this change), EOP-109 (closed the last field typed bare
  `string` against a *mirrored* enum schema, `TrickDto.ledSuit`, and declined both a `Rank` mirror
  and a `capturedBySuit` tightening — see the follow-up note in §4), EOP-110 (`ui/` dev-toolchain
  CVEs). Read EOP-109's scope with its qualifier: `Card.rank` is still typed bare `string` against a
  `$ref` to `Rank`, deliberately, because `Rank` has no mirror **in `ui/src/api.ts`** — no `as const`
  array, no derived union, no `is*` guard, which is the only sense of "mirror" the gate recognises,
  and not a claim that no rank list exists anywhere in `ui/` — so that bullet does not mean "no
  contract enum is left wide"
