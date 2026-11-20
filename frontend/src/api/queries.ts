import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isApiError, unwrap, type Schemas } from "./client";

export type Context = Schemas["ContextResponse"];
export type ContextKind = Context["kind"];
export type VersionedObject = Schemas["ObjectResponse"];
export type Iteration = Schemas["IterationResponse"];
export type Member = Schemas["MemberResponse"];
/** A team role key, e.g. `MEMBER`. Roles are data: see [useTeamRoles]. */
export type TeamRole = string;
export type TeamRoleDefinition = Schemas["TeamRoleResponse"];
export type Permission = Schemas["PolicyResponse"]["permissions"][number];
export type AccessPolicy = Schemas["PolicyResponse"];
export type PolicyEffect = AccessPolicy["effect"];
export type AccessExplanation = Schemas["ExplanationResponse"];
export type LifecycleTemplate = Schemas["LifecycleResponse"];
export type Attributes = Record<string, unknown>;
export type AttributeDefinition = Schemas["AttributeResponse"];
export type DataType = AttributeDefinition["dataType"];
export type Enumeration = Schemas["EnumerationResponse"];
export type Unit = Schemas["UnitResponse"];
export type ObjectLifecycle = Schemas["ObjectLifecycleResponse"];
export type LayoutScreen = Schemas["ResolvedLayoutResponse"]["screen"];
export type ResolvedLayout = Schemas["ResolvedLayoutResponse"];
export type LayoutGroup = Schemas["LayoutGroupDto"];

const PAGE_SIZE = 50;

/** Cache keys in one place, so mutations invalidate exactly what they change. */
export const keys = {
  me: ["me"] as const,
  site: ["site"] as const,
  context: (id: string) => ["context", id] as const,
  children: (id: string) => ["children", id] as const,
  members: (id: string) => ["members", id] as const,
  effectiveMembers: (id: string) => ["effectiveMembers", id] as const,
  policies: (id: string) => ["policies", id] as const,
  explain: (id: string, principalId: string, typeId: string, state: string) => ["explain", id, principalId, typeId, state] as const,
  teamRoles: ["teamRoles"] as const,
  lifecycles: ["lifecycles"] as const,
  permissions: (contextId: string, typeId?: string) => ["permissions", contextId, typeId ?? "any"] as const,
  types: ["types"] as const,
  typeAttributes: (typeId: string) => ["typeAttributes", typeId] as const,
  layout: (typeId: string, screen: string) => ["layout", typeId, screen] as const,
  enumerations: ["enumerations"] as const,
  units: ["units"] as const,
  objects: (contextId: string) => ["objects", contextId] as const,
  object: (id: string) => ["object", id] as const,
  history: (id: string) => ["history", id] as const,
  lifecycle: (id: string) => ["lifecycle", id] as const,
  workingCopy: (id: string) => ["workingCopy", id] as const,
  principal: (id: string) => ["principal", id] as const,
  audit: (aggregateId: string) => ["audit", aggregateId] as const,
};

// ---------- Reads ----------

export const useMe = () => useQuery({ queryKey: keys.me, queryFn: () => unwrap(api.GET("/api/v1/me")) });

/** The root context, or null if the user isn't on any team yet (the server reports it as not found). */
export const useSite = () =>
  useQuery({
    queryKey: keys.site,
    queryFn: async () => {
      try {
        return await unwrap(api.GET("/api/v1/contexts/site"));
      } catch (e) {
        if (isApiError(e, 404)) return null;
        throw e;
      }
    },
  });

export const useContextById = (id: string | undefined) =>
  useQuery({
    queryKey: keys.context(id ?? ""),
    queryFn: () => unwrap(api.GET("/api/v1/contexts/{id}", { params: { path: { id: id! } } })),
    enabled: !!id,
  });

export const useChildren = (id: string | undefined, enabled = true) =>
  useQuery({
    queryKey: keys.children(id ?? ""),
    queryFn: () => unwrap(api.GET("/api/v1/contexts/{id}/children", { params: { path: { id: id! } } })),
    enabled: !!id && enabled,
  });

export const useMembers = (contextId: string) =>
  useQuery({
    queryKey: keys.members(contextId),
    queryFn: () => unwrap(api.GET("/api/v1/contexts/{contextId}/members", { params: { path: { contextId } } })),
  });

/** Everyone with a role here, including members of parent contexts, nearest first. */
export const useEffectiveMembers = (contextId: string) =>
  useQuery({
    queryKey: keys.effectiveMembers(contextId),
    queryFn: () => unwrap(api.GET("/api/v1/contexts/{contextId}/members/effective", { params: { path: { contextId } } })),
  });

/** Access policies in effect in a context (its own and its parents'), root first. Needs MANAGE. */
export const usePolicies = (contextId: string, enabled = true) =>
  useQuery({
    queryKey: keys.policies(contextId),
    queryFn: () => unwrap(api.GET("/api/v1/contexts/{contextId}/policies", { params: { path: { contextId } } })),
    enabled,
  });

/** Why [principalId] can or can't do things here, per permission. */
export const useExplain = (contextId: string, principalId: string, typeId: string, state: string) =>
  useQuery({
    queryKey: keys.explain(contextId, principalId, typeId, state),
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/contexts/{contextId}/access/explain", {
          params: { path: { contextId }, query: { principalId, ...(typeId ? { typeId } : {}), ...(state ? { state } : {}) } },
        }),
      ),
    enabled: !!principalId,
  });

export const usePermissions = (contextId: string | undefined, typeId?: string) =>
  useQuery({
    queryKey: keys.permissions(contextId ?? "", typeId),
    queryFn: async () =>
      new Set(
        (await unwrap(
          api.GET("/api/v1/contexts/{contextId}/permissions", {
            params: { path: { contextId: contextId! }, query: typeId ? { typeId } : {} },
          }),
        )) as Permission[],
      ),
    enabled: !!contextId,
  });

export const useTypes = () => useQuery({ queryKey: keys.types, queryFn: () => unwrap(api.GET("/api/v1/types")) });

/** Reference data changes rarely: cache it for the session (admins' changes show after a reload). */
const REFERENCE_DATA = { staleTime: 10 * 60_000 };

export type ContextTemplate = Schemas["ContextTemplateResponse"];
export type SharedTeam = Schemas["SharedTeamResponse"];

/** Templates for new contexts of [kind] (all kinds when omitted). */
export const useContextTemplates = (kind?: ContextKind) =>
  useQuery({
    queryKey: ["contextTemplates", kind ?? "all"],
    queryFn: () => unwrap(api.GET("/api/v1/context-templates", { params: { query: kind ? { kind } : {} } })),
  });

export const useSharedTeams = () => useQuery({ queryKey: ["sharedTeams"], queryFn: () => unwrap(api.GET("/api/v1/shared-teams")) });

export const useAttachedTeams = (contextId: string) =>
  useQuery({
    queryKey: ["attachedTeams", contextId],
    queryFn: () => unwrap(api.GET("/api/v1/contexts/{contextId}/shared-teams", { params: { path: { contextId } } })),
  });

export function useAttachTeam(contextId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ teamId, attach }: { teamId: string; attach: boolean }) =>
      attach
        ? unwrap(api.PUT("/api/v1/contexts/{contextId}/shared-teams/{teamId}", { params: { path: { contextId, teamId } } }))
        : unwrap(api.DELETE("/api/v1/contexts/{contextId}/shared-teams/{teamId}", { params: { path: { contextId, teamId } } })),
    onSuccess: () => {
      for (const key of ["attachedTeams", "effectiveMembers", "explain", "permissions"]) void qc.invalidateQueries({ queryKey: [key] });
    },
  });
}

export function useSaveAsTemplate(contextId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Schemas["SaveFromContextRequest"]) =>
      unwrap(api.POST("/api/v1/contexts/{contextId}/save-as-template", { params: { path: { contextId } }, body })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["contextTemplates"] }),
  });
}

export type ContextPreference = Schemas["ContextPreferenceResponse"];
export type UserPreference = Schemas["UserPreferenceResponse"];

/** Every preference's value for the current user in [contextId] (site-wide when undefined), by key. */
export const useEffectivePreferences = (contextId?: string) =>
  useQuery({
    queryKey: ["preferences", "effective", contextId ?? "site"],
    queryFn: async () =>
      new Map(
        (await unwrap(api.GET("/api/v1/preferences/effective", { params: { query: contextId ? { contextId } : {} } }))).map((p) => [
          p.key,
          p.value as unknown,
        ]),
      ),
  });

export const useContextPreferences = (contextId: string, enabled = true) =>
  useQuery({
    queryKey: ["preferences", "context", contextId],
    queryFn: () => unwrap(api.GET("/api/v1/contexts/{contextId}/preferences", { params: { path: { contextId } } })),
    enabled,
  });

export const useMyPreferences = () =>
  useQuery({ queryKey: ["preferences", "me"], queryFn: () => unwrap(api.GET("/api/v1/me/preferences")) });

export function useSetPreference(scope: { contextId: string } | "me") {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ key, value, locked, clear }: { key: string; value?: unknown; locked?: boolean; clear?: boolean }) => {
      if (scope === "me") {
        return clear
          ? unwrap(api.DELETE("/api/v1/me/preferences/{key}", { params: { path: { key } } }))
          : unwrap(api.PUT("/api/v1/me/preferences/{key}", { params: { path: { key } }, body: { value } }));
      }
      const path = { contextId: scope.contextId, key };
      return clear
        ? unwrap(api.DELETE("/api/v1/contexts/{contextId}/preferences/{key}", { params: { path } }))
        : unwrap(api.PUT("/api/v1/contexts/{contextId}/preferences/{key}", { params: { path }, body: { value, locked } }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["preferences"] }),
  });
}

export const useTeamRoles = () =>
  useQuery({ queryKey: keys.teamRoles, queryFn: () => unwrap(api.GET("/api/v1/team-roles")), ...REFERENCE_DATA });

export const useLifecycles = () =>
  useQuery({ queryKey: keys.lifecycles, queryFn: () => unwrap(api.GET("/api/v1/lifecycles")), ...REFERENCE_DATA });

/** Effective attributes of a type (inherited first), the basis for every form. */
export const useTypeAttributes = (typeId: string | undefined) =>
  useQuery({
    queryKey: keys.typeAttributes(typeId ?? ""),
    queryFn: () => unwrap(api.GET("/api/v1/types/{typeId}/attributes", { params: { path: { typeId: typeId! } } })),
    enabled: !!typeId,
    ...REFERENCE_DATA,
  });

/** The layout (attribute groups and order) to render a type's attributes with on [screen]. */
export const useLayout = (typeId: string | undefined, screen: LayoutScreen) =>
  useQuery({
    queryKey: keys.layout(typeId ?? "", screen),
    queryFn: () =>
      unwrap(api.GET("/api/v1/types/{typeId}/layouts/{screen}", { params: { path: { typeId: typeId!, screen } } })),
    enabled: !!typeId,
    ...REFERENCE_DATA,
  });

export const useEnumerations = () =>
  useQuery({
    queryKey: keys.enumerations,
    queryFn: async () => new Map((await unwrap(api.GET("/api/v1/enumerations"))).map((e) => [e.id, e])),
    ...REFERENCE_DATA,
  });

export const useUnits = () =>
  useQuery({ queryKey: keys.units, queryFn: () => unwrap(api.GET("/api/v1/units")), ...REFERENCE_DATA });

export const useObjects = (contextId: string, pageSize = PAGE_SIZE) =>
  useInfiniteQuery({
    queryKey: [...keys.objects(contextId), pageSize],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET("/api/v1/objects", {
          params: { query: { contextId, limit: pageSize, ...(pageParam ? { after: pageParam } : {}) } },
        }),
      ),
    getNextPageParam: (last) => (last.length === pageSize ? last[last.length - 1]?.id : undefined),
  });

/** Objects whose number or name contains [text], across every context the user can read. */
export const useObjectSearch = (text: string, limit = PAGE_SIZE) =>
  useInfiniteQuery({
    queryKey: ["search", text, limit],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(api.GET("/api/v1/objects", { params: { query: { q: text, limit, ...(pageParam ? { after: pageParam } : {}) } } })),
    getNextPageParam: (last) => (last.length === limit ? last[last.length - 1]?.id : undefined),
    enabled: text.trim().length > 0,
    staleTime: 5_000,
  });

export const useObject = (id: string) =>
  useQuery({
    queryKey: keys.object(id),
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}", { params: { path: { id } } })),
  });

/** Lifecycle, current state, and what the user may do next. */
export const useLifecycle = (id: string) =>
  useQuery({
    queryKey: keys.lifecycle(id),
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/lifecycle", { params: { path: { id } } })),
  });

export const useHistory = (id: string) =>
  useQuery({
    queryKey: keys.history(id),
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/iterations", { params: { path: { id } } })),
  });

export const useWorkingCopy = (id: string, enabled: boolean) =>
  useQuery({
    queryKey: keys.workingCopy(id),
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}/working-copy", { params: { path: { id } } })),
    enabled,
  });

/** Display name for a user id; falls back to the id for users who never signed in. */
export const usePrincipal = (id: string | null | undefined) =>
  useQuery({
    queryKey: keys.principal(id ?? ""),
    queryFn: async () => {
      try {
        return await unwrap(api.GET("/api/v1/principals/{id}", { params: { path: { id: id! } } }));
      } catch (e) {
        if (isApiError(e, 404)) return null;
        throw e;
      }
    },
    enabled: !!id,
    staleTime: 5 * 60_000,
  });

export const useAudit = (aggregateId: string, enabled: boolean) =>
  useQuery({
    queryKey: keys.audit(aggregateId),
    queryFn: () => unwrap(api.GET("/api/v1/audit", { params: { query: { aggregateId, limit: 100 } } })),
    enabled,
  });

// ---------- Writes ----------

export function useCreateContext() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Schemas["CreateContextRequest"]) => unwrap(api.POST("/api/v1/contexts", { body })),
    onSuccess: (ctx) => {
      if (ctx.parentId) void qc.invalidateQueries({ queryKey: keys.children(ctx.parentId) });
    },
  });
}

export function usePutMember(contextId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ principalId, role }: { principalId: string; role: TeamRole }) =>
      unwrap(
        api.PUT("/api/v1/contexts/{contextId}/members/{principalId}", {
          params: { path: { contextId, principalId } },
          body: { role },
        }),
      ),
    onSuccess: () => invalidateAccess(qc),
  });
}

export function useRemoveMember(contextId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (principalId: string) =>
      unwrap(
        api.DELETE("/api/v1/contexts/{contextId}/members/{principalId}", {
          params: { path: { contextId, principalId } },
        }),
      ),
    onSuccess: () => invalidateAccess(qc),
  });
}

/** Team and policy changes can change anyone's permissions anywhere below: refetch everything access-related. */
function invalidateAccess(qc: ReturnType<typeof useQueryClient>) {
  for (const key of ["members", "effectiveMembers", "policies", "explain", "permissions"]) {
    void qc.invalidateQueries({ queryKey: [key] });
  }
}

export function useCreatePolicy(contextId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Schemas["CreatePolicyRequest"]) =>
      unwrap(api.POST("/api/v1/contexts/{contextId}/policies", { params: { path: { contextId } }, body })),
    onSuccess: () => invalidateAccess(qc),
  });
}

export function useDeletePolicy() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (policyId: string) => unwrap(api.DELETE("/api/v1/policies/{policyId}", { params: { path: { policyId } } })),
    onSuccess: () => invalidateAccess(qc),
  });
}

export async function findPrincipalByUsername(username: string) {
  return unwrap(api.GET("/api/v1/principals", { params: { query: { username } } }));
}

export function useCreateObject() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Schemas["CreateObjectRequest"]) => unwrap(api.POST("/api/v1/objects", { body })),
    onSuccess: (obj) => qc.invalidateQueries({ queryKey: keys.objects(obj.contextId) }),
  });
}

/** Check out / in, undo, revise, edit: all refresh the same views of one object. */
export function useObjectActions(id: string) {
  const qc = useQueryClient();
  const refresh = (contextId?: string) =>
    Promise.all([
      qc.invalidateQueries({ queryKey: keys.object(id) }),
      qc.invalidateQueries({ queryKey: keys.history(id) }),
      qc.invalidateQueries({ queryKey: keys.workingCopy(id) }),
      qc.invalidateQueries({ queryKey: keys.audit(id) }),
      qc.invalidateQueries({ queryKey: keys.lifecycle(id) }),
      contextId ? qc.invalidateQueries({ queryKey: keys.objects(contextId) }) : Promise.resolve(),
    ]);
  const path = { params: { path: { id } } };

  return {
    checkOut: useMutation({
      mutationFn: () => unwrap(api.POST("/api/v1/objects/{id}/checkout", path)),
      onSettled: () => refresh(),
    }),
    checkIn: useMutation({
      mutationFn: (comment: string) =>
        unwrap(api.POST("/api/v1/objects/{id}/checkin", { ...path, body: { comment: comment || null } })),
      onSettled: (obj) => refresh(obj?.contextId),
    }),
    undoCheckOut: useMutation({
      mutationFn: () => unwrap(api.POST("/api/v1/objects/{id}/undo-checkout", path)),
      onSettled: () => refresh(),
    }),
    revise: useMutation({
      mutationFn: () => unwrap(api.POST("/api/v1/objects/{id}/revise", path)),
      onSettled: (obj) => refresh(obj?.contextId),
    }),
    setState: useMutation({
      mutationFn: (body: { state: string; comment?: string; override?: boolean }) =>
        unwrap(api.POST("/api/v1/objects/{id}/state", { ...path, body })),
      onSettled: (obj) => refresh(obj?.contextId),
    }),
    updateAttributes: useMutation({
      mutationFn: (attributes: Attributes) =>
        unwrap(api.PATCH("/api/v1/objects/{id}/working-copy", { ...path, body: { attributes } })),
      onSettled: () => refresh(),
    }),
  };
}

/** Changes an object's number and/or name (needs MANAGE). Kept apart from the toolbar actions so its errors stay in its dialog. */
export function useRenameObject(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Schemas["RenameRequest"]) => unwrap(api.POST("/api/v1/objects/{id}/rename", { params: { path: { id } }, body })),
    onSuccess: (obj) => {
      for (const key of [keys.object(id), keys.audit(id), keys.history(id), keys.objects(obj.contextId)]) {
        void qc.invalidateQueries({ queryKey: key });
      }
    },
  });
}

/** Context kinds that hold objects directly, and what each kind may contain. */
export const HOLDS_OBJECTS: ReadonlySet<ContextKind> = new Set(["PRODUCT", "LIBRARY", "PROJECT"]);
export const ALLOWED_CHILDREN: Record<ContextKind, ContextKind[]> = {
  SITE: ["ORGANIZATION"],
  ORGANIZATION: ["PRODUCT", "LIBRARY", "PROJECT"],
  PRODUCT: [],
  LIBRARY: [],
  PROJECT: [],
};
