# UniversityAdmissions.se Scraper

A small Java app that scrapes master-level programs from UniversityAdmissions.se, parses key fields into a `Program` record, and stores the results in PostgreSQL. It can run as a JVM app or be compiled into a single native binary with GraalVM.


## Features

- Scrapes the search results page (default):
  ```
  https://www.universityadmissions.se/intl/search?type=programs&advancedLevel=true&period=27&sortBy=creditAsc&subjects=120-&subjects=130-40-&subjects=130-180-&subjects=130-50-&numberOfFetchedPages=2
  ```
  You can override the URL via the first CLI argument.
- Extracts:
  - `title`, `creditCount`, `university`, `location`, `status`
  - `firstTuitionFee`, `totalTuitionFee`
  - `period`, `level`, `languageOfInstruction`, `applicationCode`
  - `teachingForm`, `paceOfStudy`, `instructionalTime`
  - `subjectAreas[]`
- Saves to PostgreSQL: database `se_edu`, empty username, no password, host `localhost`.
- Creates a daily table with today’s date: `"programs_YYYY-MM-DD"` (quoted because of the dashes).
- Adds `created_at TIMESTAMP NOT NULL` per row.


## Requirements

- **Java 21** (Temurin/OpenJDK/GraalVM)
- **Maven 3.9+**
- **PostgreSQL 14+** running locally with:
  - DB: `se_edu`
  - User: empty (no password)
- Internet access from the machine (to fetch the page).


## Project Layout

```
.
├── pom.xml
└── src/main/java/…/App.java
```

The main class is `me.fekri.scrapper.App`.


## Configuration

- **Default URL** is embedded in `App.java`.  
  To use another search URL, pass it as the **first argument**:
  ```bash
  java -jar target/UniversityAdmissionsSeScrapper-1.0.0.jar "https://www.universityadmissions.se/intl/search?...your params..."
  ```
- **Database:** hardcoded to `jdbc:postgresql://localhost:5432/se_edu`, user empty, password empty.  
  If you need credentials, adjust the connection in `saveToPostgres(...)`.

Create DB & user (if needed):
```sql
CREATE DATABASE se_edu;
CREATE USER se_edu;
GRANT ALL PRIVILEGES ON DATABASE se_edu TO se_edu;
```


## Build & Run (JVM)

### 1) Package
```bash
mvn -DskipTests package
```

> We use the **maven-shade-plugin** to produce a runnable fat jar that includes dependencies.

### 2) Run
```bash
# default URL
java -jar target/UniversityAdmissionsSeScrapper-1.0.0.jar

# custom URL (as first argument)
java -jar target/UniversityAdmissionsSeScrapper-1.0.0.jar "https://www.universityadmissions.se/intl/search?...your params..."
```

On success, you’ll see logs about parsed programs.
```
[main] INFO me.fekri.scrapper.App - Fetching...
[main] INFO me.fekri.scrapper.App - Parsing...
[main] INFO me.fekri.scrapper.App -   5 No tuition info
[main] INFO me.fekri.scrapper.App -   0 Ineligibles
[main] INFO me.fekri.scrapper.App -  54 Parsed programs
[main] INFO me.fekri.scrapper.App - Done.
```


## Build a Native Binary (GraalVM)

You can build a single executable (no JVM needed) using GraalVM.

### Option A — Maven profile (`mvn -Pnative native:compile`) ✅

Ensure your `pom.xml` contains the **GraalVM Native Build Tools** plugin and a `native` profile. Then:

1) Point your shell to GraalVM 21.0.8:
```bash
export JAVA_HOME="/path/to/graalvm-jdk-21.0.8/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

2) Compile native:
```bash
mvn -Pnative native:compile -DskipTests
```

3) Run the binary:
```bash
./target/ua-scraper
# or with custom URL
./target/ua-scraper "https://www.universityadmissions.se/intl/search?...your params..."
```

### Option B — Call `native-image` directly

If you prefer manual control (and have `native-image` installed via `gu install native-image`):

```bash
mvn -DskipTests package

native-image   --no-fallback   --enable-url-protocols=http,https   -H:+ReportExceptionStackTraces   -H:Name=ua-scraper   -jar target/UniversityAdmissionsSeScrapper-1.0.0.jar

# Run
./ua-scraper
```

> If you hit Postgres driver init issues, add:
> ```
> --initialize-at-run-time=org.postgresql.Driver
> ```


## Example SQL

Show latest rows:
```sql
SELECT title, application_code, created_at
FROM "programs_2025-08-17"
ORDER BY created_at DESC, title ASC
LIMIT 20;
```

Find by subject:
```sql
SELECT title, subject_areas
FROM "programs_2025-08-17"
WHERE 'Computer Science' = ANY(subject_areas);
```


## License
MIT License


## Credits

- HTML parsing via **jsoup**
- PostgreSQL JDBC driver
- GraalVM Native Image (optional)
