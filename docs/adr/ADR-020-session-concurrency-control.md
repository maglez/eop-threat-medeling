# ADR-020: Concurrency Control by Compare-and-Set on `status`, Not by Optimistic Locking

**Status:** Accepted (lock ordering decided 2026-08-12, corrected from a chain to a tree 2026-08-13 — see ADR-023)
**Date:** 2026-08-10
**Deciders:** @tech-lead, @architecture-guardian, @db-designer

## Context

EOP-10 is the first story in which two people can act on the same row at the same
moment. Everything before it was either reference data or a read.

The lobby is concurrent by nature, and not incidentally so. A join code is pasted
into a chat window and five people click it within the same second. That is the
normal case, not the stress case. Three interleavings have to be wrong-proof:

1. Two players joining while the facilitator presses **start**. Either both are
   seated before the lobby closes, or they are refused — never seated into a game
   that has already begun and already dealt around a table of a different size.
2. Two players joining in the same instant and computing the same next seat. Seat
   order is load-bearing domain data (ADR-019): if two players hold seat 3, turn
   order is undefined and the game is corrupt in a way no error message reports.
3. Two facilitators — or one facilitator double-clicking — starting the same
   session twice, emitting two `game-started` events for one transition.

The PRD carried this as open risk **R5**, and the risk text guessed at the answer:
"Optimistic locking is the likely answer; the semantics are not decided." The
semantics are now decided, and the guess was wrong. This ADR records what was
actually built, because the code contains a signpost pointing the other way.

**That signpost is the reason this document exists.** `GameSessionJpaEntity` maps

```java
@Version
@Column(name = "version", nullable = false)
private long version;
```

and `db/changelog/changes/003-session-lifecycle.xml` declares the matching column
as `version BIGINT DEFAULT 0`. A reader who finds a `@Version` field reasonably
concludes that JPA optimistic locking is the concurrency control. **It is not.**
Nothing in this repository handles `OptimisticLockingFailureException` or
`ObjectOptimisticLockingFailureException`; no method carries `@Lock`; no test
provokes a version conflict. Searching the tree for any of those strings returns
nothing. The column is real, it is maintained, and it is not the enforcement
mechanism — and a mapped annotation that looks like a control but is not one is
worse than no annotation at all, because it invites a maintainer to delete the
mechanism that *is* working on the grounds that the framework has it covered.

## Decision

**Concurrency is controlled by status-guarded conditional `UPDATE` statements —
compare-and-set on `status` — plus unique constraints. The `@Version` column is
retained as bookkeeping, not as a gate.**

### The mechanism: two conditional updates that report rows affected

> **Amended 2026-08-13, EOP-14 Slice C1 — there are now five.** The count in the
> heading and in the sentence below was true when written and is not any more.
> `claimDeal`, `touchWhileLeaderSeatIs` and `advanceLeaderSeat` join them, all three
> compare-and-set, all three hand-incrementing `version`, all three checked for rows
> affected at every call site. Two of them witness `current_leader_seat` rather than
> `status`, which is a widening of this decision recorded in *The deal-once gate*
> below. Read "two" here as "the first two". One caution: all five compare-and-set,
> but that does **not** make all five replay guards — `advanceLeaderSeat` is
> idempotent when the leader wins their own trick, and the section *One of the five is
> not a replay guard* records what serialises trick resolution instead.

`GameSessionJpaRepository` — package private, deliberately not the application's
port — declares exactly two writes:

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE GameSessionJpaEntity s SET s.updatedAt = :now, s.version = s.version + 1 "
     + "WHERE s.id = :sessionId AND s.status = :required")
int touchWhileInStatus(UUID sessionId, SessionStatus required, OffsetDateTime now);

@Modifying(clearAutomatically = true)
@Query("UPDATE GameSessionJpaEntity s SET s.status = :target, s.updatedAt = :now, "
     + "s.version = s.version + 1 "
     + "WHERE s.id = :sessionId AND s.status = :required")
int advanceStatus(UUID sessionId, SessionStatus required, SessionStatus target,
                  OffsetDateTime now);
```

Both are compare-and-set. The `WHERE` clause names the status the caller believes
the session to be in; the database applies the change only if that belief is still
true when the statement executes. The return value is the whole protocol: **one
means the caller's assumption held, zero means it did not.**

Note that both statements hand-increment `s.version`. The version column is
therefore maintained by this JPQL rather than by Hibernate's locking machinery,
which is the concrete reason the `@Version` annotation is not doing the work: an
explicit `UPDATE` bypasses the managed-entity dirty-check path entirely.

`clearAutomatically = true` is not decoration. A bulk `UPDATE` is invisible to the
persistence context, so any `GameSessionJpaEntity` already loaded in the same
transaction would hold a stale `status` and a stale `version`. Clearing forces the
next read to go to the database and prevents the adapter from making a second
decision on pre-update state.

### Serialisation rests on the row lock, not on the return value

The return value detects a conflict that has already been resolved; it does not
resolve one. What resolves it is that **the conditional `UPDATE` takes a row lock
on `game_session` and holds it to the end of the transaction.**

This is the load-bearing detail and the easiest to lose in a refactor.
`seatPlayer` calls `touchWhileInStatus` *first* and inserts the player *second*,
inside one `@Transactional` method. The `UPDATE` is not there to change anything
meaningful — bumping `updated_at` is a side effect. It is there to serialise
concurrent joiners on the parent row before any of them inserts a child row. Two
joins arriving together are ordered by the database: the first takes the lock, the
second waits, and by the time the second proceeds it is reading a world the first
has finished changing.

Removing `touchWhileInStatus` because "it only touches a timestamp" would remove
the serialisation point and leave case 1 above as a race. It is documented here
for that reason.

### Two further guarantees come from constraints, not from code

Correctness under concurrency is delegated to the schema wherever the schema can
express it:

| Constraint | What it makes impossible |
|---|---|
| `uq_game_session_join_code` | two live sessions sharing a join code |
| `uq_player_session_seat` on `(game_session_id, seat_order)` | two players holding one seat |
| `uq_player_identity_token_hash` | two players sharing an identity token digest |

`SessionRepositoryAdapter` writes optimistically and interprets the failure. It
catches `DataIntegrityViolationException`, walks the cause chain, and matches the
**constraint name** in the message text: `uq_game_session_join_code` becomes
`JoinCodeUnavailableException` (retry with a fresh code), `uq_player_session_seat`
becomes `SeatAlreadyTakenException` (retry with the next seat). Anything
unrecognised is rethrown unchanged, so a constraint added later fails loudly
instead of arriving as a silent retry loop.

Matching on a name in a message string is fragile, and it is chosen anyway: the
exception *type* is identical for every constraint, so the name is the only thing
that distinguishes "pick another code" from "pick another seat" from "something
genuinely broken". The alternative is a pre-insert `SELECT`, which narrows the
race window without closing it — the check and the insert are still two
statements. Letting the database pick the winner is what makes the outcome
correct rather than merely usually correct.

### What happens on zero rows affected

Zero is not an error from the statement's point of view. It is an answer: the
world moved between the caller's read and the caller's write. `SessionRepository`
must nonetheless report *why*, because the two explanations map to different HTTP
responses.

There are exactly two. Either the session no longer exists, or it is no longer in
`LOBBY`. `SessionRepositoryAdapter` disambiguates with **one additional read**:

```java
private RuntimeException noLongerInLobby(final UUID sessionId) {
    return sessionRows.findById(sessionId)
            .map(row -> (RuntimeException) new SessionNotJoinableException(sessionId, row.getStatus()))
            .orElseGet(() -> new SessionNotFoundException(sessionId));
}
```

`SessionNotJoinableException` becomes 409 with the status in the problem detail;
`SessionNotFoundException` becomes 404 (ADR-005, ADR-019). The extra read is
accepted without hesitation because it happens **only on a path that is already
failing**. The success path stays at one statement. Paying for precision only when
something has gone wrong is the correct place to spend it.

Both `seatPlayer` and `recordStarted` funnel through this one helper, so a
double-clicked **start** and a too-late join produce consistent answers.

### Why not a version-checked read-modify-write

The rejected design is the one the PRD guessed at and the one `@Version` implies:
load the aggregate, decide in Java, save, and let Hibernate append
`WHERE version = ?` and count the rows.

**It needs one more round trip, and the extra one is the dangerous one.** Load,
then update, is two statements with a window between them. Optimistic locking
closes that window by *detecting* the loss afterwards, which means the losing
request must be replayed to be correct. A conditional `UPDATE` closes it by never
opening it: the condition and the change are evaluated together, under one lock, in
one statement.

**It converts an ordinary lobby race into a retry loop.** Five people joining at
once is the expected case here. Under version checking, four of them collide and
must reload and retry, and the retry must reconstruct a seat number — which is
precisely where a `MAX(seat_order) + 1` recomputation would reintroduce the seat
collision the unique constraint exists to prevent. Under compare-and-set, the four
queue on the row lock and proceed in order, and no application-level retry exists
to get wrong.

**The domain would have to carry a version it has no use for.** The port takes and
returns `GameSession`, a domain aggregate with no framework imports (Clean
Architecture). Version-checked save means threading a version number out of the
adapter, through the use case, into the HTTP layer, back in on the next request,
and back down — an opaque token whose only purpose is to be handed back. That is a
persistence concern promoted to a wire concern.

**And it guards the wrong invariant.** Optimistic locking answers "did this row
change since I read it?" The question the lobby actually asks is "is this session
still joinable?" Those diverge: a heartbeat or a `updated_at` bump would fail a
version check while leaving the session perfectly joinable, and a status-guarded
update ignores it correctly. Guarding the invariant that matters produces fewer
false conflicts than guarding the row.

**Rejected: `SELECT ... FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)`.** Functionally
close, since the conditional `UPDATE` already takes the same lock. It costs an
extra statement and an extra round trip to acquire what the write acquires anyway,
and it puts the lock in an annotation where a reader must know JPA lock-mode
semantics to see it. The conditional `UPDATE` states its own guard in SQL.

**Rejected: `SERIALIZABLE` isolation.** It would make all three cases correct
without any of the above. Rejected because it converts every conflict into a
serialisation failure that the application must retry, which is the retry loop
objected to above, applied globally rather than locally. The default `READ
COMMITTED` plus explicit guards keeps the reasoning local to the two methods that
need it.

### `@Version` stays, and stays confined to one class

The column is kept. It is a cheap, monotonic, per-row change counter, useful when
reconstructing what happened to a session from a database dump — an activity this
project treats as real (ADR-018). Removing it would mean a migration to drop a
column that costs eight bytes and answers a genuine forensic question.

It is confined to `GameSessionJpaEntity` and **never crosses the port**. The
entity's own javadoc gives the reason, and it is the right formulation:

> The domain aggregate has no version field, because a version is a statement
> about a row rather than about a game.

`GameSession` has no `version`. No DTO exposes one. No response header carries one.
`PlayerJpaEntity` has none either, because players are inserted and read but never
contended-on as rows — their contention is settled by
`uq_player_session_seat` instead.

### The deal-once gate: a null column, not a count of rows — amended 2026-08-13, EOP-14 Slice C1

This decision is a widening of the one above and was made in code during Slice C1
without an ADR to carry it. @architecture-guardian's gate graded that a MAJOR
finding, correctly: the *rationale* was in a Javadoc comment
(`GameSessionJpaRepository.java:76-91`) and nowhere in `docs/`, and this ADR — which
owns session concurrency control, and which that same slice amended for lock
ordering — is where it belonged.

**Dealing is serialised on the session row, and `current_leader_seat IS NULL` is the
definition of "not yet dealt".**

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE GameSessionJpaEntity s SET s.currentLeaderSeat = :leaderSeat, "
     + "s.updatedAt = :now, s.version = s.version + 1 "
     + "WHERE s.id = :sessionId AND s.status = :required "
     + "AND s.currentLeaderSeat IS NULL")
int claimDeal(UUID sessionId, int leaderSeat, SessionStatus required,
              OffsetDateTime now);
```

The predicate does two jobs in one statement: it is the status compare-and-set this
ADR already mandates, *and* it is the deal-once gate. The first caller to set the
column wins; every subsequent caller matches zero rows and is refused with
`HandAlreadyDealtException`, a 409.

**The alternative was counting `hand` rows first, and it is strictly worse.** A
`SELECT COUNT(*) FROM hand WHERE session_id = ?` followed by an insert is a
check-then-act across two statements: two concurrent deals both count zero, both
proceed, and both insert. `uq_hand_session_seat` would catch the collision, so the
outcome is not corrupt — but it is a race decided by a unique-constraint violation
translated back into a conflict, which is exactly the pattern this ADR exists to
avoid. Counting first would only *narrow* the window, never close it, at the cost of
an extra round trip. The single conditional `UPDATE` closes it, because the row lock
serialises the two callers and the column's nullness is the state being contended.

**The cost, which is real:** `HandAlreadyDealtException` now has two distinct
origins. It arises from this gate, via `sessionMoved(sessionId, null)` when the
column is already set, and it arises from `uq_hand_session_seat` when a second deal
somehow reaches the insert. The constraint-name-to-exception translation table in
[ADR-023](ADR-023-deal-remainder-and-turn-order.md) covers only the second, and
structurally cannot cover the first, because the first is not a constraint violation
at all. A reader who uses that table as the complete inventory of how each exception
is raised will be wrong about this one. That is the price of putting the gate on the
session row, and it is worth paying; this paragraph is the mitigation.

**Why the session row and not the `hand` table.** The seat the deal opens on is
derived from the cards actually dealt (`Hands.openingLeaderSeat()`), so the deal has
to write `current_leader_seat` anyway. Making that same write the gate costs nothing
and means there is exactly one row whose lock serialises dealing — which is also the
row that serialises every other transition in the session, so the lock ordering in
the amendment below stays a tree rather than becoming a graph.

### One of the five is not a replay guard: trick resolution serialises on the trick row

Recorded 2026-08-13, EOP-14 Slice C1, in the same breath as the gate above and for
the same reason — @architecture-guardian's gate observed that this slice remediated
one decision-in-a-Javadoc while introducing another, and that the new one qualifies
the guarantee the subsection above asserts. It does, and the qualification belongs
here.

**Compare-and-set on the session row does not stop a trick being resolved twice.
The `winner_play_id IS NULL` predicate on the trick row does.**

`advanceLeaderSeat` is a compare-and-set like the other four, and the sentence above
that "one means the caller's assumption held, zero means it did not" is still true of
it. What is *not* true is the inference that it therefore refuses a replay. It sets
`current_leader_seat = :nextLeaderSeat WHERE ... current_leader_seat =
:expectedLeaderSeat`, so whenever the caller's next leader equals the leader it
witnessed the statement writes the value it just compared against and is
**idempotent**. A replayed resolution matches the row, changes one row, and passes
the guard.

That is not an edge case. It is what happens whenever the seat that led a trick also
wins it and still holds a card, which is an ordinary outcome rather than a rare one.

So the serialisation point for resolution is one statement further in:
`TrickJpaRepository.recordWinner` carries `AND t.winnerPlayId IS NULL`, and it is the
first statement that can distinguish a first resolution from a replay. Zero rows
there raises `TrickAlreadyResolvedException`, and so a 409. Until this slice it
raised `IllegalStateException`, and so a 500 — an ordinary replay of an ordinary hand
billed as a server fault, which is the defect that made this decision visible.

**Three consequences worth naming.** First, the two second-resolution shapes are
genuinely different and both are tested: when the lead *did* move, the session
compare-and-set refuses with `OutOfTurnException`; when it did not, the trick
predicate refuses with `TrickAlreadyResolvedException`. Neither subsumes the other.
Second, `TrickAlreadyResolvedException` joins `HandAlreadyDealtException` as an
exception whose origin is not a constraint violation, so it is a second thing
[ADR-023](ADR-023-deal-remainder-and-turn-order.md)'s constraint-name translation
table structurally cannot cover. Third, this is the one place in the design where the
contended row is *not* the session row, which is why it is recorded rather than left
to be rediscovered: a reader who takes "every write compare-and-sets the session row,
therefore every write is replay-safe" from the section above would be wrong here, and
wrong in the direction of removing the predicate that is actually doing the work.

## Consequences

**Positive:** the guard and the write are one statement, so the success path has no
window to lose a race in and no application-level retry to get wrong. The four
losers of a five-way join queue on a row lock and then succeed, rather than
failing and being replayed.

**Positive:** the two hardest invariants — one seat per player, one code per
session — are enforced by the database and cannot be broken by a later refactor of
the join logic. Someone rewriting `JoinSessionUseCase` without reading this ADR
still cannot double-book seat 3.

**Positive:** the failure vocabulary is honest. Zero rows affected is turned into
either 404 or 409 by one disambiguating read, so a client learns whether the
session vanished or merely moved on, and neither answer requires the client to
hold a version.

**Negative — a `@Version` field is mapped and does nothing that a reader expects
it to do.** This is the cost of the decision and it is the reason this ADR exists.
Mitigations are documentation only: the field's javadoc says so, and this record
says so. There is no compiler check that stops a future contributor from calling
`save()` on a managed `GameSessionJpaEntity`, silently getting Hibernate's version
check, and then writing an `OptimisticLockingFailureException` handler that
duplicates a mechanism already present. If that happens, the two should be
reconciled by a new ADR rather than by adding a second gate.

**Negative — conflict detection depends on matching constraint names in exception
messages.** `mentions(Throwable, String)` lowercases and substring-matches. A
database upgrade that reworded its integrity-violation message, or a rename of a
constraint in a migration without a matching change to the adapter's constants,
would turn a recoverable retry into a 500. This is mitigated by rethrowing
anything unrecognised — the failure is loud — but it is a real coupling between a
Java constant and a Liquibase `constraintName`, and the two must be changed
together.

**Negative — serialisation relies on a statement whose visible purpose is a
timestamp.** `touchWhileInStatus` looks like housekeeping and is in fact the lock
acquisition that orders concurrent joins. It is the single most deletable-looking
load-bearing line in the story.

**Negative — correctness is scoped to one process and one database, and only the
database half is real protection.** Nothing here depends on the application being
a single instance: row locks and unique constraints work identically across many.
That is deliberate, and it is why this ADR is not a scaling liability. The parts
that *are* process-local are elsewhere and are named as such — the SSE subscriber
registry and the join-attempt limiter (ADR-019).

**Neutral — no deadlock ordering policy is written down.** Only one table is
locked on the write paths in this story, so a lock cycle is not currently
constructible. EOP-14 adds `trick` and `trick_play` and will touch two tables in
one transaction, at which point a consistent lock-acquisition order becomes a real
decision. Recorded here so that story does not discover it by observing an
intermittent deadlock.

> **Amended 2026-08-12 — see [ADR-023](ADR-023-deal-remainder-and-turn-order.md).** The
> policy is now written down: `game_session`, then `trick`, then `trick_play`, parent
> before child in every write path. EOP-14 decided it ahead of the schema rather than
> after the first deadlock, so this consequence is discharged. The paragraph above is
> left as written, because its reasoning for why the question was still open at the time
> remains accurate — only its status has changed.

> **Amended 2026-08-13, EOP-14 Slice C1.** The policy is a tree, not the chain named
> above: `game_session` → `hand` → `hand_card` and `game_session` → `trick` →
> `trick_play` → `trick_play_component`, acquired left to right along every path, and
> where one transaction touches both branches the `hand` branch is taken first.
> [ADR-023](ADR-023-deal-remainder-and-turn-order.md) carries the diagram. EOP-14 Slice
> C1's `TrickPlayRepositoryAdapter.appendPlay` is the first code to walk both branches —
> it takes the session row, reads `hand`, deletes from `hand_card`, then inserts into
> `trick_play` and `trick_play_component` — so it is also the first place the difference
> between a chain and a tree could have mattered. The 2026-08-12 note above summarised
> the policy more narrowly than the policy it points at, which was harmless while no code
> walked the `hand` branch and is not harmless now.

**Neutral — this is settled per aggregate, not globally.** `game_session` is the
only contended aggregate today. EOP-14's card-playing path has a different shape
— "is it my turn, and do I hold this card" — and should re-derive its guard from
these principles rather than assume `touchWhileInStatus` generalises.

## Related

- [ADR-019](ADR-019-session-lifecycle-and-join-codes.md) — the story that needed this; seat order enforced by constraint, and the same 404/409 vocabulary
- [ADR-018](ADR-018-uuid-v7-identifiers.md) — identifiers minted in the use case before the entity exists, which is why no write path depends on a database-assigned key
- [ADR-008](ADR-008-database-migration-liquibase.md) — Liquibase owns the schema; `003-session-lifecycle.xml` declares the `version` column and all three unique constraints
- [ADR-005](ADR-005-error-handling-strategy.md) — where zero-rows-affected becomes an HTTP status
- [ADR-014](ADR-014-realtime-transport.md) — reconnect is a re-read, so every read is a database read and no cache can disagree with a conditional update
- [PRD §6 R5](../requirements/PRD-eop-card-game.md) — the risk this closes, including the guess it corrects
- EOP-10 (this story), EOP-14 (trick play — two tables, and therefore a lock-ordering decision)
