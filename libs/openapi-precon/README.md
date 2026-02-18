# OpenAPI Precon

Centralized OpenAPI specification for the Precon service. Generates and publishes both a **Java library** and a **TypeScript client** to GitHub Packages, so consumers get type-safe, versioned API clients instead of manually sharing or copying spec files.

## Why

Previously, API specs were shared ad hoc between services — copied into repos, pasted into tools, or referenced by raw file paths. This created drift between what the API actually exposed and what consumers coded against. Changes to an endpoint required manually updating every consumer.

This module solves that by making the OpenAPI spec the single source of truth:

- The spec lives in `specs/precon/openapi-precon.yaml`
- CI validates, generates clients, and publishes versioned packages on every merge to `main`
- Consumers depend on a specific version — updates are explicit, not silent

## Published Packages

| Platform | Package | Registry |
|----------|---------|----------|
| Java/Kotlin | `com.tosspaper:tosspaper-openapi-precon` | GitHub Maven Packages |
| TypeScript | `@build4africa/tosspaper-openapi-precon` | GitHub npm Packages |

Both packages are always published at the same version, defined in `gradle.properties`.

## Usage

### Authentication

GitHub Packages requires authentication to pull packages.

1. Go to https://github.com/settings/tokens/new
2. Select **classic** token
3. Set an expiration
4. Select scopes: `read:packages`
5. Click **Generate token** and copy it

Export the token:

```sh
export GITHUB_TOKEN=ghp_your_token_here
```

### TypeScript

Configure npm to use GitHub Packages for the `@build4africa` scope:

```sh
echo "@build4africa:registry=https://npm.pkg.github.com" >> .npmrc
echo "//npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}" >> .npmrc
```

Install:

```sh
npm install @build4africa/tosspaper-openapi-precon@0.2.0
```

Use the generated client:

```typescript
import { TendersApi, Configuration } from '@build4africa/tosspaper-openapi-precon';

const config = new Configuration({ basePath: 'https://api.tosspaper.com' });
const tendersApi = new TendersApi(config);

const tenders = await tendersApi.listTenders();
```

### Java / Kotlin (Gradle)

Add the GitHub Maven registry to your `build.gradle`:

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/Build4Africa/tosspaper")
        credentials {
            username = System.getenv('GITHUB_ACTOR')
            password = System.getenv('GITHUB_TOKEN')
        }
    }
}
```

Add the dependency:

```groovy
implementation 'com.tosspaper:tosspaper-openapi-precon:0.2.0'
```

The Java library provides Spring server interfaces and model classes:

```java
import com.tosspaper.precon.generated.model.Tender;
import com.tosspaper.precon.generated.api.TendersApi;
```

## Versioning

Version is managed in `gradle.properties` (`moduleVersion=X.Y.Z`). This is the single source of truth — the build injects it into both the Maven artifact and the npm package.

To release a new version:

1. Bump `moduleVersion` in `gradle.properties`
2. Add an entry to `CHANGELOG.md` under `## [X.Y.Z]`
3. Open a PR — CI validates that the version is valid semver and the changelog entry exists
4. Merge to `main` — CI builds and publishes both packages

## Project Structure

```
specs/precon/
  openapi-precon.yaml          # The OpenAPI specification (source of truth)
  .redocly.yaml                # Linting rules

libs/openapi-precon/
  build.gradle                 # Java codegen + TypeScript build + publishing
  gradle.properties            # Module version (single source of truth)
  package.json                 # npm package template (version injected at build)
  tsconfig.json                # TypeScript compiler config
  CHANGELOG.md                 # Release history
```

## CI Workflow

The `publish-openapi-specs.yml` workflow triggers on changes to `specs/**`, `libs/openapi-precon/**`, or the workflow file itself.

1. **Validate** — checks semver format, changelog entry, and lints the spec with Redocly
2. **Build** — generates Java stubs and TypeScript-Axios client, compiles both
3. **Publish** (main only) — publishes Maven artifact and npm package to GitHub Packages
