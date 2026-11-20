import type { ReactNode } from "react";
import { useAuth } from "react-oidc-context";
import { currentLocation, type SignInState } from "./oidc";

/** Renders children only for a signed-in user. Otherwise shows the sign-in screen. */
export function AuthGate({ children }: { children: ReactNode }) {
  const auth = useAuth();

  if (auth.isLoading || auth.activeNavigator) {
    return (
      <div className="center-screen">
        <span className="spinner" role="status" aria-label="Signing in" />
      </div>
    );
  }

  if (!auth.isAuthenticated) {
    const signIn = () => auth.signinRedirect({ state: { returnTo: currentLocation() } satisfies SignInState });
    return (
      <div className="center-screen">
        <div className="signin">
          <img src="/favicon.svg" alt="" />
          <h1>PLM</h1>
          <p>Sign in with your company account to manage parts, documents and changes.</p>
          {auth.error && (
            <div className="banner error" role="alert">
              Sign-in failed: {auth.error.message}
            </div>
          )}
          <button className="btn primary" onClick={() => void signIn()}>
            Sign in
          </button>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
