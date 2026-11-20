import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isApiError, unwrap, type Schemas } from "./client";

export type Context = Schemas["ContextResponse"];
export type ContextKind = Context["kind"];
export type VersionedObject = Schemas["ObjectResponse"];
export type Iteration = Schemas["IterationResponse"];
export type Member = Schemas["MemberResponse"];
export type TeamRole = Member["role"];
export type Permission = "READ" | "CREATE" | "MODIFY" | "REVISE" | "MANAGE";
export type Attributes = Record<string, unknown>;

const PAGE_SIZE = 50;

/** Cache keys in one place, so mutations invalidate exactly what they change. */
export const keys = {
  me: ["me"] as const,
  site: ["site"] as const,
  context: (id: string) => ["context", id] as const,
  children: (id: string) => ["children", id] as const,
  members: (id: string) => ["members", id] as const,
  permissions: (contextId: string, typeId?: string) => ["permissions", contextId, typeId ?? "any"] as const,
  types: ["types"] as const,
  objects: (contextId: string) => ["objects", contextId] as const,
  object: (id: string) => ["object", id] as const,
  history: (id: string) => ["history", id] as const,
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

export const useObjects = (contextId: string) =>
  useInfiniteQuery({
    queryKey: keys.objects(contextId),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET("/api/v1/objects", {
          params: { query: { contextId, limit: PAGE_SIZE, ...(pageParam ? { after: pageParam } : {}) } },
        }),
      ),
    getNextPageParam: (last) => (last.length === PAGE_SIZE ? last[last.length - 1]?.id : undefined),
  });

export const useObject = (id: string) =>
  useQuery({
    queryKey: keys.object(id),
    queryFn: () => unwrap(api.GET("/api/v1/objects/{id}", { params: { path: { id } } })),
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
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.members(contextId) }),
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
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.members(contextId) }),
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
    updateAttributes: useMutation({
      mutationFn: (attributes: Attributes) =>
        unwrap(api.PATCH("/api/v1/objects/{id}/working-copy", { ...path, body: { attributes } })),
      onSettled: () => refresh(),
    }),
  };
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
