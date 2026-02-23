#!/bin/bash
set -e

# Local test script for release build process
# Usage: ./scripts/test-release-build.sh [version]
# Example: ./scripts/test-release-build.sh v1.2.3

VERSION="${1:-test-1.0.0}"
REVISION="$(git rev-parse HEAD)"

# Add wt- prefix if not present
if [[ ! "$VERSION" =~ ^wt- ]]; then
    VERSION="wt-${VERSION}"
fi

echo "========================================="
echo "BookLore Release Build Test"
echo "========================================="
echo "Version: $VERSION"
echo "Revision: $REVISION"
echo "========================================="
echo ""

# Check prerequisites
command -v npm >/dev/null 2>&1 || { echo "ERROR: npm is required but not installed."; exit 1; }
command -v java >/dev/null 2>&1 || { echo "ERROR: java is required but not installed."; exit 1; }
command -v buildah >/dev/null 2>&1 || { echo "ERROR: buildah is required but not installed."; exit 1; }
command -v yq >/dev/null 2>&1 || { echo "WARNING: yq not found, installing..."; sudo wget -qO /usr/local/bin/yq https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 && sudo chmod +x /usr/local/bin/yq; }

# Step 1: Build Angular frontend
echo "[1/6] Building Angular frontend..."
cd booklore-ui
npm install --force --legacy-peer-deps
npm run build --configuration=production
cd ..
echo "✓ Frontend build complete"
echo ""

# Step 2: Inject version into application.yaml
echo "[2/6] Injecting version into application.yaml..."
cd booklore-api
cp src/main/resources/application.yaml src/main/resources/application.yaml.bak
yq eval ".app.version = \"$VERSION\"" -i src/main/resources/application.yaml
cd ..
echo "✓ Version injected: $VERSION"
echo ""

# Step 3: Copy Angular dist to Spring static resources
echo "[3/6] Embedding Angular in Spring Boot..."
mkdir -p booklore-api/src/main/resources/static
cp -r booklore-ui/dist/booklore/browser/* booklore-api/src/main/resources/static/
echo "✓ Angular embedded"
echo ""

# Step 4: Build Spring Boot JAR
echo "[4/6] Building Spring Boot JAR..."
cd booklore-api
./gradlew clean build -x test --no-daemon --parallel
cd ..
echo "✓ JAR build complete"
echo ""

# Step 5: Restore application.yaml
mv booklore-api/src/main/resources/application.yaml.bak booklore-api/src/main/resources/application.yaml
echo "✓ Application.yaml restored"
echo ""

# Step 6: Build container images with buildah
echo "[5/6] Building container images..."
echo "Creating multi-arch manifest..."
buildah manifest create booklore:${VERSION}

echo ""
echo "Building for linux/amd64..."
buildah build \
    --platform linux/amd64 \
    --manifest booklore:${VERSION} \
    --build-arg APP_VERSION=${VERSION} \
    --build-arg APP_REVISION=${REVISION} \
    --file Dockerfile.ci \
    --format docker \
    --layers \
    .

echo ""
echo "Building for linux/arm64..."
buildah build \
    --platform linux/arm64 \
    --manifest booklore:${VERSION} \
    --build-arg APP_VERSION=${VERSION} \
    --build-arg APP_REVISION=${REVISION} \
    --file Dockerfile.ci \
    --format docker \
    --layers \
    .

echo "✓ Container images built"
echo ""

# Step 7: Inspect and verify
echo "[6/6] Inspecting image labels..."
echo ""
echo "=== Manifest Inspection ==="
buildah manifest inspect booklore:${VERSION} | jq -r '.manifests[] | "Platform: \(.platform.os)/\(.platform.architecture)"'

echo ""
echo "=== AMD64 Image Labels ==="
IMAGE_ID=$(buildah images --format '{{.ID}}' --noheading | head -1)

if [ -n "$IMAGE_ID" ]; then
    buildah inspect $IMAGE_ID > /tmp/booklore-inspect.json
    
    echo "org.opencontainers.image.version: $(jq -r '.OCIv1.config.Labels."org.opencontainers.image.version" // "NOT SET"' /tmp/booklore-inspect.json)"
    echo "org.opencontainers.image.revision: $(jq -r '.OCIv1.config.Labels."org.opencontainers.image.revision" // "NOT SET"' /tmp/booklore-inspect.json)"
    echo "org.opencontainers.image.ref.name: $(jq -r '.OCIv1.config.Labels."org.opencontainers.image.ref.name" // "NOT SET"' /tmp/booklore-inspect.json)"
    
    echo ""
    echo "=== All Labels ==="
    jq -r '.OCIv1.config.Labels' /tmp/booklore-inspect.json
    
    rm /tmp/booklore-inspect.json
fi

echo ""
echo "========================================="
echo "Build Complete!"
echo "========================================="
echo "Image: localhost/booklore:${VERSION}"
echo ""
echo "To test run the container:"
echo "  podman run --rm -p 6060:6060 localhost/booklore:${VERSION}"
echo ""
echo "To push to registry:"
echo "  buildah manifest push --all booklore:${VERSION} docker://registry.example.com/booklore:${VERSION}"
echo ""
echo "To cleanup:"
echo "  buildah manifest rm booklore:${VERSION}"
echo "  buildah rmi --all"
echo "========================================="
