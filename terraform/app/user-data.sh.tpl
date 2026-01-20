#!/bin/bash
# =============================================================================
# EC2 User Data Script - TossPaper Application Server
# =============================================================================
# This script runs on first boot to configure the instance
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
dnf install -y \
    java-21-amazon-corretto-headless \
    docker \
    docker-compose-plugin \
    jq \
    curl \
    wget

# -----------------------------------------------------------------------------
# Configure Docker
# -----------------------------------------------------------------------------
systemctl enable docker
systemctl start docker
usermod -aG docker ec2-user

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
mkdir -p /app/files
chown -R 1000:1000 /app
chmod 750 /app
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
# Configure Firewall (iptables) - Cloudflare IPs only
# -----------------------------------------------------------------------------
# Defense in depth: both security group AND iptables restrict to Cloudflare

# Cloudflare IPv4 ranges (from https://www.cloudflare.com/ips-v4/)
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

# Allow HTTP/HTTPS/8080 from Cloudflare IPs only
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

echo "User-data script completed at $(date)"
