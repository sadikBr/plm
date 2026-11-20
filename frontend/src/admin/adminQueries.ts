import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap, type Schemas } from "../api/client";

export type TypeDefinition = Schemas["TypeResponse"];
export type TypeRules = Schemas["TypeRulesResponse"];
export type RevisionScheme = TypeRules["revisionScheme"];
export type NumberingScheme = Schemas["NumberingSchemeResponse"];
export type SavedLayout = Schemas["LayoutResponse"];
export type LayoutScreenKey = SavedLayout["screen"];
export type Lifecycle = Schemas["LifecycleResponse"];
export type CreateLifecycle = Schemas["CreateLifecycleRequest"];
export type EnumerationRequest = Schemas["EnumerationRequest"];
export type DefineAttribute = Schemas["DefineAttributeRequest"];
export type AttributeSettings = Schemas["AttributeSettingsRequest"];

/** Admin changes affect forms and pages everywhere: refetch every piece of reference data. */
const ADMIN_KEYS = [
  "types",
  "typeAttributes",
  "layout",
  "ownLayouts",
  "typeRules",
  "typeLifecycle",
  "enumerations",
  "units",
  "numberingSchemes",
  "lifecycles",
  "teamRoles",
];

function useAdminMutation<A, R>(fn: (args: A) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      for (const key of ADMIN_KEYS) void qc.invalidateQueries({ queryKey: [key] });
    },
  });
}

// ---------- Reads ----------

/** Effective attributes including deactivated ones (forms only show active ones). */
export const useAllAttributes = (typeId: string) =>
  useQuery({
    queryKey: ["typeAttributes", typeId, "all"],
    queryFn: () =>
      unwrap(api.GET("/api/v1/types/{typeId}/attributes", { params: { path: { typeId }, query: { includeInactive: true } } })),
  });

export const useOwnLayouts = (typeId: string) =>
  useQuery({
    queryKey: ["ownLayouts", typeId],
    queryFn: () => unwrap(api.GET("/api/v1/types/{typeId}/layouts", { params: { path: { typeId } } })),
  });

export const useTypeRules = (typeId: string) =>
  useQuery({
    queryKey: ["typeRules", typeId],
    queryFn: () => unwrap(api.GET("/api/v1/types/{typeId}/rules", { params: { path: { typeId } } })),
  });

export const useTypeLifecycle = (typeId: string) =>
  useQuery({
    queryKey: ["typeLifecycle", typeId],
    queryFn: () => unwrap(api.GET("/api/v1/types/{typeId}/lifecycle", { params: { path: { typeId } } })),
  });

export const useNumberingSchemes = () =>
  useQuery({ queryKey: ["numberingSchemes"], queryFn: () => unwrap(api.GET("/api/v1/numbering-schemes")) });

export const useUnitList = () => useQuery({ queryKey: ["units", "list"], queryFn: () => unwrap(api.GET("/api/v1/units")) });

export const useEnumerationList = () =>
  useQuery({ queryKey: ["enumerations", "list"], queryFn: () => unwrap(api.GET("/api/v1/enumerations")) });

// ---------- Writes ----------

export const useCreateType = () =>
  useAdminMutation((body: Schemas["CreateTypeRequest"]) => unwrap(api.POST("/api/v1/types", { body })));

export const useDefineAttribute = (typeId: string) =>
  useAdminMutation((body: DefineAttribute) =>
    unwrap(api.POST("/api/v1/types/{typeId}/attributes", { params: { path: { typeId } }, body })),
  );

export const useUpdateAttribute = (typeId: string) =>
  useAdminMutation(({ attributeId, body }: { attributeId: string; body: AttributeSettings }) =>
    unwrap(api.PUT("/api/v1/types/{typeId}/attributes/{attributeId}", { params: { path: { typeId, attributeId } }, body })),
  );

export const useSaveLayout = (typeId: string) =>
  useAdminMutation(({ screen, body }: { screen: LayoutScreenKey; body: Schemas["SaveLayoutRequest"] }) =>
    unwrap(api.PUT("/api/v1/types/{typeId}/layouts/{screen}", { params: { path: { typeId, screen } }, body })),
  );

export const useRemoveLayout = (typeId: string) =>
  useAdminMutation((screen: LayoutScreenKey) =>
    unwrap(api.DELETE("/api/v1/types/{typeId}/layouts/{screen}", { params: { path: { typeId, screen } } })),
  );

export const useUpdateRules = (typeId: string) =>
  useAdminMutation((body: Schemas["TypeRuleRequest"]) =>
    unwrap(api.PUT("/api/v1/types/{typeId}/rules", { params: { path: { typeId } }, body })),
  );

export const useAssignLifecycle = (typeId: string) =>
  useAdminMutation((lifecycleId: string) =>
    unwrap(api.PUT("/api/v1/types/{typeId}/lifecycle", { params: { path: { typeId } }, body: { lifecycleId } })),
  );

export const useCreateNumberingScheme = () =>
  useAdminMutation((body: Schemas["CreateNumberingSchemeRequest"]) => unwrap(api.POST("/api/v1/numbering-schemes", { body })));

export const useCreateEnumeration = () =>
  useAdminMutation((body: EnumerationRequest) => unwrap(api.POST("/api/v1/enumerations", { body })));

export const useUpdateEnumeration = () =>
  useAdminMutation(({ id, body }: { id: string; body: EnumerationRequest }) =>
    unwrap(api.PUT("/api/v1/enumerations/{id}", { params: { path: { id } }, body })),
  );

export const useCreateLifecycle = () =>
  useAdminMutation((body: CreateLifecycle) => unwrap(api.POST("/api/v1/lifecycles", { body })));

export const useCreateRole = () =>
  useAdminMutation((body: Schemas["CreateTeamRoleRequest"]) => unwrap(api.POST("/api/v1/team-roles", { body })));

export const useUpdateRole = () =>
  useAdminMutation(({ key, body }: { key: string; body: Schemas["UpdateTeamRoleRequest"] }) =>
    unwrap(api.PUT("/api/v1/team-roles/{key}", { params: { path: { key } }, body })),
  );
