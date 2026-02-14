# DataGrip Helpers

A DataGrip plugin that simplifies database connection management. Opinionated, by https://sandstorm.de

## Features

### 🐳 Docker Compose Auto-DataSource

Automatically creates DataGrip data sources from your docker-compose files.

**Supported Databases:**
- PostgreSQL
- MySQL
- MariaDB

**How it works:**
1. **On project open**: Scans for `docker-compose*.yml/yaml` files (up to 2 directory levels deep)
2. **On file changes**: Detects when docker-compose files are modified
3. **Auto-creates data sources**: Extracts credentials from environment variables and port mappings

**Example:**
```yaml
services:
  postgres:
    image: postgres:14
    ports:
      - "15432:5432"
    environment:
      POSTGRES_DB: mydb
      POSTGRES_USER: admin
      POSTGRES_PASSWORD: secret123
```

Creates data source: **Docker: postgres** → `jdbc:postgresql://localhost:15432/mydb`

Each data source includes a comment noting it was auto-created from the docker-compose file.

### 📟 Command Line Interface

Open database connections from the command line:

```bash
datagrip opensql postgresql jdbc:postgresql://localhost:5432/postgres admin password "My DB"
```

**Supported drivers:** postgresql, mysql, mariadb, and all other DataGrip-supported drivers

## Development

### Building
```bash
./gradlew build
```

### Running
```bash
./gradlew runIde
```

### Verifying Plugin Compatibility

```bash
./gradlew verifyPlugin
```

**Note:** The `verifyPlugin` task requires IDE versions to be configured in `build.gradle.kts`. To verify against recommended IDE versions, ensure the `intellijPlatform.pluginVerification.ides` block is configured.

### Building Distribution JAR

Build the plugin distribution file:
```bash
./gradlew buildPlugin
```

The plugin JAR will be located at:
```
build/distributions/datagrip-helpers-*.zip
```

### Publishing to JetBrains Marketplace

**Option 1: Gradle Task (Automated)**

Set up plugin signing and authentication:
```bash
# Add to gradle.properties or environment variables
PUBLISH_TOKEN=your_jetbrains_marketplace_token
```

Publish the plugin:
```bash
./gradlew publishPlugin
```

**Option 2: Manual Upload (Recommended for first release)**

1. Build the distribution: `./gradlew buildPlugin`
2. Go to [JetBrains Plugin Repository](https://plugins.jetbrains.com/plugin/upload)
3. Sign in with your JetBrains account
4. Click "Upload Plugin"
5. Select the ZIP file from `build/distributions/`
6. Fill in plugin details (description, changelog, etc.)
7. Submit for review

**Plugin Submission Guidelines:**
- Follow [JetBrains Marketplace Quality Guidelines](https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html)
- Include clear description and screenshots
- Provide comprehensive changelog for updates
- Test thoroughly before submission

**Marketplace Token:**
To get a publish token:
1. Go to [JetBrains Account](https://account.jetbrains.com/hub/)
2. Navigate to Profile → Access Tokens
3. Create new token with "Marketplace" scope
4. Use token in `gradle.properties` or environment variable

### References & Prior Work

**Feature Requests:**
- [Ability to connect to a database from the command line](https://intellij-support.jetbrains.com/hc/en-us/community/posts/115000180404-Ability-to-connect-to-a-database-from-the-command-line)
- [YouTrack: DBE-7685](https://youtrack.jetbrains.com/issue/DBE-7685)

**Targeting DataGrip:**
- [Configuring Plugin Projects Targeting DataGrip](https://plugins.jetbrains.com/docs/intellij/data-grip.html#configuring-plugin-projects-targeting-datagrip)

**CLI Integration:**
- Uses `com.intellij.appStarter` extension point ([docs](https://plugins.jetbrains.com/docs/intellij/extension-point-list.html#langextensionpointsxml))
- Example: [FormatterStarter.java](https://github.com/Mizzlr/intellij-community/blob/7e1217822045325b2e9269505d07c65daa9e5e9d/platform/lang-impl/src/com/intellij/formatting/commandLine/FormatterStarter.java)

**DataSource Creation API:**

```kotlin
// Get driver instance
val instance = DatabaseDriverManagerImpl.getInstance()
val driver = instance.getDriver("postgresql")

// Create data source
val ds = LocalDataSource()
ds.name = "Test Data Source"
ds.databaseDriver = driver
ds.url = "jdbc:postgresql://localhost:5432/test_database"
ds.username = "user"

// Add to project
LocalDataSourceManager.getInstance(project).addDataSource(ds)

// Navigate to data source
val dbDataSource = DbImplUtil.getDbDataSource(project, ds)
if (dbDataSource != null) {
    OpenSourceUtil.navigate(true, true, dbDataSource)
}
```

**Community Discussions:**
- [Is it possible to create a data source via an API?](https://intellij-support.jetbrains.com/hc/en-us/community/posts/115000690844-Is-it-possible-to-create-a-data-source-via-an-API-) (2021)
- [YouTrack: IJSDK-328](https://youtrack.jetbrains.com/issue/IJSDK-328) (2017)

## License

MIT License
