# Exposition MCP (Phase 6.4)

La passerelle s'expose comme **serveur MCP** (Model Context Protocol) en plus de
son ingress REST OpenAI. Un assistant compatible MCP (Claude Desktop, IDE, agent)
peut ainsi piloter le proxy : compléter un prompt **routé/caché/comptabilisé** et
lire l'empreinte coût + carbone — le tout sans transiter par un tiers.

## Architecture

MCP est un **second ingress**, traité comme le web OpenAI : un adapter entrant
`com.example.gatewai.adapter.in.mcp` qui ne contient aucune logique métier et
réutilise les ports `in`/`out` existants. La chaîne d'advisors verte (cache →
routeur → comptabilité) reste l'unique source de vérité.

```
adapter/in/mcp
├── GatewayMcpTools          # méthodes @Tool (ingress MCP)
├── McpServerConfiguration   # ToolCallbackProvider + hints natifs
├── *ToolResult              # records exposés (schéma JSON des outils)
└── McpNativeRuntimeHints    # reflection hints GraalVM
```

- Dépendance : `spring-ai-starter-mcp-server-webmvc` (transport streamable-HTTP
  réutilisant Spring MVC, pas de serveur séparé).
- Les `@Tool` sont publiés via un bean `ToolCallbackProvider`
  (`MethodToolCallbackProvider`) découvert par l'auto-configuration du starter.
- Validé par `ArchitectureTest` (ArchUnit) comme adapter de la couche onion.

## Outils exposés

| Outil | Rôle | Port sous-jacent |
|---|---|---|
| `routed_chat` | Complète un prompt à travers la passerelle ; renvoie la réponse, **le modèle réellement choisi**, le cache-hit et les tokens | `ChatCompletionUseCase` |
| `green_report` | Coût (€) et empreinte (gCO2) agrégés sur une plage ISO-8601 (par défaut 30 j), + coût/CO2 évités, taux de hit, mix modèles | `GenerateGreenReportUseCase` |
| `carbon_intensity` | Intensité carbone réseau courante (gCO2-eq/kWh) pour une zone | `CarbonIntensityProvider` |

## Configuration

`application.properties` :

```properties
spring.ai.mcp.server.enabled=true
spring.ai.mcp.server.name=gatewai-green-proxy
spring.ai.mcp.server.version=0.1.0
spring.ai.mcp.server.instructions=...
```

Mettre `spring.ai.mcp.server.enabled=false` pour désactiver complètement
l'exposition MCP.

## Sécurité

L'endpoint `/mcp` est protégé par le **même** `ApiKeyAuthenticationFilter` que
`/v1/**` : le client MCP envoie `Authorization: Bearer <clé API gatewai>`. Une clé
valide lie aussi le `RequestContext` (clientId), donc la comptabilité verte et le
namespacing du cache fonctionnent à l'identique sur le canal MCP. Cohérent avec la
posture on-premise zéro-transit du projet.

## Exemple de client (Claude Desktop)

```json
{
  "mcpServers": {
    "gatewai": {
      "url": "http://localhost:8080/mcp",
      "headers": { "Authorization": "Bearer <votre-clé-API-gatewai>" }
    }
  }
}
```

## Image native (6.3)

Les records de résultat des outils sont (dé)sérialisés par réflexion par MCP :
`McpNativeRuntimeHints` enregistre leurs binding hints pour GraalVM, à l'image de
`NativeRuntimeHints` côté web.
