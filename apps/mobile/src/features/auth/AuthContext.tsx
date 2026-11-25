import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { authApi, invalidateAuthSession, registerSessionExpiredHandler } from '@/services/apiClient';
import { capabilityStorage, tokenStorage } from '@/services/storage';
import { clearQueryCache } from '@/services/queryClient';
import { pushApi } from '@/services/pushApi';
import {
  ApiError,
  CurrentUserResponse,
  LoginRequest,
  RegisterRequest,
  RegistrationResponse,
  TokenPairResponse,
} from '@/types/auth';
import { ActiveCapability, resolveActiveCapability } from './routeGuard';

export type AuthStatus =
  | 'INITIALIZING'
  | 'RESTORE_FAILED'
  | 'UNAUTHENTICATED'
  | 'AUTHENTICATED';

export interface AuthContextType {
  status: AuthStatus;
  user: CurrentUserResponse | null;
  activeCapability: ActiveCapability | null;
  isLoading: boolean;
  restoreError: string | null;
  sessionNotice: string | null;
  login: (data: LoginRequest) => Promise<TokenPairResponse>;
  register: (data: RegisterRequest) => Promise<RegistrationResponse>;
  confirmEmail: (token: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: (preferredCapability?: ActiveCapability | null) => Promise<CurrentUserResponse>;
  setActiveCapability: (capability: ActiveCapability) => Promise<void>;
  retrySessionRestore: () => Promise<void>;
}

async function safeGetActiveCapability(): Promise<string | null> {
  try {
    return await capabilityStorage.getActiveCapability();
  } catch {
    return null;
  }
}

async function safeSaveActiveCapability(
  capability: string,
  shouldApply?: () => boolean
): Promise<void> {
  try {
    await capabilityStorage.saveActiveCapability(capability, shouldApply ? { shouldApply } : undefined);
  } catch {
    // Non-fatal: persistence failure must not disrupt auth or UI capability
  }
}

async function safeClearActiveCapability(shouldApply?: () => boolean): Promise<void> {
  try {
    await capabilityStorage.clearActiveCapability(shouldApply ? { shouldApply } : undefined);
  } catch {
    // Best-effort cleanup
  }
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('INITIALIZING');
  const [user, setUser] = useState<CurrentUserResponse | null>(null);
  const [activeCapability, setActiveCapabilityState] = useState<ActiveCapability | null>(null);
  const [restoreError, setRestoreError] = useState<string | null>(null);
  const [sessionNotice, setSessionNotice] = useState<string | null>(null);

  const userRef = useRef<CurrentUserResponse | null>(user);
  const statusRef = useRef<AuthStatus>(status);
  const activeCapabilityRef = useRef<ActiveCapability | null>(activeCapability);
  const sessionEpochRef = useRef(0);
  const capabilityEpochRef = useRef(0);
  useEffect(() => {
    userRef.current = user;
  }, [user]);
  useEffect(() => {
    statusRef.current = status;
  }, [status]);
  useEffect(() => {
    activeCapabilityRef.current = activeCapability;
  }, [activeCapability]);

  const handleSessionExpired = useCallback(() => {
    sessionEpochRef.current += 1;
    capabilityEpochRef.current += 1;
    const expiredEpoch = sessionEpochRef.current;
    invalidateAuthSession?.();
    clearQueryCache();
    safeClearActiveCapability(() => sessionEpochRef.current === expiredEpoch).catch(() => {});
    userRef.current = null;
    activeCapabilityRef.current = null;
    statusRef.current = 'UNAUTHENTICATED';
    setUser(null);
    setActiveCapabilityState(null);
    setRestoreError(null);
    setSessionNotice('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
    setStatus('UNAUTHENTICATED');
  }, []);

  useEffect(() => {
    registerSessionExpiredHandler(handleSessionExpired);
    return () => {
      registerSessionExpiredHandler(null);
    };
  }, [handleSessionExpired]);

  const restoreSession = useCallback(async (): Promise<void> => {
    sessionEpochRef.current += 1;
    capabilityEpochRef.current += 1;
    const startedSessionEpoch = sessionEpochRef.current;
    const startedCapabilityEpoch = capabilityEpochRef.current;
    const isCurrentRestore = () => sessionEpochRef.current === startedSessionEpoch;

    statusRef.current = 'INITIALIZING';
    setStatus('INITIALIZING');
    setRestoreError(null);

    let currentUser: CurrentUserResponse;
    try {
      const tokens = await tokenStorage.getTokens();
      if (!isCurrentRestore()) return;
      if (!tokens) {
        clearQueryCache();
        userRef.current = null;
        activeCapabilityRef.current = null;
        statusRef.current = 'UNAUTHENTICATED';
        setUser(null);
        setActiveCapabilityState(null);
        setStatus('UNAUTHENTICATED');
        return;
      }

      currentUser = await authApi.getCurrentUser();
      if (!isCurrentRestore()) return;
    } catch (error) {
      if (!isCurrentRestore()) return;
      const isTerminalAuthFailure =
        error instanceof ApiError && (error.status === 401 || error.status === 403);

      if (!isTerminalAuthFailure) {
        statusRef.current = 'RESTORE_FAILED';
        setRestoreError('Không thể kiểm tra phiên đăng nhập. Hãy kiểm tra mạng và thử lại.');
        setStatus('RESTORE_FAILED');
        return;
      }

      await tokenStorage.clearTokens({ shouldApply: isCurrentRestore }).catch(() => {});
      if (!isCurrentRestore()) return;
      clearQueryCache();
      await safeClearActiveCapability(isCurrentRestore);
      if (!isCurrentRestore()) return;
      userRef.current = null;
      activeCapabilityRef.current = null;
      statusRef.current = 'UNAUTHENTICATED';
      setUser(null);
      setActiveCapabilityState(null);
      setSessionNotice('Phiên đăng nhập không còn hiệu lực. Vui lòng đăng nhập lại.');
      setStatus('UNAUTHENTICATED');
      return;
    }

    // Capability persistence failure must never fail authentication or wipe valid tokens.
    const persisted = await safeGetActiveCapability();
    if (!isCurrentRestore() || capabilityEpochRef.current !== startedCapabilityEpoch) return;
    const resolved = resolveActiveCapability(currentUser, persisted);

    if (
      currentUser.capabilities?.hasStudentProfile &&
      currentUser.capabilities?.hasTrainerProfile &&
      resolved
    ) {
      await safeSaveActiveCapability(
        resolved,
        () => isCurrentRestore() && capabilityEpochRef.current === startedCapabilityEpoch
      );
      if (!isCurrentRestore() || capabilityEpochRef.current !== startedCapabilityEpoch) return;
    }

    userRef.current = currentUser;
    activeCapabilityRef.current = resolved;
    statusRef.current = 'AUTHENTICATED';
    setUser(currentUser);
    setActiveCapabilityState(resolved);
    setRestoreError(null);
    setStatus('AUTHENTICATED');
  }, []);

  useEffect(() => {
    const initialRestore = Promise.resolve().then(restoreSession);
    initialRestore.catch(() => {
      statusRef.current = 'RESTORE_FAILED';
      setRestoreError('Không thể khôi phục phiên đăng nhập. Vui lòng thử lại.');
      setStatus('RESTORE_FAILED');
    });
  }, [restoreSession]);

  const login = useCallback(async (data: LoginRequest): Promise<TokenPairResponse> => {
    invalidateAuthSession?.();
    sessionEpochRef.current += 1;
    capabilityEpochRef.current += 1;
    const startedSessionEpoch = sessionEpochRef.current;
    const startedCapabilityEpoch = capabilityEpochRef.current;

    const tokenPair = await authApi.login(data);
    if (sessionEpochRef.current !== startedSessionEpoch) {
      throw new ApiError(401, 'Phiên đăng nhập đã thay đổi.');
    }

    const persisted = await safeGetActiveCapability();
    if (
      sessionEpochRef.current !== startedSessionEpoch ||
      capabilityEpochRef.current !== startedCapabilityEpoch
    ) {
      throw new ApiError(401, 'Phiên đăng nhập hoặc vai trò đã thay đổi.');
    }
    const resolved = resolveActiveCapability(tokenPair.user, persisted);

    if (
      tokenPair.user.capabilities?.hasStudentProfile &&
      tokenPair.user.capabilities?.hasTrainerProfile &&
      resolved
    ) {
      await safeSaveActiveCapability(
        resolved,
        () =>
          sessionEpochRef.current === startedSessionEpoch &&
          capabilityEpochRef.current === startedCapabilityEpoch
      );
      if (
        sessionEpochRef.current !== startedSessionEpoch ||
        capabilityEpochRef.current !== startedCapabilityEpoch
      ) {
        throw new ApiError(401, 'Phiên đăng nhập hoặc vai trò đã thay đổi.');
      }
    }

    clearQueryCache();
    userRef.current = tokenPair.user;
    activeCapabilityRef.current = resolved;
    statusRef.current = 'AUTHENTICATED';
    setSessionNotice(null);
    setRestoreError(null);
    setUser(tokenPair.user);
    setActiveCapabilityState(resolved);
    setStatus('AUTHENTICATED');
    return tokenPair;
  }, []);

  const register = useCallback(async (data: RegisterRequest): Promise<RegistrationResponse> => {
    return authApi.register(data);
  }, []);

  const confirmEmail = useCallback(async (token: string): Promise<void> => {
    return authApi.confirmEmail({ token });
  }, []);

  const logout = useCallback(async (): Promise<void> => {
    invalidateAuthSession?.();
    sessionEpochRef.current += 1;
    capabilityEpochRef.current += 1;
    const logoutEpoch = sessionEpochRef.current;

    try {
      try { await pushApi.unregister(); } catch { /* Offline logout still clears local credentials. */ }
      if (sessionEpochRef.current !== logoutEpoch) return;
      await authApi.logout();
    } finally {
      if (sessionEpochRef.current === logoutEpoch) {
        clearQueryCache();
        await safeClearActiveCapability(() => sessionEpochRef.current === logoutEpoch);
        if (sessionEpochRef.current === logoutEpoch) {
          userRef.current = null;
          activeCapabilityRef.current = null;
          statusRef.current = 'UNAUTHENTICATED';
          setUser(null);
          setActiveCapabilityState(null);
          setRestoreError(null);
          setSessionNotice(null);
          setStatus('UNAUTHENTICATED');
        }
      }
    }
  }, []);

  const refreshUser = useCallback(
    async (preferredCapability?: ActiveCapability | null): Promise<CurrentUserResponse> => {
      const startedSessionEpoch = sessionEpochRef.current;
      const startedCapabilityEpoch = capabilityEpochRef.current;
      const startedUserId = userRef.current?.id ?? null;
      const isStaleRefresh = () =>
        sessionEpochRef.current !== startedSessionEpoch ||
        capabilityEpochRef.current !== startedCapabilityEpoch ||
        statusRef.current !== 'AUTHENTICATED';

      if (statusRef.current !== 'AUTHENTICATED') {
        throw new ApiError(401, 'Phiên đăng nhập chưa được xác thực.');
      }

      const currentUser = await authApi.getCurrentUser();
      if (isStaleRefresh()) {
        throw new ApiError(401, 'Phiên đăng nhập hoặc vai trò đã thay đổi.');
      }

      const targetCapability =
        preferredCapability !== undefined
          ? preferredCapability
          : await safeGetActiveCapability();
      if (isStaleRefresh()) {
        throw new ApiError(401, 'Phiên đăng nhập hoặc vai trò đã thay đổi.');
      }
      const resolved = resolveActiveCapability(currentUser, targetCapability);

      if (
        currentUser.capabilities?.hasStudentProfile &&
        currentUser.capabilities?.hasTrainerProfile &&
        resolved
      ) {
        await safeSaveActiveCapability(resolved, () => !isStaleRefresh());
        if (isStaleRefresh()) {
          throw new ApiError(401, 'Phiên đăng nhập hoặc vai trò đã thay đổi.');
        }
      }

      if (startedUserId && startedUserId !== currentUser.id) {
        clearQueryCache();
      }
      userRef.current = currentUser;
      activeCapabilityRef.current = resolved;
      setUser(currentUser);
      setActiveCapabilityState(resolved);
      return currentUser;
    },
    []
  );

  const setActiveCapability = useCallback(
    async (capability: ActiveCapability): Promise<void> => {
      const startedSessionEpoch = sessionEpochRef.current;
      capabilityEpochRef.current += 1;
      const targetCapabilityEpoch = capabilityEpochRef.current;
      const currentUser = userRef.current;
      const resolved = resolveActiveCapability(currentUser, capability);

      if (
        currentUser?.capabilities?.hasStudentProfile &&
        currentUser?.capabilities?.hasTrainerProfile &&
        resolved
      ) {
        await safeSaveActiveCapability(
          resolved,
          () =>
            sessionEpochRef.current === startedSessionEpoch &&
            capabilityEpochRef.current === targetCapabilityEpoch &&
            statusRef.current === 'AUTHENTICATED'
        );
      }

      if (
        sessionEpochRef.current !== startedSessionEpoch ||
        capabilityEpochRef.current !== targetCapabilityEpoch ||
        statusRef.current !== 'AUTHENTICATED'
      ) {
        return;
      }

      activeCapabilityRef.current = resolved;
      setActiveCapabilityState(resolved);
    },
    []
  );

  return (
    <AuthContext.Provider
      value={{
        status,
        user,
        activeCapability,
        isLoading: status === 'INITIALIZING',
        restoreError,
        sessionNotice,
        login,
        register,
        confirmEmail,
        logout,
        refreshUser,
        setActiveCapability,
        retrySessionRestore: restoreSession,
      }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextType {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
