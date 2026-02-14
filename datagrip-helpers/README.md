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
