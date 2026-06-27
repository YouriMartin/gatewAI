# Observabilité (Phase 6.1)

Métriques Micrometer exportées au format Prometheus, sur deux niveaux.

## Endpoints (Actuator)

| Endpoint | Accès | Contenu |
|---|---|---|
| `/actuator/health` | public | santé |
| `/actuator/info` | public | infos build |
| `/actuator/prometheus` | public | métriques au format Prometheus |
| `/actuator/metrics` | authentifié | métriques en JSON (exploration) |

> `/actuator/prometheus` est ouvert pour faciliter le scrape interne ; en
> production, restreindre par réseau/pare-feu.

## Métriques natives Spring AI

Avec Actuator + Micrometer sur le classpath, Spring AI instrumente
automatiquement les appels modèle (latence, modèle, tokens d'usage) via
l'`ObservationRegistry` — aucune ligne de code à écrire.

## Métriques green custom (`gatewai_*`)

Émises par `MicrometerMetricsRecorder` à chaque requête servie :

| Métrique | Type | Tags | Sens |
|---|---|---|---|
| `gatewai_requests_total` | counter | `model`, `cache_hit` | nb de requêtes |
| `gatewai_tokens_total` | counter | `model` | tokens consommés |
| `gatewai_cost_eur_total` | counter | `model` | coût € réel |
| `gatewai_cost_avoided_eur_total` | counter | — | € économisés |
| `gatewai_energy_kwh_total` | counter | `model` | énergie estimée |
| `gatewai_co2_grams_total` | counter | `model` | gCO2 émis |
| `gatewai_co2_avoided_grams_total` | counter | — | gCO2 évités |
| `gatewai_cache_hits_total` / `gatewai_cache_misses_total` | counter | — | cache |
| `gatewai_request_latency_seconds` | timer (histogram) | `model` | latence |

Tag commun `application=gatewai` sur toutes les séries.

## Démo Prometheus + Grafana

L'app gère son propre `compose.yaml` (Postgres + Ollama). La stack
d'observabilité est **séparée** et lancée à la demande :

```bash
# 1. lancer l'app (expose :8080)
./mvnw spring-boot:run
# 2. lancer Prometheus + Grafana
docker compose -f docker-compose.observability.yml up -d
```

- Prometheus : http://localhost:9090 (scrape `gatewai` toutes les 15 s)
- Grafana : http://localhost:3000 (admin anonyme), source de données
  Prometheus = `http://prometheus:9090`

Exemples PromQL :

```promql
sum(rate(gatewai_co2_avoided_grams_total[5m]))            # gCO2 évités / s
sum by (model) (gatewai_requests_total)                   # mix de modèles
gatewai_cache_hits_total / (gatewai_cache_hits_total + gatewai_cache_misses_total)
```
