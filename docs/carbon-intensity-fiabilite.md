# Intensité carbone des zones : méthode & fiabilité

Note de référence sur **comment on connaît l'intensité carbone d'une zone**, **comment
c'est calculé en amont**, et **quelles sont les limites de fiabilité**. À garder sous la
main pour la présentation du projet et pour la Phase 4.5 (reporting type CSRD).

Lié à : `CarbonIntensityProvider`, `CarbonAwareZoneSelector`, `ElectricityMapsCarbonIntensityProvider`,
`StaticCarbonIntensityProvider`, modèle carbone (Phase 4.1) et dispatch (Phase 4.4).

---

## 1. Comment on obtient la donnée (dans le projet)

Deux sources derrière la même abstraction `CarbonIntensityProvider` :

- **Statique** (`gatewai.carbon.zone-intensities`) : valeurs en dur (FR 56, SE 30, DE 380,
  PL 650 gCO2/kWh). **Démo uniquement** — ordres de grandeur réalistes mais figés.
- **ElectricityMaps** (temps réel) : `GET /carbon-intensity/latest?zone=XX` →
  champ `carbonIntensity` en gCO2eq/kWh. Le `CarbonAwareZoneSelector` prend le **minimum**
  sur les zones candidates.

Côté gateway, « savoir » = *demander à un fournisseur de données*. La vraie question est
comment **lui** le sait.

## 2. Comment ElectricityMaps / WattTime calculent (chaîne physique)

1. **Mix de production en quasi temps réel** publié par les gestionnaires de réseau
   (RTE, ENTSO-E, EIA) : MW par source (charbon, gaz, nucléaire, hydro, éolien, solaire…).
2. **Facteur d'émission cycle de vie** par source (gCO2eq/kWh, fourchettes GIEC) :
   charbon ~820, gaz ~490, solaire ~45, hydro ~24, nucléaire ~12, éolien ~11.
3. **Intensité = somme pondérée** du mix par ces facteurs.
4. **Flux import/export** : *flow-tracing* pour l'électricité *consommée* (pas seulement
   produite) dans la zone — **modélisé, pas mesuré**.

> Même approche que **Google** (Carbon-Intelligent Computing) et **Microsoft** pour décaler
> leurs charges → technique légitime, en production chez les hyperscalers.

## 3. Fiabilité

### Ce qui EST fiable (signal directionnel)
- L'écart **entre zones** est massif et structurel (30 vs 650 gCO2/kWh).
  « La Suède est plus verte que la Pologne » est vrai à tout instant.
- Pour une décision binaire « zone A ou B ? », la moyenne temps réel suffit.

### Ce qui l'est MOINS (pièges)
- **Moyenne vs marginal — piège n°1.** ElectricityMaps donne l'intensité *moyenne* du mix.
  Mais une charge *additionnelle* est servie par la centrale **marginale** (souvent gaz/charbon
  de pointe). Une zone à faible moyenne peut avoir un marginal élevé. **WattTime** vise le
  marginal, qui est le bon signal pour du *load-shifting*. **Notre code utilise la moyenne.**
- **Estimation révisée a posteriori** : latence, données manquantes (zones estimées), corrections.
- **Facteurs cycle de vie incertains** (le solaire varie du simple au triple selon l'étude).
- **Granularité zone, pas datacenter** : PPA solaires / contrats d'origine non reflétés.

## 4. Limites propres à notre implémentation

1. **Pas de relocalisation physique** : on choisit la zone *de comptabilité*, pas une
   exécution multi-régions réelle. Bénéfice **comptable**, pas physique.
2. **Carbone absolu grossier** : intensité grid × coefficients `energyIntensity` (kWh/token)
   qui sont des **placeholders**. Une bonne donnée grid ne sauve pas une estimation
   énergétique approximative en amont.
3. Shifting **temporel** (worker `@Scheduled` qui diffère l'exécution) = **réel**.

## 5. Posture recommandée

- **Présentation portfolio** : « carbon-aware routing, directionnellement correct, même
  méthode que Google/Microsoft » — en assumant les limites.
- **Pour des claims carbone audités (CSRD)**, il faudrait :
  - passer à l'intensité **marginale** (WattTime) ;
  - des **facteurs énergétiques mesurés** (pas les placeholders kWh/token) ;
  - une **exécution multi-régions** réelle (endpoints régionaux) ;
  - une méthodologie documentée et auditable (PUE datacenter, périmètre Scope 2/3).

## Synthèse

| Question | Réponse honnête |
|---|---|
| Sait-on quelle zone est la plus verte ? | Oui — le **classement** est fiable. |
| Les **chiffres absolus** sont-ils fiables ? | Moyennement (marginal ≠ moyenne, facteurs incertains, révisions). |
| Notre implémentation est-elle « vraie » ? | Temporel réel ; géo = comptable ; énergie = placeholders. |
