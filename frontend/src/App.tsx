import { AuditReportPage } from "./audit/AuditReport";
import { NotificationsPage } from "./personal/Personal";
import { DashboardsPage } from "./reports/Dashboards";
import { ReportsPage } from "./reports/ReportsPage";
import { ComparePage } from "./documents/ComparePage";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
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
        <Route path="admin" element={<AdminPage />} />
        <Route path="preferences" element={<MyPreferencesPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

export function App() {
  return (
    <Providers>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </Providers>
  );
}
