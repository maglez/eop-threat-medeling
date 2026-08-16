# ADR-038: Pointer-Event Drag-and-Drop for Card Play

**Status:** Accepted (EOP-60, 2026-08-16)

**Date:** 2026-08-16

**Deciders:** Miguel González

## Context

The game screen requires playing a card onto a virtual table. Three options were
considered: the HTML5 Drag and Drop API, a library (dnd-kit / react-dnd), and
hand-rolled Pointer Events.

**HTML5 DnD** is not usable here: it has no touch support on mobile Safari, its drag
image cannot be styled to match GOV.UK card faces, and its event model is notoriously
inconsistent across browsers. `DataTransfer` serialises the payload into a string, which
creates a cross-document drag channel that is unnecessary and undesirable.

**A library** (dnd-kit, react-dnd) would add a dependency (~30–50 kB) and, more
importantly, ships its own focus and ARIA model that must be reconciled with the GOV.UK
Design System required by ADR-009. Both libraries also abstract away the pointer event
model in ways that make the keyboard fallback harder to reason about independently.

**Pointer Events** unify mouse, touch and pen in one event stream and are supported by
every browser in the project's support matrix. They require no dependency and stay inside
the GOV.UK focus model.

## Decision

Card drag-and-drop is implemented directly on Pointer Events, with no drag-and-drop
library.

1. `onPointerDown` calls `setPointerCapture`, so the drag survives the pointer leaving
   the card's bounds without a document-level listener.
2. The drop target is hit-tested with `getBoundingClientRect()` against the drop zone ref
   on `pointerup`. There is no droppable registry.
3. The drag ghost is a positioned element marked `aria-hidden="true"`; it is decoration,
   never the accessible representation of the card.
4. **Drag is an enhancement, never the only route.** Every card is a `role="button"` with
   `tabIndex`, `aria-pressed` and an Enter/Space handler, and a "Play selected card"
   button provides a keyboard- and screen-reader-complete path. A change that makes
   dragging the sole way to play a card is a regression, not a refactor.
5. `onPointerCancel` must reset drag state. Without it an interrupted gesture (an incoming
   call, a browser-level swipe) leaves the card stuck mid-drag.

## Consequences

- No new dependency; the interaction stays inside the GOV.UK focus model.
- Pointer geometry is ours to maintain: multi-touch is not handled, and adding a second
  drop target means extending the hit-test rather than registering a droppable.
- The keyboard path is the contract. It must be tested independently of the pointer path,
  since automated pointer-gesture tests in jsdom cannot prove accessibility.
- If a second draggable surface is introduced, revisit this decision: the hit-test
  approach does not scale to several drop zones and a library becomes the cheaper option.
- WCAG 2.2 SC 2.5.3 (Pointer Cancellation) is satisfied: `pointercancel` resets state
  and no action is committed on `pointerdown` alone.
- WCAG 2.2 SC 2.4.7 (Focus Visible, Level A) and SC 1.4.11 (Non-text Contrast, AA) are
  satisfied: `CardFace` carries `className="eop-card"` and tracks focus state via
  `onFocus`/`onBlur`. When focused, the inline `boxShadow` is set to the GOV.UK pattern
  (`0 0 0 3px #ffdd00, inset 0 0 0 2px #0b0c0c`) — yellow outline plus dark inset companion —
  which meets the ≥3:1 contrast requirement of SC 1.4.11 against the white card background.
  The `outline: none` suppressor was removed in EOP-60 and the CSS `.eop-card:focus` rule
  provides a fallback outline for browsers that do not execute JavaScript.
- WCAG 2.2 SC 2.5.7 (Dragging Movements, AA) is satisfied: every card is a
  `role="button"` with a click handler and a "Play selected card" button provides a
  complete single-pointer alternative to dragging.
