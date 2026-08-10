# ADR-018: UUID Version 7 Primary Keys Generated Through an Application Port

**Status:** Accepted
**Date:** 2026-08-05
**Deciders:** @tech-lead, @db-designer

## Context

EOP-10 adds the first two tables whose rows are inserted at runtime: `game_session`
and `player`. Everything in the schema until now has been reference data. The
`card` table is seeded by a migration, and its identifiers are **deterministic
UUIDv5** values derived from a fixed namespace
(`db/changelog/changes/002-real-deck.xml`), precisely so that re-running the seed
produces the same rows. That is the correct choice for static data and it is not a
precedent for anything inserted while the application is running.

So there is no existing convention to follow, and one is about to be established
by accident. EOP-14 adds `trick` and `trick_play`, EOP-15 adds scoring rows, and
whatever EOP-10 does first is what those will copy.

A standard does already exist in the repository, but not where it binds. The
db-designer agent persona lists "standardize on UUID v7 or auto-increment BigInt"
as a non-negotiable rule (`.opencode/agents/db-designer.md`). Being
persona-scoped, it applies only when that agent happens to do the work. No ADR
mentions UUID at all — the string does not appear in ADR-002 through ADR-017, nor
in `.opencode/rules/database.md`. A rule that binds one contributor and not the
codebase is not a rule, and this ADR closes that gap.

**The usual argument for v7 does not apply at this scale, and pretending
otherwise would be dishonest.** The B-tree locality benefit of time-ordered keys
matters when a table takes millions of random-ordered inserts and the index no
longer fits in memory. This application will hold single-digit thousands of
`player` rows over its entire life, at three to six per session. Index page splits
are not a problem this project has, and will not become one.

## Decision

**UUID version 7 for every runtime-inserted primary key, generated in the
application through a port declared in the use case layer.**

### Why v7 rather than v4, given the performance argument does not apply

Three reasons that survive the scale check.

**One convention, not two.** With no decision recorded, the next contributor picks
v4 or v7 by coin flip, and the schema ends up with both. Two identifier schemes in
one database is a question every future reader has to ask and nobody can answer.

**Debuggability.** Reconstructing what happened in a session from a database dump
is a real activity for this project — the whole point of persisting state is to
inspect and resume it. Sorting by primary key shows insert order. With v4 that
information exists only in `joined_at` and `created_at`, which are correct but are
data rather than structure, and which a `SELECT *` does not order by.

**It removes a decision from every future story.** The value of a written standard
is not that v7 is dramatically better; it is that nobody has to think about it
again.

### Generated through a port, not by Hibernate at flush time

`IdentifierGenerator` is declared in `org.maglez.eop.usecase` as a single-method
port returning a `UUID`. The implementation lives in
`org.maglez.eop.adapter.persistence` and delegates to Hibernate's
`org.hibernate.id.uuid.UuidVersion7Strategy` (Hibernate ORM 7.4.1, already on the
classpath via Spring Boot 4.1 — no new dependency). Tests substitute a
deterministic fake.

This shape follows from a domain rule rather than from a preference for ports. The
existing `Card` entity validates a non-null identifier in its constructor, and
`GameSession` and `Player` will do the same. An entity whose identifier is
populated later cannot enforce that, so the domain would have to tolerate a
transient null-id state that exists purely because of when the persistence
framework happens to assign values. The use case asks for an identifier before
constructing the entity, the entity is valid from the moment it exists, and the
domain layer keeps zero framework imports.

### Generation is application-side, not database-side

A `DEFAULT uuidv7()` column would be the least code. It is not available:
`uuidv7()` arrived in PostgreSQL 18 and the deployed image is
`postgres:17-alpine`, and H2 — which the test suite runs against — has no
equivalent at any version. Since every migration in this project must be valid on
both engines, a database-side default is not portable. The migration is in fact
**identical** whichever version is generated: `type="UUID"` in Liquibase, native
`uuid` on PostgreSQL, `UUID` on H2. This is entirely an application-layer choice.

### Rejected alternatives

**`@UuidGenerator(style = VERSION_7)` on the JPA `@Id` field.** Fewer lines, but
it assigns the identifier at flush, which forces exactly the nullable-id domain
state described above and pushes the decision into the persistence annotation
where the use case layer cannot see it or test it.

**A hand-rolled `UuidV7` class in the entity package.** Roughly forty lines of RFC
9562 §6.2 bit manipulation. The failure mode is the objection: a subtle error in
the sub-millisecond counter still produces valid-looking UUIDs that have silently
lost their ordering, so the bug is invisible until someone relies on the sort. The
Hibernate implementation already handles monotonicity within a millisecond, which
is the part that is easy to get wrong.

**Auto-increment `BIGINT`.** Allowed by the persona rule and better on every
performance axis. Rejected because a session identifier appears in a URL that
players are given, and sequential integers there invite enumeration of other
sessions — a URL is not a credential, but it should not be a directory listing
either.

## Consequences

**Positive:** one identifier convention for every runtime-inserted row, written
down where it binds the codebase rather than one agent persona. `trick`,
`trick_play` and scoring tables inherit it without a new discussion.

**Positive:** identifiers are assigned by code the use case layer owns, so tests
can make them deterministic. A test asserting a specific sequence of identifiers
does not need a database or a Hibernate session.

**Negative — a v7 identifier leaks its own creation timestamp.** Anyone holding a
`sessionId` can read, to the millisecond, when that session was created. This is
immaterial here: session identifiers appear in shareable URLs and are not secrets,
and the two values that *are* secrets — the join code and the identity token — are
neither of them UUIDs (ADR-019, ADR-015). It is recorded so that nobody later
treats a v7 identifier as a capability, and so that any future table holding
something sensitive revisits this rather than inheriting it.

**Negative — the port calls into an undocumented corner of Hibernate.**
`UuidVersion7Strategy.generateUuid` takes a `SharedSessionContractImplementor`
that the v7 implementation ignores, so the adapter passes `null`. That is
behaviour observed from the bytecode, not a documented contract, and a future
Hibernate version could dereference it. Mitigated by a unit test asserting the
version nibble is 7 and that successive values increase, so a dependency upgrade
fails loudly in the build rather than silently downgrading identifier quality in
production.

**Negative — the stated benefit is modest.** This ADR openly does not claim a
performance win, because at a few thousand rows there is none to claim. It buys
consistency and debuggability. A reader looking for a scaling justification should
not find one and should not add one later.

**Neutral — `card` keeps its UUIDv5 identifiers.** Deterministic identifiers are
right for seeded reference data whose migration must be idempotent, and rewriting
a merged changeset is forbidden in any case. The two schemes coexist for a
reason that can be stated in one sentence, which is the test this ADR applies to
mixed conventions.

## Related

- [ADR-019](ADR-019-session-lifecycle-and-join-codes.md) — the story that needed this decision; join codes and tokens are deliberately not UUIDs
- [ADR-008](ADR-008-database-migration-liquibase.md) — Liquibase is the only authority on schema; `type="UUID"` is portable across both engines
- [ADR-012](ADR-012-deployment-target.md) — `postgres:17-alpine`, which is why `uuidv7()` is unavailable server-side
- [PRD §5](../requirements/PRD-eop-card-game.md) — the domain model whose tables this governs
- `.opencode/agents/db-designer.md` — where this standard previously existed, bound to one persona instead of the codebase
- EOP-10 (first runtime inserts), EOP-14 and EOP-15 (inherit the convention)
