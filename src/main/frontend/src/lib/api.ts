// Thin client for the Green AI Proxy API.
// The API key is sent as a Bearer token (entered by the user, kept in the browser).

export interface GreenReport {
  from: string;
  to: string;
  total_requests: number;
  cache_hits: number;
  cache_hit_rate: number;
  total_cost_eur: number;
  total_cost_avoided_eur: number;
  total_energy_kwh: number;
  total_grams_co2: number;
  total_grams_co2_avoided: number;
  model_mix: Record<string, number>;
}

export interface ApiClientView {
  id: string;
  name: string;
  enabled: boolean;
  admin: boolean;
  created_at: string;
}

export interface CreatedClient {
  client: ApiClientView;
  api_key: string;
}

function authHeaders(apiKey: string): HeadersInit {
  return { Authorization: `Bearer ${apiKey}` };
}

async function ensureOk(response: Response): Promise<Response> {
  if (!response.ok) {
    throw new Error(`API responded with ${response.status}`);
  }
  return response;
}

export async function fetchGreenReport(
  apiKey: string,
  from: string,
  to: string,
): Promise<GreenReport> {
  const params = new URLSearchParams({ from, to });
  const response = await fetch(`/v1/reports/green?${params.toString()}`, {
    headers: authHeaders(apiKey),
  });
  return (await (await ensureOk(response)).json()) as GreenReport;
}

export async function downloadReport(
  apiKey: string,
  from: string,
  to: string,
  format: 'csv' | 'pdf',
): Promise<Blob> {
  const params = new URLSearchParams({ from, to, format });
  const response = await fetch(`/v1/reports/green?${params.toString()}`, {
    headers: authHeaders(apiKey),
  });
  return (await ensureOk(response)).blob();
}

export async function fetchGreenSeries(
  apiKey: string,
  from: string,
  to: string,
): Promise<GreenReport[]> {
  const params = new URLSearchParams({ from, to });
  const response = await fetch(`/v1/reports/green/series?${params.toString()}`, {
    headers: authHeaders(apiKey),
  });
  return (await (await ensureOk(response)).json()) as GreenReport[];
}

export async function listClients(apiKey: string): Promise<ApiClientView[]> {
  const response = await fetch('/v1/admin/clients', {
    headers: authHeaders(apiKey),
  });
  return (await (await ensureOk(response)).json()) as ApiClientView[];
}

export async function createClient(
  apiKey: string,
  name: string,
  admin: boolean,
): Promise<CreatedClient> {
  const response = await fetch('/v1/admin/clients', {
    method: 'POST',
    headers: { ...authHeaders(apiKey), 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, admin }),
  });
  return (await (await ensureOk(response)).json()) as CreatedClient;
}

export async function revokeClient(apiKey: string, id: string): Promise<void> {
  const response = await fetch(`/v1/admin/clients/${id}/revoke`, {
    method: 'POST',
    headers: authHeaders(apiKey),
  });
  await ensureOk(response);
}

export interface SemanticRoute {
  name: string;
  tier: string;
  examples: string[];
}

export interface RoutingConfig {
  strategy: string;
  entry_length_threshold: number;
  premium_length_threshold: number;
  premium_keywords: string[];
  route_similarity_threshold: number;
  routes: SemanticRoute[];
}

export async function getRoutingConfig(apiKey: string): Promise<RoutingConfig> {
  const response = await fetch('/v1/admin/routing', {
    headers: authHeaders(apiKey),
  });
  return (await (await ensureOk(response)).json()) as RoutingConfig;
}

export async function updateRoutingConfig(
  apiKey: string,
  config: RoutingConfig,
): Promise<RoutingConfig> {
  const response = await fetch('/v1/admin/routing', {
    method: 'PUT',
    headers: { ...authHeaders(apiKey), 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  });
  return (await (await ensureOk(response)).json()) as RoutingConfig;
}
