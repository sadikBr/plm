import { WorkspacesPage } from "./workspace/WorkspacesPage";
import { PackagesPage } from "./packages/PackagesPage";
import { OslcPreviewPage, OslcSelectPage } from "./standards/Standards";
import { ClassSearchPage } from "./classification/ClassSearch";
import { BaselinesPage } from "./structure/Baselines";
import { InboxPage } from "./workflow/Inbox";
import { PromotionsPage } from "./workflow/Promotions";
import { ChangesPage } from "./changes/Changes";
import { StructureComparePage } from "./structure/StructureCompare";
import { browserLanguage, isLanguage, setLanguage } from "./i18n/i18n";
import { useEffectivePreferences } from "./api/queries";
import { AuditReportPage } from "./audit/AuditReport";
import { NotificationsPage } from "./personal/Personal";
import { DashboardsPage } from "./reports/Dashboards";
import { ReportsPage } from "./reports/ReportsPage";
import { ComparePage } from "./documents/ComparePage";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Fragment, useState, type ReactNode } from "react";
import { BrowserRouter, Route, Routes } from "react-router";
import { isApiError } from "./api/client";
import { AdminPage } from "./admin/AdminPage";
import { Layout } from "./components/Layout";
import { BrowsePage } from "./pages/BrowsePage";
import { ContextPage } from "./pages/ContextPage";
import { HomePage } from "./pages/HomePage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { ObjectPage } from "./pages/ObjectPage";
import { MyPreferencesPage } from "./pages/Preferences";
import { SearchPage } from "./pages/SearchPage";

export function makeQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        refetchOnWindowFocus: true,
        // Client errors (403/404/409...) won't fix themselves: don't retry them.
        retry: (count, error) => !(isApiError(error) && error.status < 500) && count < 2,
      },
      mutations: { retry: false },
    },
  });
}

export function Providers({ children, client }: { children: ReactNode; client?: QueryClient }) {
  const [queryClient] = useState(() => client ?? makeQueryClient());
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

export function AppRoutes() {
  return (
    <Routes>
      {/* Shown inside other tools' pages (OSLC delegated UI, roadmap 9.6): no app shell. */}
      <Route path="oslc/select" element={<OslcSelectPage />} />
      <Route path="oslc/preview/:id" element={<OslcPreviewPage />} />
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="browse/:kind" element={<BrowsePage />} />
        <Route path="contexts/:id" element={<ContextPage />} />
        <Route path="objects/:id" element={<ObjectPage />} />
        <Route path="search" element={<SearchPage />} />
        <Route path="compare" element={<ComparePage />} />
        <Route path="reports" element={<ReportsPage />} />
        <Route path="reports/:id" element={<ReportsPage />} />
        <Route path="dashboards" element={<DashboardsPage />} />
        <Route path="dashboards/:id" element={<DashboardsPage />} />
        <Route path="notifications" element={<NotificationsPage />} />
        <Route path="audit" element={<AuditReportPage />} />
        <Route path="structure-compare" element={<StructureComparePage />} />
        <Route path="inbox" element={<InboxPage />} />
        <Route path="workspaces" element={<WorkspacesPage />} />
        <Route path="workspaces/:id" element={<WorkspacesPage />} />
        <Route path="packages" element={<PackagesPage />} />
        <Route path="packages/:id" element={<PackagesPage />} />
        <Route path="changes" element={<ChangesPage />} />
        <Route path="classes" element={<ClassSearchPage />} />
        <Route path="promotions" element={<PromotionsPage />} />
        <Route path="promotions/:id" element={<PromotionsPage />} />
        <Route path="baselines" element={<BaselinesPage />} />
        <Route path="baselines/:id" element={<BaselinesPage />} />
        <Route path="admin" element={<AdminPage />} />
        <Route path="preferences" element={<MyPreferencesPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

/**
 * The interface language (roadmap 3.12): the user's (or the site's) `ui.language` preference, else
 * the browser's. Changing it re-renders everything in the new language.
 */
function LanguageGate({ children }: { children: ReactNode }) {
  const { data } = useEffectivePreferences();
  const chosen = data?.get("ui.language");
  const lang = isLanguage(chosen) ? chosen : browserLanguage();
  setLanguage(lang);
  return <Fragment key={lang}>{children}</Fragment>;
}

export function App() {
  return (
    <Providers>
      <BrowserRouter>
        <LanguageGate>
          <AppRoutes />
        </LanguageGate>
      </BrowserRouter>
    </Providers>
  );
}
