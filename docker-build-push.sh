#!/bin/bash
set -e

# === Configuration ===
LOCAL_IMAGE="tosspaper"
ORG="build4africa"          # must be lowercase for GHCR
REPO="tosspaper"
REGISTRY="ghcr.io"
ENV_FILE="springboot_dev.env"

# === 0️⃣ Increment version in env file ===
echo "Reading current version from $ENV_FILE..."
CURRENT_VERSION=$(grep "^APP_VERSION=" "$ENV_FILE" | cut -d'=' -f2)

if [ -z "$CURRENT_VERSION" ]; then
    echo "❌ Could not find APP_VERSION in $ENV_FILE"
    exit 1
fi

# Increment patch version (e.g., 0.1.9 -> 0.1.10)
NEW_VERSION=$(echo "$CURRENT_VERSION" | awk -F. '{
    if (NF == 3) {
        printf "%d.%d.%d", $1, $2, $3+1
    } else if (NF == 2) {
        printf "%d.%d", $1, $2+1
    } else {
        printf "%d", $1+1
    }
}')

# Always append -snapshot
VERSION="${NEW_VERSION}-snapshot"

echo "Current version: $CURRENT_VERSION -> New version: $VERSION"

# Update the env file
if [ "$(uname)" = "Darwin" ]; then
    sed -i '' "s/^APP_VERSION=.*/APP_VERSION=$NEW_VERSION/" "$ENV_FILE"
else
    sed -i.bak "s/^APP_VERSION=.*/APP_VERSION=$NEW_VERSION/" "$ENV_FILE" && rm -f "${ENV_FILE}.bak"
fi

echo "✅ Updated APP_VERSION in $ENV_FILE to $NEW_VERSION"

# Update docker-compose-remote.yml with the actual image tag
COMPOSE_FILE="docker-compose-remote.yml"
FULL_IMAGE_TAG="$REGISTRY/$ORG/$REPO:$VERSION"
echo "Updating image tag in $COMPOSE_FILE to $FULL_IMAGE_TAG..."

if [ "$(uname)" = "Darwin" ]; then
    sed -i '' "s|image: ghcr.io/build4africa/tosspaper:.*|image: $FULL_IMAGE_TAG|" "$COMPOSE_FILE"
else
    sed -i.bak "s|image: ghcr.io/build4africa/tosspaper:.*|image: $FULL_IMAGE_TAG|" "$COMPOSE_FILE" && rm -f "${COMPOSE_FILE}.bak"
fi

echo "✅ Updated image tag in $COMPOSE_FILE"

IMAGE_TAG="$FULL_IMAGE_TAG"

# === 1️⃣ Build the Docker image ===
echo "Building Docker image $LOCAL_IMAGE:$VERSION ..."
# ./gradlew clean
# make jooq
# ./gradlew openApiGenerate
./gradlew build -x test

docker build --platform linux/arm64 -t "$LOCAL_IMAGE:$VERSION" .

# === 2️⃣ Tag image for GHCR ===
echo "Tagging image as $IMAGE_TAG ..."
docker tag "$LOCAL_IMAGE:$VERSION" "$IMAGE_TAG"

# === 3️⃣ Log in to GHCR ===
if [ -z "$GITHUB_TOKEN" ]; then
  echo "❌ GITHUB_TOKEN environment variable is not set. Export it first."
  exit 1
fi

echo "$GITHUB_TOKEN" | docker login $REGISTRY -u "$GITHUB_USERNAME" --password-stdin

# === 4️⃣ Push the image ===
echo "Pushing image to $IMAGE_TAG ..."
docker push "$IMAGE_TAG"

echo "✅ Image pushed successfully: $IMAGE_TAG"
echo "📝 Version $NEW_VERSION has been saved to $ENV_FILE"
echo "🏷️  Image tagged as: $VERSION (with -snapshot suffix)"
