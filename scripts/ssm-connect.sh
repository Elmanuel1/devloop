#!/usr/bin/env bash
# Interactive SSM Session Manager connector
# Usage: ./scripts/ssm-connect.sh <environment>
# Example: ./scripts/ssm-connect.sh stage

set -e

ENV="${1:-}"
PROFILE="${AWS_PROFILE:-tosspaper}"
REGION="${AWS_REGION:-us-west-2}"

if [[ -z "$ENV" ]]; then
  echo "Usage: $0 <environment>"
  echo "  environment: stage, prod"
  exit 1
fi

# Get account ID based on environment
get_account_id() {
  case "$1" in
    stage) echo "318724431231" ;;
    prod)  echo "YOUR_PROD_ACCOUNT_ID" ;;
    *)     echo "" ;;
  esac
}

ACCOUNT_ID=$(get_account_id "$ENV")

if [[ -z "$ACCOUNT_ID" ]]; then
  echo "Error: Unknown environment '$ENV'"
  echo "Available: stage, prod"
  exit 1
fi

echo "Connecting to $ENV environment (Account: $ACCOUNT_ID)..."
echo ""

# Assume role and get credentials
CREDS=$(aws sts assume-role \
  --role-arn "arn:aws:iam::${ACCOUNT_ID}:role/OrganizationAccountAccessRole" \
  --role-session-name "ssm-session-$(date +%s)" \
  --profile "$PROFILE" \
  --output json)

export AWS_ACCESS_KEY_ID=$(echo "$CREDS" | jq -r '.Credentials.AccessKeyId')
export AWS_SECRET_ACCESS_KEY=$(echo "$CREDS" | jq -r '.Credentials.SecretAccessKey')
export AWS_SESSION_TOKEN=$(echo "$CREDS" | jq -r '.Credentials.SessionToken')

echo "Fetching instances..."
echo ""

# Fetch all running instances
INSTANCES_JSON=$(aws ec2 describe-instances \
  --region "$REGION" \
  --filters "Name=instance-state-name,Values=running" \
  --query 'Reservations[*].Instances[*].{InstanceId:InstanceId,Name:Tags[?Key==`Name`].Value|[0],Type:InstanceType,PrivateIp:PrivateIpAddress}' \
  --output json)

# Get count of instances
INSTANCE_COUNT=$(echo "$INSTANCES_JSON" | jq '[.[][]] | length')

if [[ "$INSTANCE_COUNT" -eq 0 ]]; then
  echo "No running instances found in $ENV environment."
  exit 1
fi

# Display instances
echo "Available instances:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
printf "%-4s %-22s %-28s %-12s %-15s\n" "#" "Instance ID" "Name" "Type" "Private IP"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

for i in $(seq 0 $((INSTANCE_COUNT - 1))); do
  INSTANCE_ID=$(echo "$INSTANCES_JSON" | jq -r ".[][] | select(. != null)" | jq -s ".[$i].InstanceId")
  INSTANCE_ID=$(echo "$INSTANCES_JSON" | jq -r "[.[][]][$i].InstanceId")
  INSTANCE_NAME=$(echo "$INSTANCES_JSON" | jq -r "[.[][]][$i].Name // \"N/A\"")
  INSTANCE_TYPE=$(echo "$INSTANCES_JSON" | jq -r "[.[][]][$i].Type")
  INSTANCE_IP=$(echo "$INSTANCES_JSON" | jq -r "[.[][]][$i].PrivateIp // \"N/A\"")

  printf "%-4s %-22s %-28s %-12s %-15s\n" \
    "$((i+1))" \
    "$INSTANCE_ID" \
    "${INSTANCE_NAME:0:27}" \
    "$INSTANCE_TYPE" \
    "$INSTANCE_IP"
done

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Prompt for selection
while true; do
  read -p "Select instance (1-${INSTANCE_COUNT}) or 'q' to quit: " selection

  if [[ "$selection" == "q" || "$selection" == "Q" ]]; then
    echo "Cancelled."
    exit 0
  fi

  if [[ "$selection" =~ ^[0-9]+$ ]] && [[ "$selection" -ge 1 ]] && [[ "$selection" -le "$INSTANCE_COUNT" ]]; then
    break
  fi

  echo "Invalid selection. Please enter a number between 1 and ${INSTANCE_COUNT}"
done

# Get selected instance
INDEX=$((selection - 1))
SELECTED_ID=$(echo "$INSTANCES_JSON" | jq -r "[.[][]][$INDEX].InstanceId")
SELECTED_NAME=$(echo "$INSTANCES_JSON" | jq -r "[.[][]][$INDEX].Name // \"N/A\"")

echo ""
echo "Connecting to: $SELECTED_NAME ($SELECTED_ID)..."
echo ""

# Start SSM session
aws ssm start-session --target "$SELECTED_ID" --region "$REGION"
