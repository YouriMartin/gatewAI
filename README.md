# gatewAI

Proxy LLM open-source et auto-hébergé pour entreprises.
Il sécurise, met en cache, route et mesure l'empreinte carbone des requêtes IA.

## Pourquoi gatewAI ?

Les entreprises adoptent les LLM mais font face à trois problèmes :

- **Coût** — des requêtes redondantes facturées en double (ou en triple)
- **Confidentialité** — les données transitent chez des tiers sans contrôle
- **Impact environnemental** — aucune visibilité sur l'empreinte carbone de l'IA

gatewAI résout les trois en une seule brique d'infrastructure.

## Architecture

Une gateway mince construite sur une chaîne d'**Advisors** Spring AI. Vue
end-to-end :

```
        ┌──────────────┐        ┌──────────────────┐
        │ SDK OpenAI    │       │ Client MCP        │
        │ (base_url →)  │       │ (Claude Desktop…) │
        └──────┬────────┘       └────────┬──────────┘
               │ /v1/chat/completions     │ /mcp
               ▼                          ▼
   ╔══════════════════════ gatewAI ══════════════════════╗
   ║  ingress OpenAI  +  ingress MCP   (auth clé API)     ║
   ║      │                                               ║
   ║      ▼  chaîne d'Advisors Spring AI                  ║
   ║  [1] Cache sémantique   ─ hit → réponse, 0 appel LLM ║
   ║  [2] Routeur intelligent ─ modèle optimal coût/CO2   ║
   ║  [3] Comptabilité green  ─ € + gCO2 (+ CO2 évité)    ║
   ║      │                                               ║
   ║      ▼ egress                  Dashboard Svelte  ◀───╫─ /
   ║  ChatModel réel                /actuator/prometheus ◀╫─ Prometheus+Grafana
   ╚══════╪═══════════════════════════════╪══════════════╝
          │                               │
   ┌──────▼──────┐  ┌──────────────┐  ┌───▼──────────────────┐
   │ Claude /     │  │ Ollama        │ │ PostgreSQL + pgvector │
   │ OpenAI (API) │  │ (embeddings + │ │ cache vectoriel +     │
   │              │  │  modèle local)│ │ metrics relationnelles│
   └──────────────┘  └──────────────┘  └──────────────────────┘
```

**Ingress** (format OpenAI ou MCP) et **egress** (fournisseur LLM) sont
indépendants. Tout SDK client existant fonctionne en changeant uniquement le
`base_url`. Détails par couche : voir [`docs/`](docs/) (carbone, observabilité,
MCP, image native).

## Stack technique

| Composant | Technologie |
|---|---|
| Langage | Java 25 (Virtual Threads + Scoped Values) |
| Framework | Spring Boot 4, Spring AI 2.0 |
| Base de données | PostgreSQL + pgvector (cache vectoriel + metrics) |
| Embeddings | Ollama + nomic-embed-text (768 dim, 100% on-premise) |
| Infra locale | Docker Compose |
| Build | Maven (wrapper inclus) |

## Prérequis

- **Docker** et **Docker Compose** (suffit pour le mode plug & play)
- **Java 25** + Maven *(uniquement pour le mode développement)*
- Une clé API Anthropic (`ANTHROPIC_API_KEY`)

## Démarrage rapide — plug & play (tout en conteneurs)

Aucun JDK requis : Docker build l'app (back + dashboard) et lance toute la stack.

```bash
# 1. Cloner le projet
git clone https://github.com/votre-user/gatewAI.git
cd gatewAI

# 2. Configurer les secrets
cp .env.example .env
$EDITOR .env            # renseigner ANTHROPIC_API_KEY

# 3. Lancer la stack complète (gateway + Postgres/pgvector + Ollama)
docker compose -f docker-compose.yml up --build
```

> `compose.yaml` (infra seule) sert au mode dev et a la priorité quand on tape
> `docker compose` sans `-f` ; la stack complète s'invoque donc explicitement
> avec `-f docker-compose.yml`.

- Dashboard : <http://localhost:8080/>
- API OpenAI : `POST http://localhost:8080/v1/chat/completions`
- Serveur MCP : `http://localhost:8080/mcp` (voir [`docs/mcp.md`](docs/mcp.md))
- Health : <http://localhost:8080/actuator/health>

Au **premier** démarrage, la gateway télécharge le modèle d'embedding
(`nomic-embed-text`) depuis Ollama — laisser le temps au healthcheck de passer.
La **clé API admin** d'amorçage est affichée une seule fois dans les logs :

```bash
docker compose -f docker-compose.yml logs gateway | grep "Admin API key"
```

## Mode développement (JDK local, hot reload)

```bash
# Boot démarre Postgres + Ollama via compose.yaml ; l'app tourne sur la JVM
export ANTHROPIC_API_KEY=sk-ant-...
./mvnw spring-boot:run

# Dashboard en hot reload (Vite, proxy /v1 → :8080)
cd src/main/frontend && npm run dev
```

## Commandes utiles

```bash
# Tests (rapides, sans Node ni conteneurs)
./mvnw test

# Build complet : tests + Checkstyle + SpotBugs
./mvnw verify

# Infra seule, sans l'app (mode dev)
docker compose -f compose.yaml up -d

# Stack observabilité optionnelle (Prometheus + Grafana)
docker compose -f docker-compose.observability.yml up -d

# Arrêter la stack complète plug & play
docker compose -f docker-compose.yml down
```

## Fonctionnalités

### Cache sémantique
Intercepte les requêtes similaires avant tout appel LLM.
Réduit les coûts et la latence en court-circuitant les appels redondants.

### Routage intelligent
Envoie chaque requête au modèle le moins cher/gourmand capable de la traiter :
local (Ollama) pour les tâches simples, cloud premium pour les tâches complexes.

### Comptabilité carbone
Mesure l'empreinte (tokens → kWh → gCO2) et calcule le CO2 évité grâce au cache et au routage.
Export compatible reporting CSRD.

## Licence

[MIT](LICENSE)
