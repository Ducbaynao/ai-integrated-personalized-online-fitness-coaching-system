import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { authApi, registerSessionExpiredHandler } from '@/services/apiClient';
import { capabilityStorage, tokenStorage } from '@/services/storage';
import {
  CurrentUserResponse,
  LoginRequest,
  RegisterRequest,
  RegistrationResponse,
  TokenPairResponse,
} from '@/types/auth';
import { ActiveCapability, resolveActiveCapability } from './routeGuard';

export type AuthStatus = 'INITIALIZING' | 'UNAUTHENTICATED' | 'AUTHENTICATED';

export interface AuthContextType {
  status: AuthStatus;
  user: CurrentUserResponse | null;
  activeCapability: ActiveCapability | null;
  isLoading: boolean;
  login: (data: LoginRequest) => Promise<TokenPairResponse>;
  register: (data: RegisterRequest) => Promise<RegistrationResponse>;
  confirmEmail: (token: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: (preferredCapability?: ActiveCapability | null) => Promise<CurrentUserResponse>;
  setActiveCapability: (capability: ActiveCapability) => Promise<void>;
}

async function safeGetActiveCapability(): Promise<string | null> {
  try {
    return await capabilityStorage.getActiveCapability();
  } catch {
    return null;
  }
}

async function safeSaveActiveCapability(capability: string): Promise<void> {
  try {
    await capabilityStorage.saveActiveCapability(capability);
  } catch {
    // Non-fatal: persistence failure must not disrupt auth or UI capability
  }
}

async function safeClearActiveCapability(): Promise<void> {
  try {
    await capabilityStorage.clearActiveCapability();
  } catch {
    // Best-effort cleanup
  }
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('INITIALIZING');
  const [user, setUser] = useState<CurrentUserResponse | null>(null);
  const [activeCapability, setActiveCapabilityState] = useState<ActiveCapability | null>(null);

  const userRef = useRef<CurrentUserResponse | null>(user);
  useEffect(() => {
    userRef.current = user;
  }, [user]);

  const handleSessionExpired = useCallback(() => {
    safeClearActiveCapability().catch(() => {});
    userRef.current = null;
    setUser(null);
    setActiveCapabilityState(null);
    setStatus('UNAUTHENTICATED');
  }, []);

  useEffect(() => {
    registerSessionExpiredHandler(handleSessionExpired);
    return () => {
      registerSessionExpiredHandler(null);
    };
  }, [handleSessionExpired]);

  // Initial session hydration
  useEffect(() => {
    let isMounted = true;

    async function hydrateSession() {
      let currentUser: CurrentUserResponse;
      try {
        const tokens = await tokenStorage.getTokens();
        if (!tokens) {
          if (isMounted) {
            setStatus('UNAUTHENTICATED');
          }
          return;
        }

        // Genuine auth failure (invalid tokens, refresh failure) triggers session cleanup
        currentUser = await authApi.getCurrentUser();
      } catch {
        // Hydration failed (token invalid, refresh failed)
        await tokenStorage.clearTokens().catch(() => {});
        await safeClearActiveCapability();
        userRef.current = null;
        if (isMounted) {
          setUser(null);
          setActiveCapabilityState(null);
          setStatus('UNAUTHENTICATED');
        }
        return;
      }

      // Capability persistence failure must never fail authentication or wipe valid tokens
      userRef.current = currentUser;
      const persisted = await safeGetActiveCapability();
      const resolved = resolveActiveCapability(currentUser, persisted);

      if (
        currentUser.capabilities?.hasStudentProfile &&
        currentUser.capabilities?.hasTrainerProfile &&
        resolved
      ) {
        await safeSaveActiveCapability(resolved);
      }

      if (isMounted) {
        setUser(currentUser);
        setActiveCapabilityState(resolved);
        setStatus('AUTHENTICATED');
      }
    }

    hydrateSession();

    return () => {
      isMounted = false;
    };
  }, []);

  const login = useCallback(async (data: LoginRequest): Promise<TokenPairResponse> => {
    const tokenPair = await authApi.login(data);
    userRef.current = tokenPair.user;

    const persisted = await safeGetActiveCapability();
    const resolved = resolveActiveCapability(tokenPair.user, persisted);

    if (
      tokenPair.user.capabilities?.hasStudentProfile &&
      tokenPair.user.capabilities?.hasTrainerProfile &&
      resolved
    ) {
      await safeSaveActiveCapability(resolved);
    }

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
    try {
      await authApi.logout();
    } finally {
      await safeClearActiveCapability();
      userRef.current = null;
      setUser(null);
      setActiveCapabilityState(null);
      setStatus('UNAUTHENTICATED');
    }
  }, []);

  const refreshUser = useCallback(
    async (preferredCapability?: ActiveCapability | null): Promise<CurrentUserResponse> => {
      const currentUser = await authApi.getCurrentUser();
      userRef.current = currentUser;

      const targetCapability =
        preferredCapability !== undefined
          ? preferredCapability
          : await safeGetActiveCapability();
      const resolved = resolveActiveCapability(currentUser, targetCapability);

      if (
        currentUser.capabilities?.hasStudentProfile &&
        currentUser.capabilities?.hasTrainerProfile &&
        resolved
      ) {
        await safeSaveActiveCapability(resolved);
      }

      setUser(currentUser);
      setActiveCapabilityState(resolved);
      return currentUser;
    },
    []
  );

  const setActiveCapability = useCallback(
    async (capability: ActiveCapability): Promise<void> => {
      const currentUser = userRef.current;
      const resolved = resolveActiveCapability(currentUser, capability);

      if (
        currentUser?.capabilities?.hasStudentProfile &&
        currentUser?.capabilities?.hasTrainerProfile &&
        resolved
      ) {
        await safeSaveActiveCapability(resolved);
      }

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
        login,
        register,
        confirmEmail,
        logout,
        refreshUser,
        setActiveCapability,
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
