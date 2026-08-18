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
  (`0 0 0 3px #ffdd00, inset 0 0 0 2px #0b0c0c`) — yellow outer ring plus dark inset companion.
  The ≥3:1 contrast requirement of SC 1.4.11 is met by the dark inset (`#0b0c0c` at 19.59:1
  against the white card background `#ffffff`); the yellow alone is 1.35:1 and does not satisfy
  the criterion on its own. The inline `boxShadow` always wins over the CSS `box-shadow`
  declaration in `.eop-card:focus` (`GameScreen.css:200`), which is therefore inert; the CSS
  `outline: 3px solid #ffdd00` in that same rule (`GameScreen.css:198`) is a separate property
  and is always active, contributing an additional yellow ring alongside the inline shadow.
  The `outline: none` suppressor that previously removed focus visibility was removed in EOP-60.
- WCAG 2.2 SC 2.5.7 (Dragging Movements, AA) is satisfied: every card is a
  `role="button"` with a click handler and a "Play selected card" button provides a
  complete single-pointer alternative to dragging.

**No descendant of a drag surface may be natively draggable (EOP-79 amendment, 2026-08-18).**
This is a sixth invariant of the design above, added because violating it broke drag entirely
and decision 5 masked the failure rather than surfacing it. An `<img>` is implicitly
`draggable="true"` in every browser, so pressing on card artwork and moving started a *native*
HTML5 drag; per the HTML drag-and-drop processing model the user agent then **must** suppress
the pointer event stream for that pointer, which it does by firing `pointercancel`. Decision 5's
`onPointerCancel` handler dutifully cleared `dragState`, so `pointerup` never reached the
hit-test of decision 2 and no play was ever submitted — the card simply returned to the hand.
Note that neither `userSelect: 'none'` nor `touchAction: 'none'` suppresses native image
dragging; only `draggable={false}` (with `WebkitUserDrag: 'none'` for older WebKit) does.
The invariant binds every natively-draggable descendant of the element carrying the pointer
handlers, not merely the card that receives `pointerdown`: `handlePointerCancel` filters no
`pointerId`, so a native drag begun anywhere beneath that container aborts an unrelated drag
in flight. Both card images — the hand card in `CardFace` and the played card in the trick
zone — therefore carry `draggable={false}`. The drag ghost does not need it, because
`pointerEvents: 'none'` means it can never be a press target.

**`pointerdown` default action is now cancelled, which moves focus behaviour (EOP-79 amendment,
2026-08-18).** `handlePointerDown` calls `e.preventDefault()` before `setPointerCapture` as
defence in depth for the text-fallback card face and any future draggable child. Cancelling
`pointerdown` suppresses the compatibility `mousedown`, and it is `mousedown` that moves DOM
focus to a `div[role="button"]`. A mouse press on a card therefore no longer focuses it — often
desirable, but it means `onFocus` does not fire on click and a hybrid mouse/keyboard user tabs
from the document start rather than from the card just clicked. Decision 4's contract survives
intact: `click` is a `PointerEvent`, not a compatibility mouse event, so cancelling
`pointerdown` cannot suppress it and the select-then-play path still works. Keyboard focus via
Tab is unaffected, so SC 2.4.7 above still holds.

**Accepted limitation: this class of defect is not reachable by the automated suite (EOP-79
amendment, 2026-08-18).** The point above about jsdom and accessibility understated the problem.
jsdom implements no native HTML5 drag at all, so it cannot fire `dragstart` in response to a
pointer move and cannot reproduce the `dragstart` → `pointercancel` abort that constitutes this
bug. It also implements neither `PointerEvent` nor `setPointerCapture`, both of which are
shimmed in `ui/src/setupTests.ts` so that `fireEvent.pointer*` carries `clientX`/`clientY` at
all. Consequently the pointer-gesture tests in `GameScreen.test.tsx` cover the hit-test
arithmetic — real coverage that was previously absent, and whose absence is why EOP-79 escaped
— but they pass with or without the fix. The only automated guard on this regression is the
assertion that the rendered card `<img>` carries `draggable="false"`, and the same is true of
the focus consequence described above, which no test can currently observe. We accept this
gap rather than introducing a browser-driving test framework for one interaction: the invariant
is declarative and assertable as an attribute, and the residue is covered by manual
verification. Introducing Playwright or Vitest browser mode would supersede this paragraph.
