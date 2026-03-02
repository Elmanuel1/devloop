# BOM (Bill of Materials) Dependency Management

BOMs centrally manage versions for a set of related dependencies. When a BOM is imported, its managed dependencies need no explicit version in build.gradle.

## Spring Boot BOM

### New modules: use `platform()`

New modules MUST use Gradle's native `platform()`:

```groovy
dependencies {
    implementation platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
}
```

### Existing modules (legacy)

Existing modules use the `spring-dep-mgmt` plugin via the root `subprojects` block:

```groovy
apply plugin: 'io.spring.dependency-management'
dependencyManagement {
    imports {
        mavenBom "org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"
    }
}
```

> Do not add `mavenBom` to new modules. Do not refactor existing modules.

### BOM-Managed Dependencies (No Version Needed)

These modules have their versions managed by the Spring Boot BOM:

**Spring Framework:**
- `org.springframework:spring-web`
- `org.springframework:spring-context`
- `org.springframework:spring-tx`
- `org.springframework:spring-jdbc`

**Jakarta:**
- `jakarta.annotation:jakarta.annotation-api`
- `jakarta.servlet:jakarta.servlet-api`

**Jackson:**
- `com.fasterxml.jackson.core:jackson-annotations`
- `com.fasterxml.jackson.core:jackson-databind`
- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310`

**Validation:**
- `org.hibernate.validator:hibernate-validator`

**Spring Boot Starters:**
- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.boot:spring-boot-starter-test`
- `org.springframework.boot:spring-boot-starter-data-jpa`
- All other `spring-boot-starter-*` modules

### NOT BOM-Managed (Version Required)

These libraries are NOT in the Spring Boot BOM and need explicit versions from the catalog:

```groovy
// Must use version catalog
implementation "io.swagger.core.v3:swagger-annotations:${libs.versions.swagger.annotations.get()}"
implementation "jakarta.validation:jakarta.validation-api:${libs.versions.jakarta.validation.get()}"
implementation libs.mapstruct
implementation libs.resilience4j.spring.boot3
```

## How to Check if a Dependency is BOM-Managed

1. Run `./gradlew :libs:<module>:dependencies --configuration compileClasspath`
2. Look for the dependency in the output — if it shows a resolved version without you specifying one, it's BOM-managed
3. If the build fails with "Could not resolve" when you remove the version, it's NOT BOM-managed

## Additional BOMs in This Project

### Spring Cloud AWS BOM

```groovy
dependencyManagement {
    imports {
        mavenBom "io.awspring.cloud:spring-cloud-aws-dependencies:${libs.versions.spring.cloud.aws.get()}"
    }
}
```

Manages: `spring-cloud-aws-starter-parameter-store` and other `io.awspring.cloud` modules.

### Spring Cloud BOM

```groovy
dependencyManagement {
    imports {
        mavenBom "org.springframework.cloud:spring-cloud-dependencies:${libs.versions.spring.cloud.get()}"
    }
}
```

Manages: `spring-cloud-starter-bootstrap` and other `org.springframework.cloud` modules.
