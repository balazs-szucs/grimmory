# Local Release Build Testing

Two options for testing the release build process locally:

## Option 1: Direct Shell Script (Recommended)

**This is the easiest and most reliable way to test locally.**

Run the full build pipeline locally with buildah:

```bash
./scripts/test-release-build.sh v1.2.3
```

This script:
- ✅ Builds the Angular frontend
- ✅ Builds the Spring Boot backend with embedded UI
- ✅ Creates multi-platform container images (amd64 + arm64)  
- ✅ Injects version into application.yaml
- ✅ Sets all OCI labels correctly
- ✅ Inspects and validates the labels
- ✅ Shows you how to run/push the image

### Prerequisites
```bash
# Fedora/RHEL/CentOS
sudo dnf install buildah podman qemu-user-static jq

# Ubuntu/Debian
sudo apt install buildah podman qemu-user-static jq

# Arch/EndeavourOS
sudo pacman -S buildah podman qemu-user-static jq
```

### Usage Examples

```bash
# Test with a specific version
./scripts/test-release-build.sh v1.2.3

# Test with a test version
./scripts/test-release-build.sh test-build-1

# Quick test (will use default: test-1.0.0)
./scripts/test-release-build.sh
```

After running, test the container:
```bash
podman run --rm -p 6060:6060 localhost/booklore:wt-v1.2.3
# Visit http://localhost:6060
```

Check labels:
```bash
buildah inspect localhost/booklore:wt-v1.2.3 | jq '.OCIv1.config.Labels'
```

Cleanup:
```bash
buildah manifest rm booklore:wt-v1.2.3
buildah rmi --all
```

---

## Option 2: Gitea/GitHub Actions Workflow

For testing with CI runners (Gitea Actions or GitHub Actions):

```bash
# Create a test tag
git tag test-v1.2.3
git push origin test-v1.2.3

# Or manually trigger via UI with workflow_dispatch
```

The workflow file is at [.gitea/workflows/test-release.yml](.gitea/workflows/test-release.yml)

**Note**: The workflow uses Docker Buildx (not buildah) for better compatibility with CI environments.

### Using with `act` (GitHub Actions locally)

If you're using [`act`](https://github.com/nektos/act) to run the workflow locally:

```bash
# Install act (if not already installed)
# Arch: sudo pacman -S act
# macOS: brew install act
# Other: see https://github.com/nektos/act#installation

# Run the workflow with act
act workflow_dispatch --input version=v1.2.3

# Or trigger on a test tag
git tag test-v1.2.3
act push --ref refs/tags/test-v1.2.3
```

**Note**: `act` runs workflows in Docker containers, so the workflow uses Docker Buildx instead of buildah for better nested container support. The workflow builds for amd64 only (Docker Buildx can't `--load` multi-platform images). For true multi-platform testing, use the shell script (Option 1) which uses buildah on bare metal.

---

## What Gets Tested

Both options verify:

1. **Version Injection**: `app.version` in application.yaml
2. **OCI Labels**:
   - `org.opencontainers.image.version` = `wt-v1.2.3`
   - `org.opencontainers.image.revision` = `<git SHA>`
   - `org.opencontainers.image.ref.name` = should be `wt-v1.2.3` (check GitLab CI config for label override)
3. **Multi-platform support**: Both linux/amd64 and linux/arm64
4. **JAR embedding**: Frontend is built and embedded in the JAR
5. **Container startup**: Basic validation that the container can start

---

## Differences from GitLab CI

The local test **emulates** the GitLab CI release flow but with some simplifications:

| Feature | GitLab CI | Local Script | GitHub Actions Workflow |
|---------|-----------|--------------|-------------------------|
| Multi-platform build | ✅ via docker component | ✅ via buildah | ⚠️ amd64 only (Buildx limitation) |
| Version ARG | ✅ `wt-$CI_COMMIT_TAG` | ✅ Same | ✅ Same |
| Revision ARG | ✅ `$CI_COMMIT_SHA` | ✅ `git rev-parse HEAD` | ✅ `$GITHUB_SHA` |
| Registry push | ✅ Automatic | ❌ Manual | ❌ Manual |
| Tag aliases | ✅ latest, major.minor | ❌ Manual | ❌ Manual |
| Compression | ✅ zstd level 20 | ⚠️ Default | ⚠️ Default |
| CI Environment | ✅ Native | ✅ Direct machine | ⚠️ Docker-in-Docker |

The ref.name label override in GitLab CI is handled by the docker component's `release-labels` input.

---

## Troubleshooting

### "buildah not found"
```bash
sudo apt install buildah  # Ubuntu/Debian
sudo dnf install buildah  # Fedora
sudo pacman -S buildah    # Arch
```

### "Architecture emulation not available"
```bash
sudo apt install qemu-user-static
# or
sudo systemctl restart systemd-binfmt.service
```

### "Permission denied"
Make sure you're in the `docker` or `podman` group, or run with `sudo`.

### "Version label is empty"
The `APP_VERSION` build arg must be passed to Docker build. Check the buildah build command includes:
```
--build-arg APP_VERSION=wt-v1.2.3
```
