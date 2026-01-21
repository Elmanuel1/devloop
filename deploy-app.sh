#!/bin/bash
set -e

# === Configuration ===
SERVER_USER="opc"
SERVER_HOST="204.216.108.85"
CONTEXT_NAME="tosspaper-dev"
scp -i ~/Documents/oracle-server.key ./docker-compose-remote.yml $SERVER_USER@$SERVER_HOST:/app/docker-compose-remote.yml
scp -i ~/Documents/oracle-server.key ./springboot_dev.env $SERVER_USER@$SERVER_HOST:/app/springboot_dev.env
ssh -i ~/Documents/oracle-server.key $SERVER_USER@$SERVER_HOST "rm -rf /app/flyway && mkdir -p /app/flyway && mkdir -p /app/nginx && mkdir -p /app/certs" 
scp -i ~/Documents/oracle-server.key -r ./flyway/* $SERVER_USER@$SERVER_HOST:/app/flyway
scp -i ~/Documents/oracle-server.key ./init-pgvector.sql $SERVER_USER@$SERVER_HOST:/app/init-pgvector.sql
scp -i ~/Documents/oracle-server.key -r ./nginx/* $SERVER_USER@$SERVER_HOST:/app/nginx
scp -i ~/Documents/oracle-server.key -r ./certs/* $SERVER_USER@$SERVER_HOST:/app/certs

# # === 1️⃣ Create Docker context (idempotent) ===
# if ! docker context ls | grep -q "$CONTEXT_NAME"; then
#     echo "Creating Docker context $CONTEXT_NAME..."
#     docker context create $CONTEXT_NAME --docker "host=ssh://$SERVER_USER@$SERVER_HOST"
# else
#     echo "✅ Docker context $CONTEXT_NAME already exists."
# fi


# docker context use $CONTEXT_NAME

# # === 3️⃣ Deploy stack ===
# echo "Deploying Spring Boot app..."
# sudo docker --context $CONTEXT_NAME compose -f docker-compose.yml -f docker-compose-remote.yml  up -d

# echo "✅ Deployment complete!"
