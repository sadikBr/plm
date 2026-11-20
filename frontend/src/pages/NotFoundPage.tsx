import { Link } from "react-router";

/** Also shown for things the user may not see: the API never reveals whether they exist. */
export function NotFoundPage({ what = "page" }: { what?: string }) {
  return (
    <div className="card" style={{ maxWidth: 520 }}>
      <h1>Not found</h1>
      <p className="muted">
        This {what} doesn't exist, or you're not on the team that can see it. Ask a manager of the product to add
        you.
      </p>
      <Link to="/">Go to start</Link>
    </div>
  );
}
