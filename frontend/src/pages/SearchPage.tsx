import { Search } from "lucide-react";
import { useSearchParams } from "react-router";
import { useObjectSearch } from "../api/queries";
import { ErrorBanner, Loading, Panel } from "../components/common";
import { PageHeader } from "../components/Layout";
import { ObjectTable } from "../components/ObjectTable";

/** Full results for the top-bar search, across every context the user can read. */
export function SearchPage() {
  const [params] = useSearchParams();
  const text = params.get("q")?.trim() ?? "";
  const search = useObjectSearch(text);
  const rows = search.data?.pages.flat() ?? [];

  return (
    <>
      <PageHeader
        icon={<Search size={18} className="icon-muted" aria-hidden />}
        title={<span className="nm">Search results for “{text}”</span>}
        meta={<span className="sub">Number or name contains the text, in every context you can read</span>}
      />
      <div className="content">
        <Panel title="Objects" note={search.data ? `${rows.length}${search.hasNextPage ? "+" : ""}` : undefined} flush>
          <ErrorBanner error={search.error} />
          {!text ? (
            <p className="empty">Type in the search box above.</p>
          ) : search.isLoading ? (
            <Loading />
          ) : rows.length === 0 ? (
            <p className="empty">Nothing matches “{text}”.</p>
          ) : (
            <ObjectTable
              label="Search results"
              objects={rows}
              showContext
              footer={
                <>
                  <span>{rows.length} objects</span>
                  {search.hasNextPage && (
                    <button className="btn small" onClick={() => void search.fetchNextPage()} disabled={search.isFetchingNextPage}>
                      Load more
                    </button>
                  )}
                </>
              }
            />
          )}
        </Panel>
      </div>
    </>
  );
}
