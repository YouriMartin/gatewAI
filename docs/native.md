# Image native GraalVM (Phase 6.3)

Compilation native **optionnelle**. Le double gain colle au discours green :
**démarrage en dizaines de ms** et **empreinte mémoire fortement réduite** vs la
JVM — moins de ressources = moins d'énergie.

## Builds

Le `spring-boot-starter-parent` fournit le profil `native` (AOT + GraalVM). Deux
chemins :

```bash
# 1. Exécutable natif local — nécessite un JDK GraalVM (ex. liberica-nik)
./mvnw -Pnative native:compile
./target/gatewai

# 2. Conteneur natif via buildpacks — pas de GraalVM local requis
./mvnw -Pnative spring-boot:build-image
docker run --rm -p 8080:8080 gatewai:0.0.1-SNAPSHOT
```

> Le build natif lance d'abord `process-aot` puis `native:compile` : prévoir
> plusieurs minutes et beaucoup de RAM. Le profil `frontend` (actif par défaut)
> bundle le dashboard dans le binaire ; les ressources `static/**` sont incluses
> par les hints natifs de Spring Boot.

## Runtime hints (réflexion)

L'AOT couvre la majorité, mais quelques types (de)sérialisés par réflexion sont
déclarés explicitement :

| Type | Pourquoi | Où |
|---|---|---|
| DTO web (OpenAI, admin, reports…) | binding Jackson contrôleurs | `NativeRuntimeHints` (`@ImportRuntimeHints`) |
| `ClassificationResult` | Structured Output Spring AI | `@RegisterReflectionForBinding` sur `ChatClientConfiguration` |
| `ElectricityMapsResponse` | corps RestClient | `@RegisterReflectionForBinding` sur `CarbonConfiguration` |

Test : `NativeRuntimeHintsTest` vérifie l'enregistrement via
`RuntimeHintsPredicates`.

## Caveats à valider en CI GraalVM

La compilation native complète n'est **pas** exécutée ici (pas de GraalVM dans
l'environnement de dev). À vérifier dans une CI dédiée :

- **OpenPDF** (export PDF) charge des polices/ressources par réflexion ; l'image
  native peut nécessiter des hints ressources supplémentaires
  (`com/lowagie/text/pdf/fonts/**`). Sinon, l'export PDF risque d'échouer au
  runtime natif alors que le JSON/CSV fonctionnent.
- **Hibernate/JPA** : `process-aot` refresh le contexte → la base doit être
  joignable au moment du build (ou utiliser un profil de build sans DataSource).
- Ajouter `org.graalvm.buildtools:native-maven-plugin` reachability metadata
  (déjà branché par le parent via `add-reachability-metadata`).

Statut : **native-ready** (config + hints + doc). Validation de l'image complète
à faire sur un runner GraalVM.
