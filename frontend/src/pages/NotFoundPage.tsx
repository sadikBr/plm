import { Link } from "react-router";
import { Panel } from "../components/common";

/** Also shown for things the user may not see: the API never reveals whether they exist. */
export function NotFoundPage({ what = "page" }: { what?: string }) {
  return (
    <div className="content notice">
      <Panel title="Not found">
        <p style={{ marginTop: 0 }}>
          This {what} doesn't exist, or you're not on the team that can see it. Ask a manager of the product to add you.
        </p>
        <Link to="/">Go to start</Link>
      </Panel>
    </div>
  );
}
