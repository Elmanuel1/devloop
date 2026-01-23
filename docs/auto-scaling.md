# Auto Scaling Configuration

This document describes the Auto Scaling Group (ASG) setup for the TossPaper application infrastructure.

## Architecture

```text
                                     ┌─────────────────┐
                                     │   Cloudflare    │
                                     │   (CDN + WAF)   │
                                     └────────┬────────┘
                                              │ HTTPS
                                              ▼
                                     ┌─────────────────┐
                                     │  Application    │
                                     │  Load Balancer  │
                                     │    (ALB:443)    │
                                     └────────┬────────┘
                                              │
                              ┌───────────────┼───────────────┐
                              │               │               │
                              ▼               ▼               ▼
                        ┌──────────┐   ┌──────────┐   ┌──────────┐
                        │   EC2    │   │   EC2    │   │  (spare) │
                        │ Instance │   │ Instance │   │          │
                        │  nginx   │   │  nginx   │   │          │
                        │  :443    │   │  :443    │   │          │
                        └────┬─────┘   └────┬─────┘   └──────────┘
                             │               │
                             ▼               ▼
                        ┌──────────┐   ┌──────────┐
                        │   App    │   │   App    │
                        │  :8080   │   │  :8080   │
                        └─────┬────┘   └────┬─────┘
                              │               │
                              └───────┬───────┘
                                      │
                              ┌───────▼───────┐
                              │     EFS       │
                              │  /app/files   │
                              │   (shared)    │
                              └───────────────┘

Traffic Flow:
1. Cloudflare terminates public TLS, proxies to ALB
2. ALB terminates Cloudflare origin TLS, distributes to healthy instances
3. Each EC2 runs nginx (TLS termination) → Spring Boot app
4. All instances share EFS for persistent file storage
```

## Scaling Configuration

### Instance Limits

| Setting | Value | Description |
|---------|-------|-------------|
| Min instances | 1 | Minimum capacity - always running |
| Max instances | 2 | Maximum capacity - cost limit |
| Desired | 1 | Starting capacity |

### Scaling Policies

The ASG uses multiple scaling policies that work together:

#### 1. CPU Target Tracking (70%)

- **Type**: Target Tracking
- **Target**: 70% average CPU utilization
- **Behavior**: Automatically adjusts capacity to maintain target

#### 2. SQS Queue Depth (Step Scaling)

- **Scale-out trigger**: Combined queue depth > 100 messages for 2 minutes
- **Scale-in trigger**: Combined queue depth < 10 messages for 5 minutes
- **Queues monitored**:
  - `ai-process` (CPU intensive)
  - `email-local-uploads`
  - `vector-store-ingestion`
  - `document-approved-events`
  - `quickbooks-events`
  - `integration-push-events`

## Persistent Storage (EFS)

EFS provides shared file storage across all ASG instances.

| Setting | Value |
|---------|-------|
| Mount point | `/app/files` |
| Encryption | Enabled (TLS + at-rest) |
| Performance mode | General Purpose |
| Throughput mode | Bursting |
| Lifecycle | Move to Infrequent Access after 30 days |

**Note**: EFS is a temporary solution. See [Issue #49](https://github.com/Build4Africa/tosspaper/issues/49) for migration to direct S3 uploads.

### EFS Operations

```bash
# Check EFS mount
df -h /app/files

# Check EFS usage
aws efs describe-file-systems --file-system-id <efs-id>

# List files
ls -la /app/files
```

## Health Checks

### ALB Target Group Health Check

| Setting | Value |
|---------|-------|
| Protocol | HTTPS |
| Port | 443 |
| Path | `/actuator/health` |
| Healthy threshold | 2 consecutive checks |
| Unhealthy threshold | 3 consecutive checks |
| Timeout | 10 seconds |
| Interval | 30 seconds |

### ASG Health Check

- **Type**: ELB (uses ALB's health determination)
- **Grace period**: 300 seconds (allows instance to initialize)

### Docker Container Health Check

The container uses Spring Boot Actuator:
```bash
curl http://localhost:8080/actuator/health
```

## CloudWatch Metrics

### Custom Metrics (from CloudWatch Agent)

| Metric | Namespace | Description | Cost |
|--------|-----------|-------------|------|
| `mem_used_percent` | CWAgent | Memory utilization percentage | $0.30/month |
| `disk_used_percent` | CWAgent | Disk utilization for root volume | $0.30/month |

### ASG Metrics (free)

| Metric | Description |
|--------|-------------|
| `GroupInServiceInstances` | Number of running, healthy instances |
| `GroupDesiredCapacity` | Target number of instances |
| `GroupMinSize` | Minimum allowed instances |
| `GroupMaxSize` | Maximum allowed instances |

### ALB Metrics (free)

| Metric | Description |
|--------|-------------|
| `RequestCount` | Total requests processed |
| `TargetResponseTime` | Average/p99 response time |
| `HealthyHostCount` | Healthy targets |
| `UnHealthyHostCount` | Unhealthy targets |
| `HTTPCode_Target_2XX_Count` | Successful responses |
| `HTTPCode_Target_5XX_Count` | Server errors |

## CloudWatch Alarms

All alarms send notifications to the SNS topic (`tosspaper-{env}-alerts`).

| Alarm | Threshold | Action |
|-------|-----------|--------|
| CPU High | > 80% for 15 min | SNS alert |
| Disk High | > 80% | SNS alert |
| ALB 5xx Errors | > 10 in 5 min | SNS alert |
| Target 5xx Errors | > 10 in 5 min | SNS alert |
| Unhealthy Hosts | > 0 for 2 min | SNS alert |
| DLQ Not Empty (x6) | > 0 messages | SNS alert |
| SQS Backlog High | > 100 messages | Scale out |
| SQS Backlog Low | < 10 messages for 5 min | Scale in |

### SNS Alerts

Email notifications are sent to the configured `alert_email` variable.

```bash
# Check SNS subscription status
aws sns list-subscriptions-by-topic --topic-arn <topic-arn>
```

**Note**: You must confirm the email subscription after first deployment.

## Manual Operations

### View Current State

```bash
# Check ASG status
aws autoscaling describe-auto-scaling-groups \
  --auto-scaling-group-names tosspaper-prod-asg \
  --query 'AutoScalingGroups[0].{Desired:DesiredCapacity,Min:MinSize,Max:MaxSize,InService:Instances[?LifecycleState==`InService`]|length(@)}'

# Check instance health
aws elbv2 describe-target-health \
  --target-group-arn <target-group-arn>
```

### Scale Manually

```bash
# Scale to 2 instances
aws autoscaling set-desired-capacity \
  --auto-scaling-group-name tosspaper-prod-asg \
  --desired-capacity 2

# Scale back to 1 instance
aws autoscaling set-desired-capacity \
  --auto-scaling-group-name tosspaper-prod-asg \
  --desired-capacity 1
```

### Force Instance Refresh

Useful after AMI updates or user-data changes:

```bash
aws autoscaling start-instance-refresh \
  --auto-scaling-group-name tosspaper-prod-asg \
  --preferences '{"MinHealthyPercentage": 50, "InstanceWarmup": 300}'
```

### Update CloudWatch Agent Config

To modify metrics collection on running instances:

```bash
# Via SSM (no SSH needed)
aws ssm send-command \
  --targets "Key=tag:aws:autoscaling:groupName,Values=tosspaper-prod-asg" \
  --document-name "AWS-RunShellScript" \
  --parameters 'commands=[
    "cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json << EOF
    <new config>
    EOF",
    "/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json -s"
  ]'
```

## Troubleshooting

### Instance Not Launching

1. **Check ASG activity history**:
   ```bash
   aws autoscaling describe-scaling-activities \
     --auto-scaling-group-name tosspaper-prod-asg \
     --max-items 10
   ```

2. **Common causes**:
   - Insufficient capacity in AZ
   - Launch template misconfiguration
   - IAM role issues
   - Security group blocking health checks
   - EFS mount failure

### Health Check Failures

1. **Check target group health**:
   ```bash
   aws elbv2 describe-target-health \
     --target-group-arn <arn>
   ```

2. **SSH to instance and check**:
   ```bash
   # Check nginx
   systemctl status nginx

   # Check application
   curl -k https://localhost/actuator/health

   # Check user-data log
   cat /var/log/user-data.log

   # Check CloudWatch agent
   systemctl status amazon-cloudwatch-agent

   # Check EFS mount
   df -h /app/files
   ```

3. **Common causes**:
   - Application not started
   - nginx configuration error
   - Security group blocking ALB
   - iptables blocking VPC traffic
   - EFS mount not ready

### EFS Issues

1. **Mount failure**:
   ```bash
   # Check mount status
   mount | grep efs

   # Check EFS security group allows NFS (port 2049)
   # Check EFS mount target exists in the instance's subnet
   ```

2. **Permission denied**:
   ```bash
   # Files should be owned by UID 1000 (container user)
   ls -la /app/files
   sudo chown -R 1000:1000 /app/files
   ```

### Scaling Not Working

1. **Check scaling policies**:
   ```bash
   aws autoscaling describe-policies \
     --auto-scaling-group-name tosspaper-prod-asg
   ```

2. **Check CloudWatch alarms**:
   ```bash
   aws cloudwatch describe-alarms \
     --alarm-name-prefix tosspaper-prod
   ```

3. **Check cooldown periods** - scaling may be blocked if recent activity occurred

## Cost Breakdown

### Monthly Costs (per environment)

| Component | Monthly Cost |
|-----------|-------------|
| ALB (base) | ~$16 |
| ALB (LCU charges) | ~$0.50/LCU |
| EC2 (1 t4g.medium On-Demand) | ~$24 |
| EC2 (2 t4g.medium On-Demand) | ~$48 |
| EFS (per GB stored) | ~$0.30/GB |
| EFS Infrequent Access | ~$0.016/GB |
| CloudWatch alarms (12) | ~$1.20 |
| CloudWatch custom metrics (2) | ~$0.60 |
| SNS email notifications | Free (first 1,000/month) |
| **Baseline (1 instance, 1GB EFS)** | **~$42/month** |
| **Peak (2 instances)** | **~$66/month** |

### Stage Environment (Spot Instances)

Stage uses Spot instances with ~70% savings:
- EC2 (1 t4g.medium Spot): ~$7/month
- EC2 (2 t4g.medium Spot): ~$14/month
- **Stage baseline**: ~$25/month

## Deployment

### Prerequisites

1. **ACM Certificate**: Import Cloudflare origin certificate to ACM
   ```bash
   aws acm import-certificate \
     --certificate fileb://certs/cloudflare-origin.crt \
     --private-key fileb://certs/cloudflare-origin.key \
     --region us-west-2
   ```

2. **Variables**: Set in tfvars
   ```hcl
   acm_certificate_arn = "arn:aws:acm:..."
   alert_email         = "alerts@example.com"
   ```

### DNS Cutover

After applying Terraform:

1. Get ALB DNS name:
   ```bash
   terraform output alb_dns_name
   ```

2. Update Cloudflare DNS:
   - Change A record (old EIP) → CNAME (ALB DNS name)
   - Or use Cloudflare Load Balancing for gradual migration

3. Confirm SNS email subscription (check inbox)

### Rollback

If issues occur after deployment:

1. Revert Cloudflare DNS to old Elastic IP (if preserved)
2. Or scale ASG to 0 and re-deploy previous EC2 configuration
