#!/bin/bash
# =============================================================================
# EC2 User Data Script - TossPaper Application Server
# =============================================================================
# This script runs on first boot to configure the instance
# Includes: Docker, AWS CLI, CloudWatch Agent (for memory metrics)
# =============================================================================

set -e

# Log all output
exec > >(tee /var/log/user-data.log) 2>&1
echo "Starting user-data script at $(date)"

# -----------------------------------------------------------------------------
# System Updates
# -----------------------------------------------------------------------------
dnf update -y

# -----------------------------------------------------------------------------
# Install Dependencies
# -----------------------------------------------------------------------------
# Note: curl-minimal is pre-installed on AL2023, provides curl command
dnf install -y \
    java-21-amazon-corretto-headless \
    docker \
    jq \
    wget

# -----------------------------------------------------------------------------
# Install Docker Compose v2 (standalone binary)
# -----------------------------------------------------------------------------
COMPOSE_VERSION="v2.24.5"
ARCH=$(uname -m)
case "$ARCH" in
    x86_64)  COMPOSE_ARCH="x86_64" ;;
    aarch64) COMPOSE_ARCH="aarch64" ;;
    *)       echo "ERROR: Unsupported architecture: $ARCH"; exit 1 ;;
esac

mkdir -p /usr/local/lib/docker/cli-plugins
curl -SL "https://github.com/docker/compose/releases/download/$${COMPOSE_VERSION}/docker-compose-linux-$${COMPOSE_ARCH}" \
    -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# -----------------------------------------------------------------------------
# Configure Docker
# -----------------------------------------------------------------------------
systemctl enable docker
systemctl start docker
usermod -aG docker ec2-user

# -----------------------------------------------------------------------------
# Ensure SSM Agent is running (pre-installed on full AL2023 AMI)
# -----------------------------------------------------------------------------
systemctl enable amazon-ssm-agent
systemctl start amazon-ssm-agent

# -----------------------------------------------------------------------------
# Install AWS CLI v2 (if not present)
# -----------------------------------------------------------------------------
if ! command -v aws &> /dev/null; then
    ARCH=$(uname -m)
    case "$ARCH" in
        x86_64)
            PKG_ARCH="x86_64"
            ;;
        aarch64)
            PKG_ARCH="aarch64"
            ;;
        *)
            echo "ERROR: Unsupported architecture: $ARCH" >&2
            exit 1
            ;;
    esac
    curl "https://awscli.amazonaws.com/awscli-exe-linux-$${PKG_ARCH}.zip" -o "awscliv2.zip"
    unzip -q awscliv2.zip
    ./aws/install
    rm -rf aws awscliv2.zip
fi

# -----------------------------------------------------------------------------
# Install CloudWatch Agent (for memory metrics)
# -----------------------------------------------------------------------------
echo "Installing CloudWatch Agent..."
ARCH=$(uname -m)
case "$ARCH" in
    x86_64)
        CW_ARCH="amd64"
        ;;
    aarch64)
        CW_ARCH="arm64"
        ;;
    *)
        echo "ERROR: Unsupported architecture for CloudWatch Agent: $ARCH" >&2
        exit 1
        ;;
esac

dnf install -y "https://amazoncloudwatch-agent.s3.amazonaws.com/amazon_linux/$${CW_ARCH}/latest/amazon-cloudwatch-agent.rpm" || \
dnf install -y amazon-cloudwatch-agent

# -----------------------------------------------------------------------------
# Configure CloudWatch Agent
# -----------------------------------------------------------------------------
# Configuration for memory, disk, and CPU metrics
# Aggregates metrics by AutoScalingGroupName for ASG scaling policies
cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json << 'CWAGENT_EOF'
{
  "agent": {
    "metrics_collection_interval": 60,
    "run_as_user": "root"
  },
  "metrics": {
    "namespace": "CWAgent",
    "append_dimensions": {
      "AutoScalingGroupName": "$${aws:AutoScalingGroupName}",
      "InstanceId": "$${aws:InstanceId}"
    },
    "aggregation_dimensions": [
      ["AutoScalingGroupName"]
    ],
    "metrics_collected": {
      "mem": {
        "measurement": [
          "mem_used_percent"
        ],
        "metrics_collection_interval": 60
      },
      "disk": {
        "measurement": [
          "disk_used_percent"
        ],
        "metrics_collection_interval": 60,
        "resources": [
          "/"
        ]
      }
    }
  }
}
CWAGENT_EOF

# Start CloudWatch Agent
/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
    -a fetch-config \
    -m ec2 \
    -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json \
    -s

# Enable CloudWatch Agent on boot
systemctl enable amazon-cloudwatch-agent

echo "CloudWatch Agent installed and configured"

# -----------------------------------------------------------------------------
# Create Application Directory
# -----------------------------------------------------------------------------
mkdir -p /opt/tosspaper
mkdir -p /opt/tosspaper/logs
mkdir -p /opt/tosspaper/files

# -----------------------------------------------------------------------------
# Create Docker Volume Mount Directory
# -----------------------------------------------------------------------------
# /app is mounted into containers - UID 1000 is typically the container user
mkdir -p /app
chown -R 1000:1000 /app
chmod 750 /app

# -----------------------------------------------------------------------------
# Mount EFS for persistent file storage
# -----------------------------------------------------------------------------
# TODO: Remove after migrating to direct S3 uploads (Issue #49)
dnf install -y amazon-efs-utils

mkdir -p /app/files

# Mount EFS with TLS encryption
echo "${efs_id}:/ /app/files efs _netdev,tls,iam 0 0" >> /etc/fstab

# Retry mount with backoff (EFS mount target may not be immediately available)
MAX_ATTEMPTS=10
ATTEMPT=1
SLEEP_SECONDS=5

while [ $ATTEMPT -le $MAX_ATTEMPTS ]; do
    echo "Attempting EFS mount (attempt $ATTEMPT/$MAX_ATTEMPTS)..."
    if timeout 30 mount -a; then
        echo "EFS mount successful"
        break
    fi
    if [ $ATTEMPT -eq $MAX_ATTEMPTS ]; then
        echo "WARNING: EFS mount failed after $MAX_ATTEMPTS attempts, continuing anyway"
        break
    fi
    echo "Mount failed, retrying in $SLEEP_SECONDS seconds..."
    sleep $SLEEP_SECONDS
    SLEEP_SECONDS=$((SLEEP_SECONDS * 2))
    ATTEMPT=$((ATTEMPT + 1))
done

# Set permissions for container user
chown -R 1000:1000 /app/files
chmod 755 /app/files

# -----------------------------------------------------------------------------
# Configure Environment
# -----------------------------------------------------------------------------
cat > /opt/tosspaper/.env << 'EOF'
# Environment
ENVIRONMENT=${environment}
AWS_REGION=${aws_region}

# Database (IAM authentication)
RDS_IAM_AUTH_ENABLED=true

# S3
AWS_S3_BUCKET_NAME=${s3_bucket_name}

# Messaging
MESSAGING_PROVIDER=sqs
SQS_QUEUE_PREFIX=tosspaper-${environment}
EOF

# -----------------------------------------------------------------------------
# Configure Firewall (iptables)
# -----------------------------------------------------------------------------
# Defense in depth: both security group AND iptables restrict traffic
# ALB health checks come from VPC CIDR (10.0.0.0/8)

# VPC CIDR for ALB health checks
VPC_CIDR="${vpc_cidr}"

# Cloudflare IPv4 ranges (backup - primary traffic comes through ALB now)
CLOUDFLARE_IPS=(
    "173.245.48.0/20"
    "103.21.244.0/22"
    "103.22.200.0/22"
    "103.31.4.0/22"
    "141.101.64.0/18"
    "108.162.192.0/18"
    "190.93.240.0/20"
    "188.114.96.0/20"
    "197.234.240.0/22"
    "198.41.128.0/17"
    "162.158.0.0/15"
    "104.16.0.0/13"
    "104.24.0.0/14"
    "172.64.0.0/13"
    "131.0.72.0/22"
)

# Allow loopback
iptables -A INPUT -i lo -j ACCEPT

# Allow established connections
iptables -A INPUT -m state --state ESTABLISHED,RELATED -j ACCEPT

# Allow SSH from anywhere (security group handles IP restriction)
iptables -A INPUT -p tcp --dport 22 -j ACCEPT

# Allow VPC traffic for ALB health checks (HTTPS on 443)
iptables -A INPUT -p tcp --dport 443 -s "$VPC_CIDR" -j ACCEPT

# Allow HTTP/HTTPS/8080 from Cloudflare IPs (backup if needed)
for ip in "$${CLOUDFLARE_IPS[@]}"; do
    iptables -A INPUT -p tcp --dport 80 -s "$ip" -j ACCEPT
    iptables -A INPUT -p tcp --dport 443 -s "$ip" -j ACCEPT
    iptables -A INPUT -p tcp --dport 8080 -s "$ip" -j ACCEPT
done

# Drop all other inbound traffic
iptables -A INPUT -j DROP

# Save iptables rules
iptables-save > /etc/sysconfig/iptables

# -----------------------------------------------------------------------------
# Set Permissions
# -----------------------------------------------------------------------------
chown -R ec2-user:ec2-user /opt/tosspaper

# -----------------------------------------------------------------------------
# Deploy Application (pull image and start containers)
# -----------------------------------------------------------------------------
echo "Deploying application..."

cd /opt/tosspaper

# Download deployment files from S3 (docker-compose has IMAGE_TAG baked in from deploy workflow)
BUCKET="${s3_bucket_name}"
aws s3 cp "s3://$BUCKET/deploy/docker-compose.yml" docker-compose-remote.yml || true
aws s3 sync "s3://$BUCKET/deploy/nginx/" nginx/ || true
aws s3 sync "s3://$BUCKET/deploy/temporal/" temporal/ || true
mkdir -p certs scripts
aws s3 cp "s3://$BUCKET/deploy/certs/rds-ca-global-bundle.pem" certs/rds-ca-global-bundle.pem || true
aws s3 cp "s3://$BUCKET/deploy/scripts/generate-env-from-ssm.sh" scripts/generate-env-from-ssm.sh || true
chmod +x scripts/generate-env-from-ssm.sh 2>/dev/null || true

# Generate env file from SSM (if script exists)
if [ -f scripts/generate-env-from-ssm.sh ]; then
    ./scripts/generate-env-from-ssm.sh ${environment} ./springboot_dev.env
    set -a
    source ./springboot_dev.env
    set +a
fi

# Login to ECR
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_REGISTRY="$ACCOUNT_ID.dkr.ecr.${aws_region}.amazonaws.com"
aws ecr get-login-password --region ${aws_region} | \
    docker login --username AWS --password-stdin "$ECR_REGISTRY"

# Pull and run (if docker-compose exists - IMAGE_TAG is baked in from deploy workflow)
if [ -f docker-compose-remote.yml ]; then
    # Extract image from docker-compose and pull it
    IMAGE_TAG=$(grep -oP 'image:\s*\K[^\s]+' docker-compose-remote.yml | grep tosspaper | head -1)
    if [ -n "$IMAGE_TAG" ]; then
        docker pull "$IMAGE_TAG"
    fi
    docker compose -f docker-compose-remote.yml up -d
    echo "Application deployed successfully"
else
    echo "WARNING: docker-compose-remote.yml not found in S3, skipping deployment"
    echo "Run GitHub Actions deploy workflow to initialize deployment files"
fi

echo "User-data script completed at $(date)"
