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
export type ValidationRule = Schemas["ValidationRuleResponse"];
export type OperationRule = Schemas["OperationRule"];
export type EventSubscription = Schemas["EventSubscription"];
export type EventSubscriptionSettings = Schemas["EventSubscriptionSettings"];
export type WebhookDelivery = Schemas["WebhookDelivery"];
export type OperationRuleSettings = Schemas["OperationRuleSettings"];
export type Extension = Schemas["ExtensionResponse"];
export type ServiceAccount = Schemas["ServiceAccountResponse"];
export type ApiToken = Schemas["ApiTokenResponse"];
export type IssuedToken = Schemas["IssuedTokenResponse"];
export type IssueTokenRequest = Schemas["IssueTokenRequest"];
export type Profile = Schemas["Profile"];
export type Job = Schemas["Job"];
export type JobSchedule = Schemas["JobSchedule"];
export type JobStatus = Job["status"];
export type ProfileMember = Schemas["ProfileMember"];
export type ExtensionPreview = Schemas["PreviewResponse"];
export type ExtensionManifest = Schemas["ExtensionManifest"];
export type ValidationRuleRequest = Schemas["ValidationRuleRequest"];

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
  "validationRules",
  "operationRules",
  "eventSubscriptions",
  "webhookDeliveries",
  "serviceAccounts",
  "apiTokens",
  "profiles",
  "jobs",
  "jobSchedules",
  "myProfile",
  "extensions",
  "actionRegistry",
  "availableActions",
  "attributes",
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
    queryFn: () => unwrap(api.GET("/api/v1/types/{typeId}/attributes", { params: { path: { typeId }, query: { includeInactive: true } } })),
    enabled: !!typeId,
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

export const useValidationRules = (typeId: string) =>
  useQuery({
    queryKey: ["validationRules", typeId],
    queryFn: () => unwrap(api.GET("/api/v1/types/{id}/validation-rules", { params: { path: { id: typeId } } })),
  });

export const useDefineValidationRule = (typeId: string) =>
  useAdminMutation((body: ValidationRuleRequest) =>
    unwrap(api.POST("/api/v1/types/{id}/validation-rules", { params: { path: { id: typeId } }, body })),
  );

export const useUpdateValidationRule = () =>
  useAdminMutation(({ id, body }: { id: string; body: ValidationRuleRequest }) =>
    unwrap(api.PUT("/api/v1/validation-rules/{id}", { params: { path: { id } }, body })),
  );

export const useDeleteValidationRule = () =>
  useAdminMutation((id: string) => unwrap(api.DELETE("/api/v1/validation-rules/{id}", { params: { path: { id } } })));

export const useCreateType = () => useAdminMutation((body: Schemas["CreateTypeRequest"]) => unwrap(api.POST("/api/v1/types", { body })));

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

export const useCreateLifecycle = () => useAdminMutation((body: CreateLifecycle) => unwrap(api.POST("/api/v1/lifecycles", { body })));

export const useUpdateLifecycle = () =>
  useAdminMutation(({ id, body }: { id: string; body: CreateLifecycle }) =>
    unwrap(api.PUT("/api/v1/lifecycles/{id}", { params: { path: { id } }, body })),
  );

export const useLifecycleVersions = (id: string) =>
  useQuery({
    queryKey: ["lifecycles", "versions", id],
    queryFn: () => unwrap(api.GET("/api/v1/lifecycles/{id}/versions", { params: { path: { id } } })),
  });

export const useUpdateType = (typeId: string) =>
  useAdminMutation((body: Schemas["TypePropertiesRequest"]) =>
    unwrap(api.PUT("/api/v1/types/{id}", { params: { path: { id: typeId } }, body })),
  );

export const useCreateUnit = () => useAdminMutation((body: Schemas["CreateUnitRequest"]) => unwrap(api.POST("/api/v1/units", { body })));

export type GlobalAttribute = Schemas["GlobalAttributeResponse"];

export const useGlobalAttributes = () =>
  useQuery({ queryKey: ["typeAttributes", "globals"], queryFn: () => unwrap(api.GET("/api/v1/attributes/global")) });

export const useDefineGlobal = () => useAdminMutation((body: DefineAttribute) => unwrap(api.POST("/api/v1/attributes/global", { body })));

export const useUpdateGlobal = () =>
  useAdminMutation(({ attributeId, body }: { attributeId: string; body: AttributeSettings }) =>
    unwrap(api.PUT("/api/v1/attributes/global/{attributeId}", { params: { path: { attributeId } }, body })),
  );

export const useGlobalUse = () =>
  useAdminMutation(({ typeId, attributeId, use }: { typeId: string; attributeId: string; use: boolean }) =>
    use
      ? unwrap(api.PUT("/api/v1/types/{typeId}/global-attributes/{attributeId}", { params: { path: { typeId, attributeId } } }))
      : unwrap(api.DELETE("/api/v1/types/{typeId}/global-attributes/{attributeId}", { params: { path: { typeId, attributeId } } })),
  );

export const useCreateRole = () =>
  useAdminMutation((body: Schemas["CreateTeamRoleRequest"]) => unwrap(api.POST("/api/v1/team-roles", { body })));

export const useUpdateRole = () =>
  useAdminMutation(({ key, body }: { key: string; body: Schemas["UpdateTeamRoleRequest"] }) =>
    unwrap(api.PUT("/api/v1/team-roles/{key}", { params: { path: { key } }, body })),
  );

// ---------- Extensions ----------

export const useExtensions = () => useQuery({ queryKey: ["extensions"], queryFn: () => unwrap(api.GET("/api/v1/extensions")) });

export const usePlatform = () =>
  useQuery({ queryKey: ["platform"], queryFn: () => unwrap(api.GET("/api/v1/platform")), staleTime: Infinity });

export const usePreviewExtension = () =>
  useMutation({
    mutationFn: (body: { manifest: string; config: Record<string, unknown> }) => unwrap(api.POST("/api/v1/extensions/preview", { body })),
  });

export const useInstallExtension = () =>
  useAdminMutation((body: { manifest: string; config: Record<string, unknown> }) => unwrap(api.POST("/api/v1/extensions", { body })));

export const useConfigureExtension = () =>
  useAdminMutation(({ id, config }: { id: string; config: Record<string, unknown> }) =>
    unwrap(api.PUT("/api/v1/extensions/{id}/config", { params: { path: { id } }, body: { config } })),
  );

export const useEnableExtension = () =>
  useAdminMutation(({ id, enabled }: { id: string; enabled: boolean }) =>
    unwrap(api.PUT("/api/v1/extensions/{id}/enabled", { params: { path: { id } }, body: { enabled } })),
  );

export const useUninstallExtension = () =>
  useAdminMutation((id: string) => unwrap(api.DELETE("/api/v1/extensions/{id}", { params: { path: { id } } })));

export const useExtensionInvocations = (id: string) =>
  useQuery({
    queryKey: ["extensions", id, "invocations"],
    queryFn: () => unwrap(api.GET("/api/v1/extensions/{id}/invocations", { params: { path: { id } } })),
  });

// ---------- Operation rules ----------

export const useOperationRules = () =>
  useQuery({ queryKey: ["operationRules"], queryFn: () => unwrap(api.GET("/api/v1/operation-rules")) });

export const useDefineOperationRule = () =>
  useAdminMutation((body: OperationRuleSettings) => unwrap(api.POST("/api/v1/operation-rules", { body })));

export const useUpdateOperationRule = () =>
  useAdminMutation(({ id, body }: { id: string; body: OperationRuleSettings }) =>
    unwrap(api.PUT("/api/v1/operation-rules/{id}", { params: { path: { id } }, body })),
  );

export const useDeleteOperationRule = () =>
  useAdminMutation((id: string) => unwrap(api.DELETE("/api/v1/operation-rules/{id}", { params: { path: { id } } })));

// ---------- Event subscriptions and webhooks ----------

export const useEventTypes = () =>
  useQuery({ queryKey: ["eventTypes"], queryFn: () => unwrap(api.GET("/api/v1/event-types")), staleTime: Infinity });

export const useEventSubscriptions = () =>
  useQuery({ queryKey: ["eventSubscriptions"], queryFn: () => unwrap(api.GET("/api/v1/event-subscriptions")) });

export const useSubscriptionHealth = () =>
  useQuery({
    queryKey: ["eventSubscriptions", "health"],
    queryFn: () => unwrap(api.GET("/api/v1/event-subscriptions/health")),
    refetchInterval: 5000,
  });

export const useWebhookDeliveries = (subscriptionId: string, status: string) =>
  useQuery({
    queryKey: ["webhookDeliveries", subscriptionId, status],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/webhook-deliveries", {
          params: { query: { ...(subscriptionId ? { subscriptionId } : {}), ...(status ? { status } : {}), limit: 100 } },
        }),
      ),
    // A live log: deliveries move on by themselves (sent, retried, dead).
    refetchInterval: 5000,
  });

export const useDefineSubscription = () =>
  useAdminMutation((body: EventSubscriptionSettings) => unwrap(api.POST("/api/v1/event-subscriptions", { body })));

export const useUpdateSubscription = () =>
  useAdminMutation(({ id, body }: { id: string; body: EventSubscriptionSettings }) =>
    unwrap(api.PUT("/api/v1/event-subscriptions/{id}", { params: { path: { id } }, body })),
  );

export const useDeleteSubscription = () =>
  useAdminMutation((id: string) => unwrap(api.DELETE("/api/v1/event-subscriptions/{id}", { params: { path: { id } } })));

export const useRedeliver = () =>
  useAdminMutation((id: string) => unwrap(api.POST("/api/v1/webhook-deliveries/{id}/redeliver", { params: { path: { id } } })));

// ---------- Service accounts (2.7) ----------

export const useServiceAccounts = () =>
  useQuery({ queryKey: ["serviceAccounts"], queryFn: () => unwrap(api.GET("/api/v1/service-accounts")) });

export const useApiTokens = (id: string) =>
  useQuery({
    queryKey: ["apiTokens", id],
    queryFn: () => unwrap(api.GET("/api/v1/service-accounts/{id}/tokens", { params: { path: { id } } })),
    enabled: !!id,
  });

export const useCreateServiceAccount = () =>
  useAdminMutation((body: Schemas["CreateServiceAccount"]) => unwrap(api.POST("/api/v1/service-accounts", { body })));

export const useUpdateServiceAccount = () =>
  useAdminMutation(({ id, body }: { id: string; body: Schemas["UpdateServiceAccount"] }) =>
    unwrap(api.PUT("/api/v1/service-accounts/{id}", { params: { path: { id } }, body })),
  );

export const useIssueToken = () =>
  useAdminMutation(({ id, body }: { id: string; body: IssueTokenRequest }) =>
    unwrap(api.POST("/api/v1/service-accounts/{id}/tokens", { params: { path: { id } }, body })),
  );

export const useRevokeToken = () =>
  useAdminMutation((id: string) => unwrap(api.DELETE("/api/v1/tokens/{id}", { params: { path: { id } } })));

// ---------- Profiles (2.8) ----------

export const useProfiles = () => useQuery({ queryKey: ["profiles"], queryFn: () => unwrap(api.GET("/api/v1/profiles")) });

export const useUiAreas = () =>
  useQuery({ queryKey: ["uiAreas"], queryFn: () => unwrap(api.GET("/api/v1/ui-areas")), staleTime: Infinity });

export const useCreateProfile = () =>
  useAdminMutation((body: Schemas["ProfileSettings"]) => unwrap(api.POST("/api/v1/profiles", { body })));

export const useUpdateProfile = () =>
  useAdminMutation(({ id, body }: { id: string; body: Schemas["ProfileSettings"] }) =>
    unwrap(api.PUT("/api/v1/profiles/{id}", { params: { path: { id } }, body })),
  );

export const useDeleteProfile = () =>
  useAdminMutation((id: string) => unwrap(api.DELETE("/api/v1/profiles/{id}", { params: { path: { id } } })));

// ---------- Jobs (3.1) ----------

export const useJobs = (status: string, type: string) =>
  useQuery({
    queryKey: ["jobs", status, type],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/jobs", {
          params: { query: { status: (status || undefined) as JobStatus | undefined, type: type || undefined, limit: 100 } },
        }),
      ),
    refetchInterval: 2000,
  });

export const useJobStats = () =>
  useQuery({ queryKey: ["jobs", "stats"], queryFn: () => unwrap(api.GET("/api/v1/jobs/stats")), refetchInterval: 2000 });

export const useJobTypes = () => useQuery({ queryKey: ["jobTypes"], queryFn: () => unwrap(api.GET("/api/v1/job-types")) });

export const useJobLog = (id: string, live: boolean) =>
  useQuery({
    queryKey: ["jobs", id, "log"],
    queryFn: () => unwrap(api.GET("/api/v1/jobs/{id}/log", { params: { path: { id } } })),
    refetchInterval: live ? 2000 : false,
  });

export const useSubmitJob = () => useAdminMutation((body: Schemas["SubmitJob"]) => unwrap(api.POST("/api/v1/jobs", { body })));

export const useCancelJob = () =>
  useAdminMutation((id: string) => unwrap(api.POST("/api/v1/jobs/{id}/cancel", { params: { path: { id } } })));

export const useRetryJob = () =>
  useAdminMutation((id: string) => unwrap(api.POST("/api/v1/jobs/{id}/retry", { params: { path: { id } } })));

export const useJobSchedules = () =>
  useQuery({ queryKey: ["jobSchedules"], queryFn: () => unwrap(api.GET("/api/v1/job-schedules")), refetchInterval: 5000 });

export const useSaveSchedule = () =>
  useAdminMutation(({ id, body }: { id?: string; body: Schemas["ScheduleSettings"] }) =>
    id
      ? unwrap(api.PUT("/api/v1/job-schedules/{id}", { params: { path: { id } }, body }))
      : unwrap(api.POST("/api/v1/job-schedules", { body })),
  );

export const useDeleteSchedule = () =>
  useAdminMutation((id: string) => unwrap(api.DELETE("/api/v1/job-schedules/{id}", { params: { path: { id } } })));

export const useRunSchedule = () =>
  useAdminMutation((id: string) => unwrap(api.POST("/api/v1/job-schedules/{id}/run", { params: { path: { id } } })));
