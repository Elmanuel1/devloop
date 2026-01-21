#!/bin/bash
set -e

USER_NAME=${SUDO_USER:-$USER}

echo "=== Updating system packages ==="
sudo dnf update -y

echo "=== Installing required dependencies ==="
sudo dnf install -y dnf-plugins-core

echo "=== Adding Docker (RHEL9-compatible) repository ==="
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo

echo "=== Enabling RHEL 9 repo stream ==="
sudo sed -i 's/\$releasever/9/g' /etc/yum.repos.d/docker-ce.repo

echo "=== Installing Docker Engine, CLI, and Compose plugin ==="
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin --nobest

echo "=== Enabling and starting Docker service ==="
sudo systemctl enable docker
sudo systemctl start docker

echo "=== Adding $USER_NAME to docker group ==="
sudo usermod -aG docker $USER_NAME

echo "✅ Docker & Docker Compose installed successfully!"
echo "ℹ️  Please log out and log back in (or run 'newgrp docker') for group changes to take effect."
echo "You can test it with: docker compose version"

# Set permissions for the writable directory
mkdir -p /app
sudo chown 1000:1000 /app
sudo chmod 750 /app

sudo usermod -aG docker opc
newgrp docker

sudo mkdir -p /app
sudo chown -R opc:opc /app

sudo mkdir -p /app/files && sudo chown -R 1000:1000 /app/files && sudo chmod -R 755 /app/files && ls -la /app/files