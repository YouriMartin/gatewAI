# Green AI Proxy & Router — Contexte projet

Proxy / gateway LLM open-source, auto-hébergé (**on-premise**), pour entreprises :
il sécurise, met en cache, route et mesure l'empreinte carbone des requêtes IA.
Projet portfolio Java/Spring, développé en solo.

## Stack
- **Java 25 LTS** (Virtual Threads + Scoped Values)
- **Spring Boot 4.0**, **Spring AI 2.0**
- **PostgreSQL + pgvector** — base unique : cache vectoriel **ET** metrics relationnelles
- **Ollama** — embeddings locaux (`nomic-embed-text`, 768 dimensions)
- **Docker Compose** pour l'infra locale
- Build : **Maven** (wrapper `./mvnw`)

## Architecture (à respecter strictement)
Une seule chaîne de traitement, pas trois applications. Gateway mince + chaîne d'**Advisors** Spring AI.

```
Client → ingress format OpenAI (/v1/chat/completions) → mapping DTO → Prompt
   → [Advisor 1] cache sémantique   ── court-circuit si hit (n'appelle pas chain.nextCall())
   → [Advisor 2] routeur            ── choisit le ChatClient cible
   → [Advisor 3] comptabilité green ── coût € + gCO2
      → egress : ChatModel réel (Claude d'abord, puis OpenAI / Ollama)
   ← remap réponse → DTO OpenAI → Client
```

Principes non négociables :
- **Ingress** (le format parlé par les clients = OpenAI) et **egress** (le fournisseur appelé) sont **indépendants**.
- Le cache custom implémente `CallAdvisor`/`StreamAdvisor`, `getOrder()` bas, court-circuite en **n'appelant pas** `chain.nextCall()`.
- Toute la persistance (entité `RequestLog` + vecteurs du cache) va dans le **même PostgreSQL**.
- Dépendre de l'interface `VectorStore`, **jamais** de pgvector en dur (réversibilité vers Qdrant).
- **Structured Concurrency = preview → NE PAS l'utiliser** dans le cœur. **Scoped Values = OK** (propagation contexte client/trace).

## Commandes
- Tests : `./mvnw test`
- Lancer l'app : `./mvnw spring-boot:run` (Boot démarre Postgres + Ollama via `compose.yaml`)
- Infra seule : `docker compose up -d`
- Pull du modèle d'embedding : `docker compose exec ollama ollama pull nomic-embed-text`
- **Toujours lancer `./mvnw test` avant de committer.**

## Architecture hexagonale (packages)

```
com.example.gatewai
├── domain/model/            # Entités, value objects — zéro dépendance Spring/JPA
├── domain/port/in/          # Ports entrants (use cases)
├── domain/port/out/         # Ports sortants (persistence, LLM, vector store)
├── application/service/     # Services applicatifs — dépend de domain uniquement
├── infrastructure/          # Adapters sortants — implémente ports out
│   ├── persistence/         # JPA
│   ├── llm/                 # ChatClient/ChatModel
│   └── vectorstore/         # VectorStore
└── adapter/in/web/          # Controllers REST (ingress OpenAI)
```

**Règles de dépendance :**
- `domain` ne dépend de rien (ni Spring, ni JPA, ni Spring AI)
- `application` dépend de `domain` uniquement
- `infrastructure` implémente les ports `out` de `domain`
- `adapter.in.web` appelle les ports `in` de `domain`
- Ces règles sont vérifiées par **ArchUnit** (`ArchitectureTest.java`)

## Conventions
- Java `record` pour les DTO.
- Builders immutables Spring AI 2.0 (pas de setters).
- Jackson 3 → package `tools.jackson` (et non `com.fasterxml.jackson`).
- Secrets via variables d'environnement, **jamais commités** (`ANTHROPIC_API_KEY`).
- Messages de commit courts, **en anglais**, à l'impératif.
- **Toujours proposer un message de commit à la fin de chaque implémentation.**

## Tests
- Nommage : `{Classe}Test.java` dans le même package miroir sous `src/test/java`
- Tests unitaires sur toutes les classes **sauf** : controllers REST (testés en intégration), mappers triviaux
- `ArchitectureTest` valide les règles hexagonales via ArchUnit
- **Toujours lancer `./mvnw test` avant de committer**

## Linters & analyse statique
- **Checkstyle** (`maven-checkstyle-plugin`) — phase `validate`, fail-fast, config `checkstyle.xml` (Google Style + surcharges)
- **SpotBugs** (`spotbugs-maven-plugin`) — phase `verify`, effort=max, threshold=low
- `./mvnw verify` lance les trois (Checkstyle + Tests + SpotBugs)

## État / roadmap
MVP = Phases 0 à 2 + tranche de Phase 3 (détail dans `plan-action-green-ai-proxy.md`).
Avancement : _(à mettre à jour au fil de l'eau)_
- [x] Phase 0 — squelette + infra locale
- [x] Phase 1 — passerelle pass-through (ingress OpenAI → egress Claude)
- [x] Phase 2 — cache sémantique
- [x] Phase 3 — routeur intelligent
  - [x] 3.1 — registre de modèles (`@ConfigurationProperties`)
  - [x] 3.2 — `ChatClient` qualifiés par palier
  - [x] 3.3 — classifieur de complexité V1 (heuristiques)
  - [x] 3.4 — `RoutingAdvisor` (classifieur → sélection du ChatClient)
  - [x] 3.5 — classifieur V2 (petit modèle + Structured Outputs, règles configurables à chaud)
- [x] Phase 4 — green inference & reporting
  - [x] 4.1 — modèle carbone (tokens → kWh → gCO2)
  - [x] 4.2 — intensité temps réel (`CarbonIntensityProvider` swappable, ElectricityMaps)
  - [x] 4.3 — persistance coût + carbone + « CO2 évité »
  - [x] 4.4 — routage temporel/géo (endpoint async + worker `@Scheduled` + zone la plus verte)
  - [x] 4.5 — API de reporting (agrégats + export CSV/PDF)
- [~] Phase 5 — dashboard (Svelte + Vite, mono-repo, bundlé dans le jar)
  - [x] 5.0 — socle : projet Svelte, `frontend-maven-plugin`, serving statique, `SecurityConfig`, shell + clé API + 3 KPI
  - [x] 5.1 — admin des clés d'API : rôle admin + seed au démarrage, CRUD `/v1/admin/clients`, UI (liste/créer/révoquer)
  - [ ] 5.2 — config du routage (seuils/règles à chaud)
  - [ ] 5.3 — metrics live (consomme `/v1/reports/green`)
  - [ ] 5.4 — rapports (téléchargement CSV/PDF)

## Build front (mono-repo)
- App Svelte+Vite dans `src/main/frontend`, buildée vers `target/classes/static` (bundlée dans le jar).
- `./mvnw package` build le front (profil `frontend` actif par défaut) ; `./mvnw test` reste sans Node.
- Travail back pur : `./mvnw … -DskipFrontend`. Dev front : `npm run dev` (proxy `/v1` → `:8080`).

## Préférences de communication
- **Après chaque implémentation** : expliquer en détail ce qui a été fait et pourquoi (choix techniques, trade-offs, liens avec l'architecture).
- **Avant chaque commande** : expliquer pourquoi cette commande est nécessaire avant de demander confirmation.
- **Langue** : répondre en français quand l'utilisateur écrit en français.
