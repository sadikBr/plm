import { Folder } from "lucide-react";
import { Link } from "react-router";
import type { VersionedObject } from "../api/queries";
import { useFolderPath } from "./Folders";

/** Where an object is filed: its folders, each a link to the context's contents there. */
export function FolderLocation({ obj }: { obj: VersionedObject }) {
  const { data: path } = useFolderPath(obj.folderId ?? undefined);
  if (!obj.folderId) return <span className="muted">At the top of its context</span>;
  return (
    <span className="row wrap" style={{ gap: 4 }}>
      <Folder size={13} className="folder-icon" aria-hidden />
      {path?.map((f, i) => (
        <span key={f.id}>
          {i > 0 && " / "}
          <Link to={`/contexts/${obj.contextId}?tab=contents&folder=${f.id}`}>{f.name}</Link>
        </span>
      ))}
    </span>
  );
}
