# Configuration Maven - Résolution des problèmes de dépendances

## Problèmes résolus

1. **Spring Boot version** : Changé de 3.4.2 (inexistante) à 3.3.5 (stable)
2. **Dépendance imageio-core** : Supprimée (ImageIO est inclus dans le JDK)
3. **Dépôts Maven** : Ajouté Maven Central comme dépôt principal

## Si le dépôt LangChain4j n'est toujours pas accessible

### Option 1 : Vérifier votre connexion Internet
Assurez-vous que votre connexion Internet fonctionne et que le pare-feu ne bloque pas l'accès.

### Option 2 : Utiliser un proxy Maven (si vous êtes derrière un proxy)
Créez ou modifiez `~/.m2/settings.xml` :

```xml
<settings>
    <proxies>
        <proxy>
            <id>my-proxy</id>
            <active>true</active>
            <protocol>http</protocol>
            <host>proxy.example.com</host>
            <port>8080</port>
        </proxy>
    </proxies>
</settings>
```

### Option 3 : Forcer la mise à jour des dépendances
Exécutez Maven avec l'option `-U` pour forcer la mise à jour :

```bash
mvn clean install -U
```

### Option 4 : Vérifier l'URL du dépôt LangChain4j
Si le dépôt n'est toujours pas accessible, vérifiez l'URL officielle sur :
- https://github.com/langchain4j/langchain4j
- https://docs.langchain4j.dev

## Commandes utiles

```bash
# Nettoyer et compiler
mvn clean compile

# Installer avec mise à jour forcée
mvn clean install -U

# Télécharger les dépendances uniquement
mvn dependency:resolve

# Voir les dépendances non résolues
mvn dependency:tree
```
