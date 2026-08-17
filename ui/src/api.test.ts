/**
 * Unit tests for subscribeToSession and dealCards in api.ts.
 *
 * subscribeToSession covers all six branches of the function:
 *  1. Non-ok response → onError(ApiError) with numeric status
 *  2. res.body is null → silent return (no callbacks)
 *  3. Stream ends cleanly (done === true) → no callbacks
 *  4. data: frame present → onEvent() called
 *  5. AbortError suppressed on teardown (abort() called)
 *  6. Non-AbortError network error → onError(Error)
 *
 * dealCards covers:
 *  1. 204 No Content → resolves void
 *  2. Non-ok response → throws ApiError with correct status
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { subscribeToSession, dealCards, ApiError } from './api';

// Helper: build a minimal Response-like object
function makeResponse(
  ok: boolean,
  status: number,
  body: ReadableStream<Uint8Array> | null,
  jsonFn?: () => Promise<unknown>,
): Response {
  return {
    ok,
    status,
    statusText: ok ? 'OK' : 'Error',
    body,
    json: jsonFn ?? (() => Promise.resolve({})),
    headers: new Headers(),
  } as unknown as Response;
}

// Helper: build a ReadableStream that yields one chunk then closes
function streamOf(chunk: string): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let sent = false;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (!sent) {
        sent = true;
        controller.enqueue(encoder.encode(chunk));
      } else {
        controller.close();
      }
    },
  });
}

// Helper: build a ReadableStream that never closes (simulates a live SSE connection)
function neverEndingStream(): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({ pull() { /* never resolves */ } });
}

describe('subscribeToSession', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('calls onError with ApiError when response is not ok (403)', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(false, 403, null, () =>
        Promise.resolve({ title: 'Forbidden', detail: 'The session has expired. Please start a new session.' })
      ))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    await vi.waitFor(() => {
      expect(onError).toHaveBeenCalledOnce();
    });

    const firstCall = onError.mock.calls[0];
    expect(firstCall).toBeDefined();
    const err = firstCall?.[0] as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(403);
    expect(err.message).toBe('The session has expired. Please start a new session.');
    expect(onEvent).not.toHaveBeenCalled();

    controller.abort();
  });

  it('calls onError with ApiError when response is not ok (404)', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(false, 404, null, () =>
        Promise.resolve({ title: 'Session not found', detail: 'No session matches that join code.' })
      ))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    await vi.waitFor(() => {
      expect(onError).toHaveBeenCalledOnce();
    });

    const firstCall404 = onError.mock.calls[0];
    expect(firstCall404).toBeDefined();
    const err = firstCall404?.[0] as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(404);
    expect(onEvent).not.toHaveBeenCalled();

    controller.abort();
  });

  it('does not call onEvent or onError when res.body is null', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(true, 200, null))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    // Give the async run() time to complete
    await new Promise(resolve => setTimeout(resolve, 50));

    expect(onEvent).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();

    controller.abort();
  });

  it('does not call onEvent or onError when stream ends cleanly with no data frames', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    // Stream yields a heartbeat comment (no 'data:' prefix) then closes
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(true, 200, streamOf(': heartbeat\n\n')))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    await new Promise(resolve => setTimeout(resolve, 50));

    expect(onEvent).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();

    controller.abort();
  });

  it('calls onEvent when a data: frame arrives in the stream', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(true, 200, streamOf('data: session-updated\n\n')))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    await vi.waitFor(() => {
      expect(onEvent).toHaveBeenCalledOnce();
    });

    expect(onError).not.toHaveBeenCalled();

    controller.abort();
  });

  it('suppresses AbortError when abort() is called (teardown path)', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    // Never-ending stream so we can abort mid-read
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(true, 200, neverEndingStream()))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    // Abort immediately
    controller.abort();

    // Give the catch handler time to run
    await new Promise(resolve => setTimeout(resolve, 50));

    expect(onError).not.toHaveBeenCalled();
    expect(onEvent).not.toHaveBeenCalled();
  });

  it('calls onError with a plain Error on unexpected network failure', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    const networkError = new TypeError('Failed to fetch');
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(networkError)));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    await vi.waitFor(() => {
      expect(onError).toHaveBeenCalledOnce();
    });

    const firstCallNet = onError.mock.calls[0];
    expect(firstCallNet).toBeDefined();
    const err = firstCallNet?.[0] as Error;
    expect(err).toBeInstanceOf(Error);
    expect(err.message).toBe('Failed to fetch');
    expect(onEvent).not.toHaveBeenCalled();

    controller.abort();
  });
});

describe('dealCards', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('resolves void when server returns 204 No Content', async () => {
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve({
        ok: true,
        status: 204,
        statusText: 'No Content',
        json: () => Promise.resolve({}),
        headers: new Headers(),
      } as unknown as Response)
    ));

    await expect(dealCards('session-1', 'token-abc')).resolves.toBeUndefined();

    const fetchMock = vi.mocked(fetch);
    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/sessions/session-1/deal');
    expect(options.method).toBe('POST');
  });

  it('throws ApiError with correct status when server returns non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve({
        ok: false,
        status: 409,
        statusText: 'Conflict',
        json: () => Promise.resolve({ title: 'Cards already dealt', detail: 'Cards have already been dealt for this session.' }),
        headers: new Headers(),
      } as unknown as Response)
    ));

    await expect(dealCards('session-1', 'token-abc')).rejects.toThrow(ApiError);

    try {
      await dealCards('session-1', 'token-abc');
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      const apiErr = e as ApiError;
      expect(apiErr.status).toBe(409);
    }
  });
});
