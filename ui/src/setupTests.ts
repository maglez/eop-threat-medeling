import "@testing-library/jest-dom/vitest";

// jsdom implements neither `PointerEvent` nor the pointer-capture methods, while every
// browser we support implements both. Without these shims `fireEvent.pointerDown` and
// friends fall all the way back to a bare `Event`, which silently drops `clientX` /
// `clientY` / `pointerId` from the init object — so any handler doing hit-testing against
// `getBoundingClientRect()` compares `undefined` and fails for reasons that have nothing
// to do with the code under test. Shimming here rather than per test keeps the whole suite
// behaving like a browser, and keeps the deception surface small: these are the only two
// gaps papered over, and both are genuine jsdom omissions rather than product behaviour.

if (typeof window.PointerEvent === "undefined") {
    class PointerEventShim extends MouseEvent {
        public readonly pointerId: number;

        public readonly pointerType: string;

        public readonly isPrimary: boolean;

        public constructor(type: string, params: PointerEventInit = {}) {
            super(type, params);
            this.pointerId = params.pointerId ?? 0;
            this.pointerType = params.pointerType ?? "mouse";
            this.isPrimary = params.isPrimary ?? true;
        }
    }

    window.PointerEvent = PointerEventShim as unknown as typeof window.PointerEvent;
}

if (typeof Element.prototype.setPointerCapture === "undefined") {
    Element.prototype.setPointerCapture = function setPointerCapture(): void {
        /* no-op: jsdom has no pointer-capture implementation to delegate to */
    };
    Element.prototype.releasePointerCapture = function releasePointerCapture(): void {
        /* no-op */
    };
    Element.prototype.hasPointerCapture = function hasPointerCapture(): boolean {
        return false;
    };
}
