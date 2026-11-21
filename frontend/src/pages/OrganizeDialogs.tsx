import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";
import { api, unwrap } from "../api/client";
import { findPrincipalByUsername, type VersionedObject } from "../api/queries";
import { Dialog, ErrorBanner, Loading } from "../components/common";
import { FolderPicker } from "./Folders";

function useRefresh(id: string) {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: ["object", id] });
    void qc.invalidateQueries({ queryKey: ["availableActions"] });
    void qc.invalidateQueries({ queryKey: ["objects"] });
    void qc.invalidateQueries({ queryKey: ["folders"] });
  };
}

/** Move (roadmap 3.4): another folder, or another context. */
export function MoveDialog({ obj, onClose }: { obj: VersionedObject; onClose: () => void }) {
  const refresh = useRefresh(obj.id);
  const [target, setTarget] = useState<{ contextId: string; folderId?: string }>({
    contextId: obj.contextId,
    folderId: obj.folderId ?? undefined,
  });
  const move = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/objects/{id}/move", {
          params: { path: { id: obj.id } },
          body: { contextId: target.contextId, folderId: target.folderId ?? null },
        }),
      ),
    onSuccess: () => {
      refresh();
      onClose();
    },
  });
  const same = target.contextId === obj.contextId && (target.folderId ?? null) === (obj.folderId ?? null);
  return (
    <Dialog
      open
      title={`Move ${obj.number}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={move.isPending || same} onClick={() => move.mutate()}>
            Move here
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={move.error} />
        <FolderPicker
          contextId={target.contextId}
          folderId={target.folderId}
          onChange={(contextId, folderId) => setTarget({ contextId, folderId })}
        />
      </div>
    </Dialog>
  );
}

/** Delete, after the delete rules said whether it may be. */
export function DeleteDialog({ obj, onClose }: { obj: VersionedObject; onClose: () => void }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { data: check, isLoading } = useQuery({
    queryKey: ["deletable", obj.id],
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/deletable", { params: { path: { id: obj.id } } })),
  });
  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/objects/{id}", { params: { path: { id: obj.id } } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["objects"] });
      void qc.invalidateQueries({ queryKey: ["folders"] });
      onClose();
      navigate(`/contexts/${obj.contextId}?tab=contents${obj.folderId ? `&folder=${obj.folderId}` : ""}`);
    },
  });
  return (
    <Dialog
      open
      title={`Delete ${obj.number}?`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn danger-fill" disabled={remove.isPending || !check?.allowed} onClick={() => remove.mutate()}>
            Delete
          </button>
        </>
      }
    >
      <div style={{ display: "grid", gap: 10 }}>
        <ErrorBanner error={remove.error} />
        {isLoading ? (
          <Loading />
        ) : check?.allowed ? (
          <p style={{ margin: 0 }}>
            {obj.number} · {obj.name} and all its versions and files will be removed. This can't be undone. The audit trail keeps a record.
          </p>
        ) : (
          <div className="banner warn" role="status">
            It can't be deleted: {check?.reason}.
          </div>
        )}
      </div>
    </Dialog>
  );
}

export function OwnerDialog({ obj, onClose }: { obj: VersionedObject; onClose: () => void }) {
  const refresh = useRefresh(obj.id);
  const [username, setUsername] = useState("");
  const save = useMutation({
    mutationFn: async () => {
      const p = await findPrincipalByUsername(username.trim());
      return unwrap(api.POST("/api/v1/objects/{id}/owner", { params: { path: { id: obj.id } }, body: { owner: p.id } }));
    },
    onSuccess: () => {
      refresh();
      onClose();
    },
  });
  return (
    <Dialog
      open
      title={`Change the owner of ${obj.number}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={save.isPending || !username.trim()} onClick={() => save.mutate()}>
            Hand over
          </button>
        </>
      }
    >
      <div className="attr-form">
        <ErrorBanner error={save.error} />
        <div className="field wide">
          <label htmlFor="owner-username">New owner (username)</label>
          <input id="owner-username" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus />
          <span className="hint">The owner answers for the object, and may delete it while the delete rules allow.</span>
        </div>
      </div>
    </Dialog>
  );
}

/** Reserve and release: one call each, then the page refreshes. */
export function useReservation(obj: VersionedObject) {
  const refresh = useRefresh(obj.id);
  const reserve = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/objects/{id}/reserve", { params: { path: { id: obj.id } } })),
    onSuccess: refresh,
  });
  const release = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/objects/{id}/release", { params: { path: { id: obj.id } } })),
    onSuccess: refresh,
  });
  return { reserve, release };
}
