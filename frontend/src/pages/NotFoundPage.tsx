import { Link } from "react-router";
import { t } from "../i18n/i18n";
import { Panel } from "../components/common";

function missing(what: string): string {
  switch (what) {
    case "page":
      return t("This page doesn't exist, or you're not on the team that can see it.");
    case "object":
      return t("This object doesn't exist, or you're not on the team that can see it.");
    case "context":
      return t("This context doesn't exist, or you're not on the team that can see it.");
  }
  return t("This {what} doesn't exist, or you're not on the team that can see it.", { what });
}

/** Also shown for things the user may not see: the API never reveals whether they exist. */
export function NotFoundPage({ what = "page" }: { what?: string }) {
  return (
    <div className="content notice">
      <Panel title={t("Not found")}>
        <p style={{ marginTop: 0 }}>
          {missing(what)} {t("Ask a manager of the product to add you.")}
        </p>
        <Link to="/">{t("Go to start")}</Link>
      </Panel>
    </div>
  );
}
