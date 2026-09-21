import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { authApi, registerSessionExpiredHandler } from '@/services/apiClient';
import { tokenStorage } from '@/services/storage';
import {
  CurrentUserResponse,
  LoginRequest,
  RegisterRequest,
  RegistrationResponse,
  TokenPairResponse,
} from '@/types/auth';

export type AuthStatus = 'INITIALIZING' | 'UNAUTHENTICATED' | 'AUTHENTICATED';

export interface AuthContextType {
  status: AuthStatus;
  user: CurrentUserResponse | null;
  isLoading: boolean;
  login: (data: LoginRequest) => Promise<TokenPairResponse>;
  register: (data: RegisterRequest) => Promise<RegistrationResponse>;
  confirmEmail: (token: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<CurrentUserResponse>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('INITIALIZING');
  const [user, setUser] = useState<CurrentUserResponse | null>(null);

  const handleSessionExpired = useCallback(() => {
    setUser(null);
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
      try {
        const tokens = await tokenStorage.getTokens();
        if (!tokens) {
          if (isMounted) {
            setStatus('UNAUTHENTICATED');
          }
          return;
        }

        // Fetch current user details
        const currentUser = await authApi.getCurrentUser();
        if (isMounted) {
          setUser(currentUser);
          setStatus('AUTHENTICATED');
        }
      } catch {
        // Hydration failed (token invalid, refresh failed)
        await tokenStorage.clearTokens();
        if (isMounted) {
          setUser(null);
          setStatus('UNAUTHENTICATED');
        }
      }
    }

    hydrateSession();

    return () => {
      isMounted = false;
    };
  }, []);

  const login = useCallback(async (data: LoginRequest): Promise<TokenPairResponse> => {
    const tokenPair = await authApi.login(data);
    setUser(tokenPair.user);
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
    await authApi.logout();
    setUser(null);
    setStatus('UNAUTHENTICATED');
  }, []);

  const refreshUser = useCallback(async (): Promise<CurrentUserResponse> => {
    const currentUser = await authApi.getCurrentUser();
    setUser(currentUser);
    return currentUser;
  }, []);

  return (
    <AuthContext.Provider
      value={{
        status,
        user,
        isLoading: status === 'INITIALIZING',
        login,
        register,
        confirmEmail,
        logout,
        refreshUser,
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
