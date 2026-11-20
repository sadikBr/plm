import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts";
import { config } from "../config";

/** Where to go after sign-in. Carried through the redirect in the OIDC `state`. */
export interface SignInState {
  returnTo: string;
}

/**
 * The one OIDC client for the app: authorization code flow with PKCE against Keycloak.
 * Tokens live in sessionStorage (cleared when the tab closes) and are renewed with the refresh token.
 */
export const userManager = new UserManager({
  authority: config.oidcAuthority,
  client_id: config.oidcClientId,
  redirect_uri: `${window.location.origin}/`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  scope: "openid profile email",
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
});

/** After the redirect back from Keycloak: drop `?code=&state=` and return to where the user was. */
export function onSigninCallback(user: User | void): void {
  const state = (user?.state ?? undefined) as SignInState | undefined;
  window.history.replaceState({}, document.title, state?.returnTo ?? "/");
}

export function currentLocation(): string {
  return window.location.pathname + window.location.search;
}
