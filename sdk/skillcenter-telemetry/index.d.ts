export type SchemaVersion = "1.0";

export type ClientType = "codex" | "department-agent" | "skillmd-compatible" | "gateway";
export type InvocationClientType = ClientType;
export type InstallationClientType = Exclude<ClientType, "gateway">;
export type InvocationStatus = "success" | "failure" | "cancelled" | "timeout";
export type InstallationAction = "install" | "upgrade" | "downgrade" | "uninstall";
export type InstallationMethod = "one-click" | "cli" | "manual-zip";
export type InstallationOutcome = "success" | "failure";

export interface TelemetrySubject {
  userId: string;
  teamId: string;
}

export interface TelemetryClientInfo {
  type: ClientType;
  version: string;
}

export interface InvocationClientInfo {
  type: InvocationClientType;
  version: string;
}

export interface InstallationClientInfo {
  type: InstallationClientType;
  version: string;
}

export interface InvocationUsage {
  model: string;
  inputTokens: number;
  outputTokens: number;
}

export interface InvocationEvent {
  schemaVersion: SchemaVersion;
  eventId: string;
  occurredAt: string;
  skillId: string;
  version: string;
  subject: TelemetrySubject;
  client: InvocationClientInfo;
  sessionId: string;
  status: InvocationStatus;
  durationMs: number;
  errorCode?: string;
  usage?: InvocationUsage;
}

export interface InstallationEvent {
  schemaVersion: SchemaVersion;
  eventId: string;
  occurredAt: string;
  skillId: string;
  version: string;
  fromVersion?: string;
  subject: TelemetrySubject;
  client: InstallationClientInfo;
  deviceId: string;
  action: InstallationAction;
  method: InstallationMethod;
  outcome: InstallationOutcome;
  errorCode?: string;
}

export type InvocationEventInput = Omit<InvocationEvent, "schemaVersion" | "eventId" | "occurredAt">
  & Partial<Pick<InvocationEvent, "schemaVersion" | "eventId" | "occurredAt">>;

export type InstallationEventInput = Omit<InstallationEvent, "schemaVersion" | "eventId" | "occurredAt">
  & Partial<Pick<InstallationEvent, "schemaVersion" | "eventId" | "occurredAt">>;

export interface TelemetryStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export interface TelemetryResponse {
  ok: boolean;
  status: number;
  headers?: { get(name: string): string | null };
  json(): Promise<unknown>;
}

export interface TelemetryRequestOptions {
  method: "POST";
  headers: Record<string, string>;
  body: string;
}

export type TelemetryFetch = (url: string, options: TelemetryRequestOptions) => Promise<TelemetryResponse>;

export interface TelemetryError {
  kind: "invocation" | "installation";
  code: string;
  status: number;
  attempts: number;
}

export interface BatchReport {
  accepted: number;
  duplicates: number;
  rejected: number;
  failed: boolean;
}

export interface FlushReport {
  invocations: BatchReport;
  installations: BatchReport;
  queued: number;
}

export interface TelemetryClientOptions {
  baseUrl?: string;
  endpoint?: string;
  fetchImpl?: TelemetryFetch;
  storage?: TelemetryStorage;
  queueKey?: string;
  maxBatchSize?: number;
  maxQueueSize?: number;
  maxAttempts?: number;
  baseDelayMs?: number;
  maxDelayMs?: number;
  sleep?: (delayMs: number) => Promise<void>;
  now?: () => Date;
  randomUUID?: () => string;
  headers?: Record<string, string>;
  onError?: (error: TelemetryError) => void;
}

export interface TelemetryClient {
  enqueueInvocation(event: InvocationEventInput | InvocationEvent): string;
  enqueueInstallation(event: InstallationEventInput | InstallationEvent): string;
  flush(): Promise<FlushReport>;
  queueSize(): number;
}

export declare function createInvocationEvent(fields?: InvocationEventInput): InvocationEvent;
export declare function createInstallationEvent(fields?: InstallationEventInput): InstallationEvent;
export declare function createTelemetryClient(options?: TelemetryClientOptions): TelemetryClient;
