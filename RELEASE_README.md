## Download

```groovy
implementation 'it.ideasolutions:signalr-client-android:1.3.0-SNAPSHOT'
```

## Rilascio su repo Maven aziendale (Artifactory)
Per utilizzare il repository maven aziendale nei progetti gradle si raccomanda la creazione di un file `gradle.properties` nella gradle home ovvero in `~/.gradle/gradle.properties`

All'interno di questo file aggiungere le seguenti property richieste dagli script gradle utilizzati per il rilascio:
```
IS_ARTIFACTORY_BASE_URL=https://artifactory.ideasolutions.it/
IS_ARTIFACTORY_PUBLISH_BASE_URL=https://artifactory.ideasolutions.it/
IS_ARTIFACTORY_USERNAME=username
IS_ARTIFACTORY_PASSWORD=password
```

Per rilasciare seguire i seguenti step:
1. aggiornare la versione `VERSION_NAME` in `gradle.properties` del progetto e nel `README.md`
2. committare su repo git e taggare con la versione ad esempio `1.2.1`
3. lanciare i comandi da terminale `./gradlew clean uploadArchives`
4. aggiornare nuovamente `VERSION_NAME` con il modifier `-SNAPSHOT` sulla prossima minor version e committare. Ad esempio se si è rilasciata la versione `1.2.1` fin tanto che si è in fase di sviluppo la versione sarà `1.3.0-SNAPSHOT`
