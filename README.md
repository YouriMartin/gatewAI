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

Une gateway mince construite sur une chaîne d'**Advisors** Spring AI :

```
Client → ingress OpenAI (/v1/chat/completions)
   → [Advisor 1] Cache sémantique   ── hit → réponse immédiate, zéro appel LLM
   → [Advisor 2] Routeur intelligent ── choisit le modèle optimal (coût/carbone)
   → [Advisor 3] Comptabilité green  ── mesure coût € + gCO2
      → egress : ChatModel (Claude, OpenAI, Ollama…)
   ← réponse → DTO OpenAI → Client
```

**Ingress** (format OpenAI) et **egress** (fournisseur LLM) sont indépendants.
Tout SDK client existant fonctionne en changeant uniquement le `base_url`.

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

- **Java 25** (ou plus récent)
- **Docker** et **Docker Compose**
- Une clé API Anthropic (`ANTHROPIC_API_KEY`)

## Démarrage rapide

```bash
# 1. Cloner le projet
git clone https://github.com/votre-user/gatewAI.git
cd gatewAI

# 2. Configurer la clé API
export ANTHROPIC_API_KEY=sk-ant-...

# 3. Lancer (Boot démarre Postgres + Ollama automatiquement)
./mvnw spring-boot:run

# 4. Tirer le modèle d'embedding (première fois uniquement)
docker compose exec ollama ollama pull nomic-embed-text
```

## Commandes utiles

```bash
# Lancer les tests
./mvnw test

# Infra seule (sans l'app)
docker compose up -d

# Arrêter l'infra
docker compose down
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
