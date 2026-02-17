---
name: gradle-deps
description: Manage Gradle dependencies using the version catalog (libs.versions.toml). Use when adding, updating, or reviewing dependencies in any build.gradle file. Enforces version catalog usage and prevents hardcoded versions.
allowed-tools:
  - Read
  - Glob
  - Grep
  - Edit
  - Write
---

# Gradle Dependency Management

Manage all dependencies through the Gradle version catalog. Never hardcode versions in build.gradle files.

## CRITICAL RULE: No Hardcoded Versions

**NEVER** write a version string directly in a `build.gradle` file. All versions MUST come from `gradle/libs.versions.toml`.

```groovy
// WRONG - hardcoded version
implementation 'com.fasterxml.jackson.core:jackson-databind:2.15.2'
implementation "org.springframework.boot:spring-boot-autoconfigure:3.5.5"

// CORRECT - version catalog alias
implementation libs.jackson.databind
implementation libs.spring.boot.starter.web

// CORRECT - version catalog version ref with BOM-managed module
implementation "io.swagger.core.v3:swagger-annotations:${libs.versions.swagger.annotations.get()}"

// CORRECT - BOM-managed (no version needed)
implementation 'org.springframework:spring-web'
```

## Version Catalog Structure

The version catalog lives at `gradle/libs.versions.toml` with three sections:

1. **[versions]** — Declare version numbers
2. **[libraries]** — Declare library coordinates with version references
3. **[plugins]** — Declare plugin coordinates with version references

See [VERSION-CATALOG.md](references/VERSION-CATALOG.md) for the full reference.

## How to Add a New Dependency

### Step 1: Check if it already exists

Search the version catalog first:

```
Grep pattern="<artifact-name>" path="gradle/libs.versions.toml"
```

### Step 2: Add to version catalog if missing

Add to `gradle/libs.versions.toml` in the appropriate section:

```toml
[versions]
new-lib = "1.2.3"

[libraries]
new-lib = { module = "com.example:new-lib", version.ref = "new-lib" }
```

### Step 3: Use in build.gradle

```groovy
implementation libs.new.lib
```

## BOM-Managed Dependencies

When a BOM (Bill of Materials) is imported, its managed dependencies need no explicit version.

### RULE: Use `platform()` for New Modules

New modules MUST use Gradle's native `platform()` to import BOMs. Do NOT use the `spring-dep-mgmt` plugin or `dependencyManagement { mavenBom }` block.

```groovy
// WRONG — do not use in new modules
plugins {
    alias(libs.plugins.spring.dep.mgmt)
}
dependencyManagement {
    imports {
        mavenBom "org.springframework.boot:spring-boot-dependencies:..."
    }
}

// CORRECT — use platform() in dependencies
dependencies {
    implementation platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    implementation 'org.springframework:spring-web'  // version resolved by platform
}
```

> **Note:** Existing modules still use `mavenBom` via the root `subprojects` block. Do not refactor existing modules — only apply `platform()` to new ones.

### Spring Boot BOM

These modules have their versions managed by the Spring Boot BOM (no version needed):

```groovy
implementation 'org.springframework:spring-web'
implementation 'org.springframework:spring-context'
implementation 'jakarta.annotation:jakarta.annotation-api'
implementation 'jakarta.servlet:jakarta.servlet-api'
implementation 'org.hibernate.validator:hibernate-validator'
implementation 'com.fasterxml.jackson.core:jackson-annotations'
implementation 'com.fasterxml.jackson.core:jackson-databind'
```

Only add explicit versions for libraries NOT managed by a BOM:

```groovy
// Not in Spring Boot BOM — must use version catalog
implementation "io.swagger.core.v3:swagger-annotations:${libs.versions.swagger.annotations.get()}"
implementation "jakarta.validation:jakarta.validation-api:${libs.versions.jakarta.validation.get()}"
```

See [BOM.md](references/BOM.md) for BOM patterns and which dependencies are managed.

## Plugin Management

Declare plugins in the version catalog and reference with `alias()`:

```groovy
// WRONG
plugins {
    id 'io.spring.dependency-management' version '1.1.7'
}

// CORRECT
plugins {
    alias(libs.plugins.spring.dep.mgmt)
}
```

## Quick Checklist

Before submitting any build.gradle change:

1. No version strings appear in build.gradle (except `version = findProperty(...)`)
2. New dependencies have a corresponding entry in `libs.versions.toml`
3. BOM-managed dependencies have no version specified
4. New modules use `platform()` — not `mavenBom`
5. Plugins use `alias(libs.plugins.*)` syntax
6. Version references use `libs.versions.*.get()` when interpolated in strings
