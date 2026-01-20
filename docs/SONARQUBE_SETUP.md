# SonarQube Setup Guide

This project is configured for SonarQube code quality analysis.

## Prerequisites

1. **SonarQube Server**: You need access to a SonarQube server instance (self-hosted or SonarCloud)
2. **Authentication Token**: Generate a token from your SonarQube server

## Configuration

### Option 1: Using Environment Variables (Recommended for CI/CD)

Set the following environment variables:

```bash
export SONAR_TOKEN=your-sonar-token
export SONAR_HOST_URL=https://your-sonar-server.com
```

### Option 2: Using gradle.properties (Local Development)

Add to `gradle.properties` (this file is gitignored):

```properties
systemProp.sonar.host.url=https://your-sonar-server.com
systemProp.sonar.token=your-sonar-token
```

### Option 3: Using Command Line Arguments

```bash
./gradlew sonar -Dsonar.host.url=https://your-sonar-server.com -Dsonar.token=your-token
```

## Running SonarQube Analysis

### Full Analysis (with tests)

```bash
./gradlew clean build sonar
```

### Analysis without running tests

```bash
./gradlew clean compileJava sonar
```

### Analysis with coverage (if using JaCoCo)

First, add JaCoCo plugin and configure test coverage, then:

```bash
./gradlew clean test jacocoTestReport sonar
```

## Project Configuration

The SonarQube configuration is defined in:

- **`build.gradle`**: Main configuration using the SonarQube Gradle plugin
- **`sonar-project.properties`**: Alternative configuration file (used when running sonar-scanner directly)

### Key Configuration Points

- **Project Key**: `tosspaper-email-engine`
- **Java Version**: 21
- **Source Encoding**: UTF-8
- **Excluded Paths**: Generated code, build artifacts, JOOQ-generated classes
- **Modules**: All sub-projects are included as modules

### Exclusions

The following are excluded from analysis:
- Generated code (`**/generated/**`)
- Build artifacts (`**/build/**`, `**/bin/**`)
- JOOQ generated classes (`**/jooq/**`)
- Compiled classes (`**/*.class`)

## SonarCloud Setup (Alternative to Self-Hosted)

If using SonarCloud instead of self-hosted SonarQube:

1. Sign up at [sonarcloud.io](https://sonarcloud.io)
2. Create a new project
3. Get your organization key and project key
4. Generate an authentication token
5. Update configuration:

```bash
export SONAR_TOKEN=your-sonarcloud-token
export SONAR_HOST_URL=https://sonarcloud.io
export SONAR_ORGANIZATION=your-org-key
```

Or add to `gradle.properties`:

```properties
systemProp.sonar.host.url=https://sonarcloud.io
systemProp.sonar.token=your-sonarcloud-token
systemProp.sonar.organization=your-org-key
```

## CI/CD Integration

### GitHub Actions Example

```yaml
- name: Run SonarQube Scan
  env:
    SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
    SONAR_HOST_URL: ${{ secrets.SONAR_HOST_URL }}
  run: ./gradlew clean build sonar
```

### GitLab CI Example

```yaml
sonarqube:
  script:
    - ./gradlew clean build sonar
  variables:
    SONAR_TOKEN: $SONAR_TOKEN
    SONAR_HOST_URL: $SONAR_HOST_URL
```

## Troubleshooting

### "No files to analyze"

- Ensure source files are in the correct directories (`src/main/java`, `src/test/groovy`)
- Check that exclusions aren't too broad
- Verify the project structure matches the module configuration

### Authentication Errors

- Verify your token is valid and has the correct permissions
- Check that `SONAR_HOST_URL` is correct
- Ensure the token hasn't expired

### Coverage Not Showing

- Ensure tests are run before the sonar task
- Configure JaCoCo plugin if using test coverage
- Check that coverage reports are generated in the expected location

## Additional Resources

- [SonarQube Gradle Plugin Documentation](https://docs.sonarqube.org/latest/analysis/scan/sonarscanner-for-gradle/)
- [SonarCloud Documentation](https://docs.sonarcloud.io/)
- [SonarQube Quality Gates](https://docs.sonarqube.org/latest/user-guide/quality-gates/)





