// Thin client for the Green AI Proxy reporting API.
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

export async function fetchGreenReport(
  apiKey: string,
  from: string,
  to: string,
): Promise<GreenReport> {
  const params = new URLSearchParams({ from, to });
  const response = await fetch(`/v1/reports/green?${params.toString()}`, {
    headers: { Authorization: `Bearer ${apiKey}` },
  });
  if (!response.ok) {
    throw new Error(`Réponse ${response.status} de l'API`);
  }
  return (await response.json()) as GreenReport;
}
