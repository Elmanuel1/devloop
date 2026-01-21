#!/bin/bash

# Script to add redis and postgres hostname mappings to /etc/hosts
# This allows applications running on the host to resolve redis/postgres to localhost

echo "Adding redis and postgres mappings to /etc/hosts..."

# Check if entries already exist
if grep -q "^127.0.0.1.*redis" /etc/hosts; then
    echo "redis entry already exists in /etc/hosts"
else
    echo "127.0.0.1 redis" | sudo tee -a /etc/hosts
    echo "Added redis -> 127.0.0.1"
fi

if grep -q "^127.0.0.1.*postgres" /etc/hosts; then
    echo "postgres entry already exists in /etc/hosts"
else
    echo "127.0.0.1 postgres" | sudo tee -a /etc/hosts
    echo "Added postgres -> 127.0.0.1"
fi

echo "Done! You can now use 'redis' and 'postgres' hostnames from your host machine."
echo ""
echo "Note: Redis is mapped to port 16379 on host (Docker port mapping)"
echo "      Postgres is mapped to port 5432 on host"


