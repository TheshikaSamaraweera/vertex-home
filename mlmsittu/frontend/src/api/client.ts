/**
 * The single place this app talks to the backend.
 *
 * Two things it exists to get right:
 *
 * 1. **Errors arrive as RFC 9457 problem documents.** The backend guarantees a machine-readable
 *    `code` on every one. This turns that into a typed `ApiError` so screens can branch on
 *    `error.code === 'INSUFFICIENT_STOCK'` and never on message text, which is for humans and will
 *    be translated.
 * 2. **The session is a cookie.** `credentials: 'include'` sends it; nothing here reads or stores
 *    a token, because there isn't one. The cookie is HttpOnly and JavaScript cannot see it — which
 *    is the point.
 */

/** A deliberate error from the API. Always carries `code`. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly detail: string;
  /** Extra problem members: `shortfall`, `itemId`, `errors`, and so on. */
  readonly properties: Record<string, unknown>;

  constructor(status: number, code: string, detail: string, properties: Record<string, unknown>) {
    super(detail || code);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = detail;
    this.properties = properties;
  }

  /** Field-level messages from a `VALIDATION_FAILED` response, if present. */
  get fieldErrors(): Record<string, string> {
    const errors = this.properties.errors;
    return errors && typeof errors === 'object' ? (errors as Record<string, string>) : {};
  }

  get isUnauthenticated(): boolean {
    return this.status === 401;
  }
}

/** Raised when the network itself failed — no response, so no `code` to branch on. */
export class NetworkError extends Error {
  constructor(cause: unknown) {
    super('Could not reach the server. Is the backend running on port 8080?');
    this.name = 'NetworkError';
    this.cause = cause;
  }
}

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined | null>;
  signal?: AbortSignal;
  /** Extra request headers. Used for `Idempotency-Key`, which describes the request, not the body. */
  headers?: Record<string, string>;
};

function buildUrl(path: string, query?: RequestOptions['query']): string {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value));
    }
  }
  const queryString = params.toString();
  return queryString ? `${path}?${queryString}` : path;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, signal, headers } = options;

  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      // Same-origin in both dev (Vite proxy) and production (Cloudflare routing), so this is
      // really just belt and braces — but it is the line that would matter if that ever changed.
      credentials: 'include',
      headers: {
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new NetworkError(cause);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const payload: unknown = text ? safeJsonParse(text) : undefined;

  if (!response.ok) {
    throw toApiError(response.status, payload);
  }

  return payload as T;
}

function safeJsonParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function toApiError(status: number, payload: unknown): ApiError {
  if (payload && typeof payload === 'object') {
    const problem = payload as Record<string, unknown>;
    const { type, title, status: _status, detail, instance, code, ...rest } = problem;
    void type;
    void title;
    void _status;
    void instance;

    return new ApiError(
      status,
      typeof code === 'string' ? code : `HTTP_${status}`,
      typeof detail === 'string' ? detail : 'Something went wrong.',
      rest,
    );
  }

  // A response that is not a problem document at all. Should not happen — the backend's
  // GlobalExceptionHandler covers every path — but guessing a shape would be worse.
  return new ApiError(status, `HTTP_${status}`, 'Something went wrong.', {});
}

export const api = {
  get: <T>(path: string, query?: RequestOptions['query']) => request<T>(path, { query }),
  post: <T>(
    path: string,
    body?: unknown,
    query?: RequestOptions['query'],
    headers?: RequestOptions['headers'],
  ) => request<T>(path, { method: 'POST', body, query, headers }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  // `delete` is a reserved word, so the method is `del`. The verb on the wire is DELETE.
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
};

/**
 * The frozen list envelope from P0-05.
 *
 * `nextCursor` is always null until Phase 7 fills it in. Parsing it here from day one is what
 * makes that a zero-change upgrade on this side — see P7-04, which checks precisely that the
 * frontend did not have to be rewritten.
 */
export type PagedResponse<T> = {
  data: T[];
  nextCursor: string | null;
};

export const unwrap = <T>(page: PagedResponse<T>): T[] => page.data;
