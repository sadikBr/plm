import type { Iteration, VersionedObject } from "../api/queries";

export const ALICE = "a11ce000-0000-4000-8000-000000000001";
export const BOB = "b0b00000-0000-4000-8000-000000000002";
export const PRODUCT = "01a1f962-0000-7000-8000-00000000000a";
export const PART_TYPE = "00000000-0000-7000-8000-000000000102";

export const me = (id = BOB, siteAdmin = false) => ({
  user: { id, username: "bob", displayName: "Bob Builder", email: null, lastSeen: "2026-11-20T10:00:00Z" },
  siteAdmin,
});

export const principal = (id: string) => ({
  id,
  username: id === ALICE ? "alice" : "bob",
  displayName: id === ALICE ? "Alice Admin" : "Bob Builder",
  email: null,
  lastSeen: "2026-11-20T10:00:00Z",
});

export function iteration(overrides: Partial<Iteration> = {}): Iteration {
  return {
    id: "01a1f962-0000-7000-8000-0000000000i1",
    version: "A.1",
    revision: "A",
    iteration: 1,
    status: "CHECKED_IN",
    state: "IN_WORK",
    attributes: { material: "AL6061" },
    basedOnId: null,
    comment: null,
    createdAt: "2026-11-20T10:00:00Z",
    createdBy: BOB,
    checkedInAt: "2026-11-20T10:00:00Z",
    checkedOutBy: null,
    ...overrides,
  };
}

export function object(overrides: Partial<VersionedObject> = {}): VersionedObject {
  const latest = overrides.latest ?? iteration();
  return {
    id: "01a1f962-0000-7000-8000-0000000000o1",
    number: "0000000001",
    name: "Motor mount",
    typeId: PART_TYPE,
    contextId: PRODUCT,
    version: latest.version,
    checkedOutBy: null,
    latest,
    createdAt: "2026-11-20T10:00:00Z",
    createdBy: BOB,
    ...overrides,
  };
}

export const product = {
  id: PRODUCT,
  parentId: "01a1f962-0000-7000-8000-00000000000o",
  kind: "PRODUCT" as const,
  name: "Drone X1",
  createdAt: "2026-11-20T09:00:00Z",
  createdBy: ALICE,
};

export const types = [
  { id: "00000000-0000-7000-8000-000000000101", parentId: null, name: "Document", displayName: "Document", baseClass: "DOCUMENT" },
  { id: PART_TYPE, parentId: null, name: "Part", displayName: "Part", baseClass: "PART" },
];
