# Plan d'action — Green AI Proxy & Router

**Rôle adopté :** Architecte logiciel senior (Java / Spring Boot 4 / MLOps).
**Cible technique :** portfolio Java/Spring, projet solo, livraison incrémentale MVP-first.
**Stack arrêtée :** Java 25 LTS (Virtual Threads + Scoped Values), Spring Boot 4.0, Spring AI 2.0, PostgreSQL + pgvector (base unique), Docker Compose.

> **Décisions actées (itération 2)** — (1) Java 25 LTS plutôt que 21 : même socle Boot 4, support ~8 ans, Scoped Values finalisés. (2) Une seule base : PostgreSQL + extension pgvector pour le cache vectoriel **et** les metrics relationnelles (au lieu de Qdrant + Postgres). (3) Distinction ingress/egress explicitée : **ingress** au format compatible OpenAI, **egress** vers Claude (Anthropic) en premier.

---

## Décision d'architecture fondatrice

### Les deux axes à ne pas confondre : ingress vs egress

- **Ingress** = le format que parlent *tes clients* en appelant le proxy. Standard de facto du marché : le format **compatible OpenAI** (`/v1/chat/completions`). Un dev change son `base_url` et son SDK existant fonctionne.
- **Egress** = le fournisseur que *toi* tu appelles derrière (Anthropic, OpenAI, Ollama…). Masqué par l'interface `ChatModel`/`ChatClient` de Spring AI.

Ces deux axes sont **orthogonaux**. On démarre donc avec **ingress OpenAI + egress Claude**. Changer ou ajouter un fournisseur = ajouter un starter + un bean, sans toucher au code métier. C'est ce que font les vraies passerelles LLM.

### Une seule chaîne, pas trois applications

Les trois piliers du brief ne sont pas trois applications. Ce sont **trois maillons d'une même chaîne de traitement** :

```
Client → ingress OpenAI (/v1/chat/completions)
   → Passerelle (mapping DTO OpenAI → Prompt Spring AI)
      → [Advisor 1] Cache sémantique   ── hit → réponse immédiate (court-circuit)
      → [Advisor 2] Routeur            ── choisit le ChatClient cible
      → [Advisor 3] Comptabilité green ── mesure coût € + gCO2
         → egress : ChatModel réel (Claude / OpenAI / Ollama local)
      ← réponse remontée à travers la chaîne (écriture cache + persistance metrics)
   ← mapping réponse Spring AI → DTO OpenAI → Client
```

Spring AI fournit nativement l'**Advisors API** (`CallAdvisor`/`StreamAdvisor`) : chaque advisor peut lire, modifier, **ou bloquer** la requête avant qu'elle n'atteigne le modèle. C'est exactement le mécanisme du cache (court-circuit) et du routage. Tu construis donc un *gateway* mince autour d'une chaîne d'advisors, pas une usine à gaz.

Spring AI **ne fournit pas** d'advisor de cache sémantique prêt à l'emploi : tu vas l'écrire. C'est précisément ce qui fait la valeur portfolio (advisor RAG existant à réutiliser comme modèle de code, mais logique custom).

---

## Définition du MVP

Le MVP démontrable = **Phases 0 à 2 + une tranche fine de la Phase 3**.
À ce stade tu as : un proxy transparent, un cache sémantique fonctionnel, un routage basique cloud/local, et la persistance des metrics. C'est déjà une démo qui tient debout. Le reste (green reporting avancé, dashboard) est de la capitalisation.

---

## Phase 0 — Fondations & infrastructure

Objectif : un squelette qui démarre, parle à un LLM et à une base vectorielle.

| Tâche | Détail | Composants Spring / Spring AI |
|---|---|---|
| 0.1 | Bootstrap via `start.spring.io` | Boot 4.0, **Java 25** |
| 0.2 | Ajouter les starters | `spring-ai-starter-model-anthropic` (egress prioritaire), puis `...-model-openai`, `...-model-ollama`, **`spring-ai-starter-vector-store-pgvector`**, `spring-boot-starter-web`, `...-data-jpa`, `postgresql`, `...-actuator`, `...-security` |
| 0.3 | Docker Compose dev | **Un seul PostgreSQL** (extension pgvector activée) + Ollama (ajouté en 0.5). Exploiter le **support Docker Compose de Boot** pour le démarrage auto en dev. Moins de conteneurs = moins de RAM = cohérent « green » |
| 0.4 | Concurrence | `spring.threads.virtual.enabled=true` (Virtual Threads, stable). **Scoped Values** (finalisés en Java 25) pour propager l'ID client/tenant + trace dans la chaîne sans le passer partout |
| 0.5 | Embedding **local** | Ollama + `nomic-embed-text` (768 dimensions). Crucial : l'embedding reste on-premise → cohérent avec le pilier « zéro fuite ». Vérifier que la dimension de la colonne `vector` correspond |
| 0.6 | Smoke test | Un `ChatClient` (egress Claude) qui répond + un `EmbeddingModel` qui vectorise + healthcheck Actuator |

**Garde-fou :** valide d'abord la chaîne embedding local → pgvector (index **HNSW**, opérateur **cosinus**) → `similaritySearch` avant tout le reste. C'est le composant le moins familier, donc le plus risqué.

---

## Phase 1 — La passerelle (la colonne vertébrale)

Objectif : un proxy transparent (ingress OpenAI → egress Claude), qui journalise tout. Démontrable seul.

| Tâche | Détail | Composants |
|---|---|---|
| 1.1 | DTO ingress compatibles OpenAI | `records` Java pour requête/réponse `/v1/chat/completions`. C'est ce qui permet à n'importe quel SDK client existant de pointer vers ton proxy **sans changer une ligne** → argument d'adoption majeur |
| 1.2 | Contrôleur passerelle | Mapping DTO OpenAI (ingress) → `Prompt` Spring AI → `ChatClient.call()` sur l'**egress Claude** → remapping réponse → DTO OpenAI. À ce stade : pass-through pur. Le format d'entrée et le fournisseur de sortie sont indépendants |
| 1.3 | Persistance des requêtes | Entité `RequestLog` (JPA, **même PostgreSQL** que le cache) : horodatage, modèle, hash du prompt, tokens in/out, latence. Lire les tokens via les **métadonnées de `ChatResponse`** (l'observabilité Spring AI les expose) |
| 1.4 | Authentification | Filtre Spring Security : clé d'API → client (table en base). Pose la base du multi-tenant et du reporting par client |

---

## Phase 2 — Pilier A : cache sémantique (advisor custom)

Objectif : intercepter les requêtes redondantes avant tout appel modèle.

| Tâche | Détail | Composants |
|---|---|---|
| 2.1 | `SemanticCacheAdvisor` | `implements CallAdvisor, StreamAdvisor`. `getOrder()` **bas** → s'exécute en premier dans la chaîne |
| 2.2 | Logique hit/miss | Embed du texte user → `vectorStore.similaritySearch()` avec `threshold ≥ 0.92`, `topK = 1`. **Hit** → construire un `ChatClientResponse` depuis la réponse stockée et **ne pas appeler** `chain.nextCall()` (court-circuit). **Miss** → laisser passer |
| 2.3 | Écriture au retour | Sur un miss, au retour de la chaîne : stocker `{embedding question, texte question, réponse}` dans **pgvector** avec métadonnées (modèle, client, date) |
| 2.4 | Paramétrage | Seuil, TTL, **namespace par client** via filtre de métadonnées (`Filter.Expression`) pour cloisonner les caches. Journaliser hit/miss + calculer € et gCO2 économisés |

**Crux du code (illustratif) :**
```java
public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
    var hit = cacheLookup(req);            // embed + similaritySearch
    if (hit.isPresent()) {
        return buildResponseFromCache(hit.get());  // court-circuit : aucun appel LLM
    }
    var response = chain.nextCall(req);    // miss → on délègue à la suite
    cacheStore(req, response);             // on apprend pour la prochaine fois
    return response;
}
```

**Piège à anticiper :** le streaming (`adviseStream`) complique le court-circuit (il faut émettre un `Flux` synthétique). Implémente d'abord le mode `call` (non-streaming), traite le streaming en V2.

**Réversibilité :** ton advisor dépend de l'interface `VectorStore`, pas de pgvector directement. Si un jour tu dois passer à l'échelle (dizaines de millions de vecteurs, très haut débit), tu bascules sur Qdrant en changeant la dépendance et la config — **pas ta logique**. Décision à faible risque.

---

## Phase 3 — Pilier B : routeur intelligent

Objectif : envoyer chaque requête au modèle le moins cher/gourmand qui sait la traiter.

| Tâche | Détail | Composants |
|---|---|---|
| 3.1 | Registre de modèles | Config (`@ConfigurationProperties`) : par modèle → provider, id, €/1k tokens, intensité énergétique, palier (local / cloud entrée de gamme / cloud premium) |
| 3.2 | Plusieurs `ChatClient` | Un bean `ChatClient` par cible, distingués par `@Qualifier` (pattern de routage recommandé officiellement). Ex : `localClient` (Ollama), `cheapCloudClient`, `premiumClient` |
| 3.3 | Classifieur de complexité — V1 | Heuristiques pures : longueur, détection de bloc de code, mots-clés (« refactor », « démontre », « architecture »). Retourne un palier. Zéro coût, zéro latence |
| 3.4 | `RoutingService` | Classifieur → sélection du `ChatClient`. Intégré dans le flux après le miss de cache |
| 3.5 | Classifieur — V2 | Appel à un petit modèle local/bon marché renvoyant un label JSON (`Structured Outputs` Spring AI → POJO). Règles et seuils configurables à chaud |

---

## Phase 4 — Pilier C : green inference & reporting

Objectif : mesurer, minimiser et restituer l'empreinte carbone.

| Tâche | Détail | Composants |
|---|---|---|
| 4.1 | Modèle carbone | tokens → kWh estimés (coefficient par modèle) → gCO2 via intensité de la grille. Démarrer avec des coefficients régionaux statiques |
| 4.2 | Intensité temps réel | Interface `CarbonIntensityProvider` (swappable/mockable) branchée sur une API type ElectricityMaps / WattTime. L'abstraction te protège du couplage et facilite les tests |
| 4.3 | Persistance | Coût + carbone par requête. Calculer le **« CO2 évité »** = (émission d'un appel premium-par-défaut) − (émission réelle après cache + routage). C'est ce chiffre qui vend la solution |
| 4.4 | Routage temporel/géo | Endpoint asynchrone (`@Async` + file) : un worker `@Scheduled` dépile et dispatche vers la région où l'intensité carbone est la plus basse au moment de l'exécution |
| 4.5 | API de reporting | Agrégats (€ économisés, gCO2 évités, taux de hit cache, mix de modèles) par plage de dates. **Export CSV/PDF** clé en main pour les départements RSE (angle CSRD) |

---

## Phase 5 — Dashboard

Objectif : administration + visualisation métier.

### Choix technologique : **Svelte + Vite (SPA statique)**

Décision assumée et **cohérente avec le thème green** : parmi React/Angular/Vue/Svelte,
**Svelte** est le plus léger (compilateur, pas de runtime ni de virtual DOM → bundle le plus
petit, moins de JS à parser/exécuter → moins d'énergie côté client). On reste sur **Svelte +
Vite en SPA statique** (pas SvelteKit : pas de runtime Node, on ne sert que des assets statiques).
Pour les graphiques : lib légère (**uPlot** ou SVG natif), surtout **pas** Chart.js/D3.

### Mono-repo (adapter l'existant, pas de second dépôt)

```
src/main/
├── java/…                ← back Spring Boot (inchangé)
├── resources/…           ← config back
└── frontend/             ← app Svelte + Vite (nouveau)
    ├── package.json
    ├── vite.config.ts    ← build.outDir → ../../../target/classes/static ; proxy /v1 en dev
    └── src/…
```

- **Build orchestré par Maven** via `frontend-maven-plugin` : installe un Node *local* (épinglé,
  reproductible, pas de Node global), lance `npm ci` + `npm run build`. Vite émet les assets dans
  `target/classes/static` → **embarqués dans le jar**, servis par Spring Boot. Un seul
  `./mvnw package` produit un livrable auto-suffisant (cohérent avec l'**on-premise**).
- **`./mvnw test` reste sans Node** : le build front est lié à la phase `prepare-package` (après
  `test`), derrière un profil **désactivable** (`-DskipFrontend`) pour le travail back pur.
- `.gitignore` : `src/main/frontend/node_modules` et `dist`/sortie de build.

### Serving & sécurité

- Spring sert `index.html` + assets statiques. `SecurityConfig` doit **`permitAll`** le shell
  (`/`, `/assets/**`, `/favicon.ico`) tout en gardant `/v1/**` **authentifié**.
- Le dashboard appelle l'API avec la **clé API en Bearer** (saisie par l'utilisateur, gardée en
  mémoire). Téléchargements CSV/PDF via `fetch` + `Blob` (un `<a href>` ne peut pas porter le header
  d'auth). **Trade-off assumé** : clé dans le navigateur = acceptable pour un outil interne
  auto-hébergé ; une auth par session est une évolution Phase 6.

### Workflow dev

- `npm run dev` → Vite sur `:5173` avec **proxy `/v1` → `localhost:8080`** (hot reload).
- Build prod = `./mvnw package` (front bundlé dans le jar).

| Tâche | Détail |
|---|---|
| 5.0 | **Socle** : projet Svelte+Vite dans `src/main/frontend`, `frontend-maven-plugin`, serving statique + `SecurityConfig`, shell + saisie clé API |
| 5.1 | Admin des clés d'API — CRUD clients/clés (nécessite des endpoints back d'admin) |
| 5.2 | Config du routage — réglage des seuils et règles à chaud |
| 5.3 | Metrics live — € et gCO2 dans le temps, taux de hit, répartition des modèles (consomme `/v1/reports/green`) |
| 5.4 | Rapports — génération et téléchargement des exports CSRD (CSV/PDF déjà en place côté back) |

---

## Phase 6 — Valorisation d'ingénierie (polish)

| Tâche | Détail | Argument portfolio |
|---|---|---|
| 6.1 | Observabilité | Observabilité native Spring AI (tokens, latence, modèle) → Micrometer → Prometheus + Grafana | Maturité MLOps |
| 6.2 | Rate limiting | Bucket4j ou les limites de concurrence natives de Boot 4 | Robustesse |
| 6.3 | Image native GraalVM | Compilation native optionnelle | Démarrage rapide + RAM minimale = cohérent avec le discours « green », double win |
| 6.4 | Exposition MCP ✅ | Spring AI 2.0 intègre MCP dans le cœur : exposer la passerelle comme serveur MCP (voir `docs/mcp.md`) | Différenciation forte |
| 6.5 | Packaging final ✅ | `Dockerfile` multi-stage + `docker-compose.yml` « plug & play » (gateway + pgvector + Ollama), `.env.example`, README + schéma d'archi end-to-end | Démontre l'archi end-to-end |

---

## Notes plateforme (pièges à connaître dès le départ)

**Java 25**
- **Scoped Values** : finalisés, production-ready. À utiliser pour propager le contexte de requête (client, trace) à travers Virtual Threads et chaîne d'advisors.
- **Structured Concurrency** : **encore en preview** (JEP 505, `--enable-preview`, API susceptible de changer). Ne pas en faire dépendre la logique cœur. Pour le fan-out parallèle (ex : worker carbone), rester sur `ExecutorService` de Virtual Threads / `CompletableFuture` pour l'instant.
- Compatibilité : elle descend du **BOM Spring Boot 4**. En générant le projet sur `start.spring.io` et en laissant le BOM gérer les versions, le gros est réglé. Seules les libs hors écosystème Spring (ex : futur client d'API carbone) sont à vérifier à la main sur leur baseline JDK.

**Spring AI 2.0**
- **Builders immutables obligatoires** : `AnthropicChatOptions.builder().model(...).temperature(...).build()` — les setters ont disparu.
- **Jackson 3** : packages déplacés de `com.fasterxml.jackson` vers `tools.jackson`. Vérifie tes désérialisations de DTO OpenAI (ingress).
- **Mémoire conversationnelle** : si tu ajoutes du `ChatMemory`, le `conversationId` explicite est désormais obligatoire (plus de `DEFAULT_CONVERSATION_ID`). Non bloquant pour le MVP.
- **JSpecify / null-safety** : annoté partout — bénéfique, mais sois rigoureux sur l'optionalité.

**PostgreSQL + pgvector**
- Activer l'extension `vector` et créer l'index **HNSW** avec l'opérateur **cosinus** (cohérent avec le seuil de similarité de 0,92). Sans index, la recherche scanne toute la table.
- Dimension de la colonne `vector` = dimension du modèle d'embedding (768 pour `nomic-embed-text`). Bien en-deçà des limites d'indexation de pgvector.

---

## Lien avec ton positionnement

Ce projet coche tes quatre axes de repositionnement d'un coup :
- **Architecture** : pipeline d'advisors, gateway, routage multi-modèles.
- **Orchestration** : arbitrage local/cloud, routage temporel/géographique.
- **Cybersécurité** : on-premise, zéro transit des clés et des données chez un tiers.
- **Traduction métier→technique** : le reporting CSRD est littéralement une contrainte réglementaire transformée en feature technique chiffrable.

Le narratif de démo n'est pas « j'ai branché une API LLM », c'est « j'ai réduit le coût et l'empreinte carbone de l'IA d'entreprise, sans compromettre la confidentialité, et je le prouve en euros et en gCO2 ».
