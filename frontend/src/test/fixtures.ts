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
    attributes: { source: "make", material: "AL6061" },
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

// ---------- Attribute model (a trimmed copy of the out-of-the-box Part) ----------

const SOURCE_ENUM = "4e6c5a8f-0000-4000-8000-0000000000e1";

export const enumerations = [
  {
    id: SOURCE_ENUM,
    name: "partSource",
    labels: { en: "Part source" },
    system: true,
    values: [
      { key: "make", labels: { en: "Make" }, active: true },
      { key: "buy", labels: { en: "Buy" }, active: true },
      { key: "legacy", labels: { en: "Legacy" }, active: false },
    ],
  },
];

export const units = [
  { code: "kg", dimension: "MASS", labels: { en: "kilogram" }, factor: 1, offset: 0 },
  { code: "g", dimension: "MASS", labels: { en: "gram" }, factor: 0.001, offset: 0 },
  { code: "m", dimension: "LENGTH", labels: { en: "metre" }, factor: 1, offset: 0 },
];

const base = {
  typeId: PART_TYPE,
  inherited: false,
  description: null,
  multiValued: false,
  required: false,
  immutable: false,
  minLength: null,
  maxLength: null,
  pattern: null,
  minValue: null,
  maxValue: null,
  enumerationId: null,
  dimension: null,
  displayUnit: null,
  defaultValue: null,
  active: true,
  system: true,
};

export const partAttributes = [
  { ...base, id: "a1", name: "source", labels: { en: "Source" }, dataType: "ENUMERATION" as const, enumerationId: SOURCE_ENUM, defaultValue: "make", sortOrder: 20 },
  { ...base, id: "a2", name: "endItem", labels: { en: "End item" }, dataType: "BOOLEAN" as const, defaultValue: false, sortOrder: 50 },
  { ...base, id: "a3", name: "material", labels: { en: "Material", fr: "Matériau" }, dataType: "STRING" as const, maxLength: 200, sortOrder: 90 },
  { ...base, id: "a4", name: "mass", labels: { en: "Mass" }, dataType: "QUANTITY" as const, dimension: "MASS", displayUnit: "kg", sortOrder: 100 },
  { ...base, id: "a5", name: "releasedAt", labels: { en: "Released at" }, dataType: "TIMESTAMP" as const, sortOrder: 110 },
  { ...base, id: "a6", name: "legacyCode", labels: { en: "Legacy code" }, dataType: "STRING" as const, active: false, sortOrder: 120 },
];

/** Routes every page needs for rendering attributes. */
export const attributeRoutes = {
  "GET /api/v1/types/:id/attributes": () => partAttributes,
  "GET /api/v1/enumerations": () => enumerations,
  "GET /api/v1/units": () => units,
};
