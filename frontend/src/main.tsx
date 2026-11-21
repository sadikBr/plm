import { browserLanguage, setLanguage } from "./i18n/i18n";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AuthProvider } from "react-oidc-context";
import { App } from "./App";
import { setTokenProvider } from "./api/client";
import { AuthGate } from "./auth/AuthGate";
import { onSigninCallback, userManager } from "./auth/oidc";
import "@fontsource-variable/inter";
import "./styles.css";

// Every API call carries the current access token (renewed in the background).
setTokenProvider(async () => (await userManager.getUser())?.access_token);

// Until the user's preference is known (e.g. on the sign-in screen): the browser's language.
setLanguage(browserLanguage());

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <AuthProvider userManager={userManager} onSigninCallback={onSigninCallback}>
      <AuthGate>
        <App />
      </AuthGate>
    </AuthProvider>
  </StrictMode>,
);
