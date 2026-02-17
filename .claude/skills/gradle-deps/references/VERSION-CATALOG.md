# Version Catalog Reference

The version catalog at `gradle/libs.versions.toml` is the single source of truth for all dependency versions.

## File Location

```
gradle/libs.versions.toml
```

## TOML Sections

### [versions]

Declare version strings. Use kebab-case names:

```toml
[versions]
spring-boot = "3.5.5"
jackson = "2.20.1"
swagger-annotations = "2.2.26"
```

### [libraries]

Declare library coordinates. Reference versions with `version.ref`:

```toml
[libraries]
swagger-annotations = { module = "io.swagger.core.v3:swagger-annotations", version.ref = "swagger-annotations" }
jackson-datatype-jsr310 = { module = "com.fasterxml.jackson.datatype:jackson-datatype-jsr310", version.ref = "jackson" }
```

### [plugins]

Declare Gradle plugin coordinates:

```toml
[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dep-mgmt = { id = "io.spring.dependency-management", version.ref = "spring-dep-mgmt" }
openapi-generator = { id = "org.openapi.generator", version.ref = "openapi-generator" }
```

## Usage in build.gradle

### Libraries

TOML names map to Groovy accessors with dots replacing hyphens:

| TOML key | Groovy accessor |
|----------|-----------------|
| `spring-boot-starter-web` | `libs.spring.boot.starter.web` |
| `swagger-annotations` | `libs.swagger.annotations` |
| `jackson-datatype-jsr310` | `libs.jackson.datatype.jsr310` |

```groovy
dependencies {
    implementation libs.swagger.annotations
    implementation libs.jackson.datatype.jsr310
    testImplementation libs.spock.core
}
```

### Version References

When you need a version string (e.g., for BOM imports or string interpolation):

```groovy
// Access the raw version string
mavenBom "org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"

// In dependency declarations with string interpolation
implementation "io.swagger.core.v3:swagger-annotations:${libs.versions.swagger.annotations.get()}"
```

**IMPORTANT**: Always use `.get()` when accessing version values. Without it, you get a Provider object instead of the version string.

### Plugins

```groovy
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
    alias(libs.plugins.openapi.generator)
}
```

## Naming Conventions

- Use **kebab-case** in TOML keys: `spring-boot-starter-web`
- Group related libraries by comment sections in the TOML file
- Use the same version ref for related libraries (e.g., all `jackson-*` use `jackson` version)

## Adding a New Dependency

1. Add version to `[versions]` if not already present
2. Add library entry to `[libraries]` with `version.ref`
3. Use `libs.<name>` in build.gradle
4. Run `./gradlew build -x test` to verify resolution

## Common Mistakes

### Using `.get()` in the wrong place

```groovy
// WRONG — .get() is only for version strings
implementation libs.swagger.annotations.get()

// CORRECT — library aliases are used directly
implementation libs.swagger.annotations

// CORRECT — .get() on version providers
implementation "io.swagger.core.v3:swagger-annotations:${libs.versions.swagger.annotations.get()}"
```

### Mixing catalog and hardcoded versions

```groovy
// WRONG — defeats the purpose of the catalog
implementation "com.fasterxml.jackson.core:jackson-databind:2.15.2"

// CORRECT — use the catalog entry
implementation libs.jackson.databind

// OR if no catalog entry exists, add one first in libs.versions.toml
```
