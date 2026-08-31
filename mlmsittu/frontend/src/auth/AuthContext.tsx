import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { api, ApiError } from '../api/client';
import type { Role, UserSummary } from '../api/types';

/**
 * Session state.
 *
 * There is no token to store. The backend issues an HttpOnly session cookie, which JavaScript
 * cannot read by design — so "am I logged in?" is answered by asking the server, not by inspecting
 * localStorage. That also means the answer is always the truth: a session revoked server-side
 * stops working immediately, rather than after some client-side expiry we invented.
 */

type AuthState = {
  user: UserSummary | null;
  /** True until the initial /auth/me has settled, so routes do not flash the login page. */
  loading: boolean;
  refresh: () => Promise<void>;
  signOut: () => Promise<void>;
  setUser: (user: UserSummary) => void;
  hasRole: (...roles: Role[]) => boolean;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSummary | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      const me = await api.get<UserSummary>('/api/v1/auth/me');
      setUser(me);
    } catch (error) {
      // A 401 here is the normal "not signed in" case, not a failure worth surfacing.
      if (error instanceof ApiError && error.isUnauthenticated) {
        setUser(null);
      } else {
        setUser(null);
        if (!(error instanceof ApiError)) {
          console.error('Could not restore session', error);
        }
      }
    } finally {
      setLoading(false);
    }
  }, []);

  // Runs once on load. This is what makes a page refresh keep you signed in — until Phase 7 adds
  // Redis the session still dies when the backend restarts, which is expected (P1-03).
  useEffect(() => {
    void refresh();
  }, [refresh]);

  const signOut = useCallback(async () => {
    try {
      await api.post('/api/v1/auth/logout');
    } finally {
      setUser(null);
    }
  }, []);

  /**
   * True if the user holds any of the named roles, following the same implications the server does.
   *
   * Without this the screens hide buttons the server would happily have accepted — a worse kind of
   * wrong than showing one that gets refused, because a missing control looks like a missing
   * feature and nobody files a bug about it.
   *
   * Still UX only. Every one of these is enforced again server-side.
   */
  const hasRole = useCallback(
    (...roles: Role[]) => {
      if (!user) return false;
      const held = new Set(user.roles ?? []);
      return roles.some((role) => held.has(role) || impliedBy(held, role));
    },
    [user],
  );

  const value = useMemo<AuthState>(
    () => ({ user, loading, refresh, signOut, setUser, hasRole }),
    [user, loading, refresh, signOut, hasRole],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}

/**
 * Role labels for display. Client-side role checks are UX only — they hide controls the user
 * cannot use. Every one of them is enforced again server-side by `@PreAuthorize`, which is where
 * the actual security lives (architecture 8.1).
 */
/**
 * Which roles imply which, mirroring the backend's `RoleHierarchy` bean.
 *
 * ```
 *   SUPER_ADMIN -> ADMIN -> the five specialist roles
 * ```
 *
 * Duplicated from the server on purpose and kept deliberately small: this is the only place the
 * frontend encodes an authorisation rule, and it decides what a screen *shows*, never what it is
 * *allowed to do*. If the two ever disagree the server wins and the user sees a 403 — annoying,
 * not dangerous.
 *
 * The one implication that is absent is the important one: **`ADMIN` does not imply
 * `SUPER_ADMIN`**, so an admin never sees the user-management screen. That matches the server,
 * where it is the actual control.
 */
const SPECIALIST_ROLES: Role[] = [
  'KYC_REVIEWER',
  'INVENTORY_CLERK',
  'PROCUREMENT_OFFICER',
  'FINANCE_OFFICER',
  'SUPPORT_AGENT',
];

const IMPLIES: Partial<Record<Role, Role[]>> = {
  SUPER_ADMIN: ['ADMIN', ...SPECIALIST_ROLES],
  ADMIN: SPECIALIST_ROLES,
};

function impliedBy(held: Set<string>, wanted: Role): boolean {
  return [...held].some((role) => IMPLIES[role as Role]?.includes(wanted) ?? false);
}

export const ROLE_LABELS: Record<Role, string> = {
  SUPER_ADMIN: 'Super admin',
  ADMIN: 'Admin',
  KYC_REVIEWER: 'Registration reviewer',
  INVENTORY_CLERK: 'Inventory clerk',
  PROCUREMENT_OFFICER: 'Procurement officer',
  FINANCE_OFFICER: 'Finance officer',
  SUPPORT_AGENT: 'Support agent',
  // The role code stays DISTRIBUTOR on the wire and in the database; only what a person reads
  // changes. Renaming the code would be a breaking API change and a migration for a wording
  // preference.
  DISTRIBUTOR: 'Customer',
};
