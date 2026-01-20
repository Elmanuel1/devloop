# Terraform Infrastructure Plan for TossPaper (Multi-Account)

## Overview
Add tosspaper accounts to the existing **ConstructDrive** AWS Organization for prod, stage, network, and security accounts. Uses **GitHub Actions + S3** for state management and PR-based workflow (free).

---

## Documented Risk Acceptances

See **[terraform-risk-acceptances.md](./terraform-risk-acceptances.md)** for detailed risk documentation.

**Summary of accepted tradeoffs:**
| Risk | Savings | Upgrade Trigger |
|------|---------|-----------------|
| Shared VPC via RAM | ~$100/mo (no TGW) | Revenue > $10k/mo |
| Single NAT Gateway | ~$32/mo | Availability SLO > 99.9% |
| Single-AZ RDS (Prod) | ~$25/mo | Customer SLA < 1hr RTO |
| Spot Instances (Stage) | ~$15-20/mo | N/A (stage only) |
| GitHub concurrency (no DynamoDB) | ~$1/mo | Multi-repo setup |
| OrganizationAccountAccessRole | Simplicity | Team > 5 or SOC2 audit |

**RDS Recovery Objectives (Prod):** RPO = 1 hour, RTO = 2 hours

---

## CI/CD Platform: GitHub Actions + S3 Backend
- **State storage**: S3 bucket (in management account)
- **Locking**: GitHub Actions `concurrency` (no DynamoDB needed)
- **Workflow**: Plan on PR → Review in PR comments → Apply on merge
- **Auth**: GitHub OIDC (no stored credentials)
- **Cost**: Free

### State Configuration
| State File | Directory | Target Account |
|------------|-----------|----------------|
| `organization.tfstate` | `terraform/organization` | management |
| `network.tfstate` | `terraform/network` | network |
| `security.tfstate` | `terraform/security` | security |
| `database-stage.tfstate` | `terraform/database` | stage |
| `database-prod.tfstate` | `terraform/database` | prod |
| `app-stage.tfstate` | `terraform/app` | stage |
| `app-prod.tfstate` | `terraform/app` | prod |

### GitHub Actions Workflow Files
```
.github/workflows/
├── terraform-organization.yml   # Triggered by terraform/organization/**
├── terraform-network.yml        # Triggered by terraform/network/**
├── terraform-security.yml       # Triggered by terraform/security/**
├── terraform-database.yml       # Triggered by terraform/database/** (manual approve)
└── terraform-app.yml            # Triggered by terraform/app/**
```

**Database isolation benefits:**
- Separate state = separate access control
- DB credentials in AWS Secrets Manager
- Database changes require manual approval in GitHub
- App deployments can auto-apply (stage only)

## AWS Organization Structure
```
Root (r-axqz)
└── ConstructDrive (905418019159) - Management Account
    │
    └── Tosspaper OU
        │
        ├── Tosspaper-Infrastructure OU
        │   ├── tosspaper-network - Shared VPC, NAT Gateway
        │   └── tosspaper-security - CloudTrail, GuardDuty, Security Hub, Logs
        │
        └── Tosspaper-Workloads OU
            ├── tosspaper-stage - Staging workloads
            └── tosspaper-prod - Production workloads
```

## Folder Structure
```
terraform/
├── organization/              # AWS Organizations & Accounts
│   ├── main.tf
│   ├── variables.tf
│   ├── outputs.tf
│   ├── accounts.tf
│   ├── ous.tf
│   ├── scp.tf
│   ├── github-oidc.tf         # GitHub OIDC provider + IAM role
│   └── backend.tf             # S3 backend config
│
├── network/                   # Shared networking
│   ├── main.tf
│   ├── variables.tf
│   ├── outputs.tf
│   ├── vpc.tf                 # VPC, subnets, IGW, single NAT
│   ├── ram.tf                 # Share subnets to workload accounts
│   └── vpc-flow-logs.tf
│
├── security/                  # Security & compliance
│   ├── main.tf
│   ├── variables.tf
│   ├── outputs.tf
│   ├── cloudtrail.tf
│   ├── guardduty.tf
│   ├── security-hub.tf
│   └── log-archive.tf
│
├── database/                  # RDS PostgreSQL (separate state file per env)
│   ├── main.tf
│   ├── variables.tf           # environment var determines stage/prod
│   ├── outputs.tf             # Exports: endpoint, port, db_name
│   ├── locals.tf              # Environment-specific DB sizing
│   ├── data.tf                # Reference shared subnets
│   ├── rds.tf                 # PostgreSQL with pgvector
│   ├── security-groups.tf     # DB security group
│   └── secrets.tf             # Secrets Manager for DB credentials
│
├── app/                       # Application resources (separate state file per env)
│   ├── main.tf
│   ├── variables.tf
│   ├── outputs.tf
│   ├── locals.tf              # Environment-specific sizing
│   ├── data.tf                # Reference subnets + DB endpoint
│   ├── ec2.tf                 # Application server
│   ├── iam.tf                 # Instance profile for S3/SQS
│   ├── security-groups.tf     # EC2 security group
│   ├── s3.tf                  # Email attachments bucket
│   ├── sqs.tf                 # Message queues + DLQs
│   └── cloudwatch.tf          # Logs and alarms
│
└── terraform.tfvars.example
```

## Phase 1: AWS Organization Setup (from management account)

### Accounts to Create (under ConstructDrive org)
| Account | Purpose | Email |
|---------|---------|-------|
| tosspaper-network | Shared VPC, NAT Gateways | tosspaper-network@constructdrive.com |
| tosspaper-security | CloudTrail, GuardDuty, Security Hub, Log Archive | tosspaper-security@constructdrive.com |
| tosspaper-stage | Staging workloads (RDS, S3, SQS) | tosspaper-stage@constructdrive.com |
| tosspaper-prod | Production workloads (RDS, S3, SQS) | tosspaper-prod@constructdrive.com |

### Organization Units (OUs) to Create
```
Root (r-axqz)
└── Tosspaper OU (new)
    ├── Tosspaper-Infrastructure OU (new)
    │   ├── tosspaper-network
    │   └── tosspaper-security
    └── Tosspaper-Workloads OU (new)
        ├── tosspaper-stage
        └── tosspaper-prod
```

## Phase 2: Network Account

### VPC Configuration
| Component | CIDR | Purpose |
|-----------|------|---------|
| VPC | 10.0.0.0/16 | Main VPC |
| Public Subnet AZ-a | 10.0.1.0/24 | NAT Gateway, Load Balancers |
| Public Subnet AZ-b | 10.0.2.0/24 | NAT Gateway, Load Balancers |
| Private Subnet AZ-a | 10.0.10.0/24 | Application workloads |
| Private Subnet AZ-b | 10.0.11.0/24 | Application workloads |
| Database Subnet AZ-a | 10.0.20.0/24 | RDS instances |
| Database Subnet AZ-b | 10.0.21.0/24 | RDS instances |

### Shared via RAM (no Transit Gateway needed)
- Private subnets shared to stage & prod accounts
- Database subnets shared to stage & prod accounts
- All resources deploy into shared subnets (same VPC)
- No cross-VPC traffic = no Transit Gateway costs

## Phase 3: Workload Resources (per account)

### Resources per Account (stage/prod)
1. **EC2 Instance** - Application server with IAM instance profile
2. **IAM Role + Instance Profile** - For S3/SQS access (no credentials on server)
3. **PostgreSQL RDS** - Uses shared database subnets
4. **S3 Bucket** - `tosspaper-email-attachments-{env}`
5. **SQS Queues** - 6 queues + 6 DLQs
6. **Security Groups** - EC2 and RDS

### EC2 Configuration (Cost Optimized)
| Setting | Stage | Prod |
|---------|-------|------|
| Instance Type | t4g.small (ARM) | t4g.medium (ARM) |
| Purchase | **Spot** (~70% savings) | On-Demand |
| AMI | Amazon Linux 2023 ARM | Amazon Linux 2023 ARM |
| EBS Volume | 30 GB gp3 | 50 GB gp3 |
| Subnet | Private (shared via RAM) | Private (shared via RAM) |

### IAM Instance Profile
```
EC2 Instance
    └── Instance Profile
            └── IAM Role (tosspaper-app-role)
                    ├── S3 Policy (GetObject, PutObject, DeleteObject)
                    ├── SQS Policy (SendMessage, ReceiveMessage, DeleteMessage)
                    └── CloudWatch Policy (logs, metrics)
```
**No AWS credentials stored on EC2** - Role is assumed automatically via instance metadata.

### Environment Differences (Cost Optimized)
| Setting | Stage | Prod |
|---------|-------|------|
| EC2 Instance | t4g.small (ARM) | t4g.medium (ARM) |
| EC2 Purchase | Spot (~70% off) | On-Demand |
| RDS Instance | db.t4g.micro (ARM) | db.t4g.medium (ARM) |
| RDS Multi-AZ | No | No (single-AZ saves ~$25/mo) |
| RDS Deletion Protection | No | Yes |
| S3 Force Destroy | Yes | No |
| NAT Gateway | Single (not HA) | Single (saves ~$32/mo) |

### Estimated Monthly Cost
| Resource | Stage | Prod | Shared Infra |
|----------|-------|------|--------------|
| EC2 | ~$5 (Spot) | ~$25 | - |
| RDS | ~$12 | ~$25 | - |
| S3 | ~$2 | ~$5 | - |
| SQS | ~$1 | ~$2 | - |
| NAT Gateway | - | - | ~$32 |
| CloudWatch/Logs | ~$2 | ~$3 | ~$5 |
| CloudTrail/GuardDuty | - | - | ~$10 |
| **Subtotal** | **~$22** | **~$60** | **~$47** |
| **Total** | | | **~$130/month** |

## Deployment Order

### Step 1: Bootstrap (one-time, manual - S3 only)
```bash
# Create S3 bucket for Terraform state (chicken-egg: can't use TF for its own state bucket)
aws s3 mb s3://tosspaper-terraform-state --region us-east-1
aws s3api put-bucket-versioning --bucket tosspaper-terraform-state \
  --versioning-configuration Status=Enabled
aws s3api put-bucket-encryption --bucket tosspaper-terraform-state \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
```

**No DynamoDB needed** - GitHub Actions `concurrency` prevents parallel runs.

### Concurrency Groups (Prevent Races)
```yaml
# Organization, Network, Security - can run independently
concurrency:
  group: terraform-${{ github.workflow }}
  cancel-in-progress: false

# Database workflows - per-environment lock
concurrency:
  group: terraform-database-${{ inputs.environment }}
  cancel-in-progress: false

# App workflows - per-environment lock (prevents race with database)
concurrency:
  group: terraform-workload-${{ inputs.environment }}
  cancel-in-progress: false
```

**Lock groups prevent:**
- Two database deploys racing for same environment
- App deploy racing with database deploy in same environment

### Step 2: First Apply (local, one-time)
Run `terraform apply` locally for organization module to create:
- GitHub OIDC provider
- GitHub Actions IAM role
- OUs and accounts

After this, all future deployments go through GitHub Actions.

### Step 3: Deploy Organization
1. Open PR with `terraform/organization/` changes
2. GitHub Action runs `terraform plan` → posts to PR comments
3. Review plan → Approve PR → Merge
4. GitHub Action runs `terraform apply` on main branch
5. Note account IDs from workflow output

### Step 4: Deploy Network
1. Update `terraform/network/` with account IDs from Step 3
2. Open PR → Review plan in PR → Merge → Apply

### Step 5: Deploy Security
1. Open PR with `terraform/security/` → Review → Merge → Apply

### Step 6: Deploy Stage Database
1. Open PR with `terraform/database/` changes (set `environment = "stage"`)
2. Review plan carefully (database is sensitive)
3. Merge → GitHub Action requires manual approval → Approve → Apply
4. DB credentials automatically stored in AWS Secrets Manager

### Step 7: Deploy Stage App
1. Open PR with `terraform/app/` changes (set `environment = "stage"`)
2. Review plan → Merge → Auto-apply (stage only)

### Step 8: Deploy Prod Database
1. Open PR for prod database (set `environment = "prod"`)
2. Review plan very carefully
3. Merge → Manual approval required → Apply

### Step 9: Deploy Prod App
1. Open PR for prod app (set `environment = "prod"`)
2. Review → Merge → Manual approval required → Apply

## Cross-Account Access

### From Management Account
Use `OrganizationAccountAccessRole` (auto-created) to assume into child accounts:
```bash
aws sts assume-role \
  --role-arn arn:aws:iam::ACCOUNT_ID:role/OrganizationAccountAccessRole \
  --role-session-name terraform
```

### Provider Configuration for Cross-Account
```hcl
provider "aws" {
  alias  = "network"
  region = "us-east-1"
  assume_role {
    role_arn = "arn:aws:iam::${var.network_account_id}:role/OrganizationAccountAccessRole"
  }
}
```

### GitHub OIDC Trust Policy (Secured)
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::905418019159:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": [
            "repo:YOUR_ORG/tosspaper-email-engine:ref:refs/heads/main",
            "repo:YOUR_ORG/tosspaper-email-engine:environment:production",
            "repo:YOUR_ORG/tosspaper-email-engine:environment:staging"
          ]
        }
      }
    }
  ]
}
```

**Security restrictions:**
- `aud`: Must be `sts.amazonaws.com` (prevents token reuse)
- `sub`: Restricts to specific repo + branch/environment
  - `ref:refs/heads/main` - Only main branch can apply
  - `environment:production` - Or GitHub environment protection
  - `environment:staging` - Staging environment
- **No wildcard `*`** on repository - prevents other repos from assuming role

**For even tighter control:**
```json
"StringEquals": {
  "token.actions.githubusercontent.com:sub": "repo:YOUR_ORG/tosspaper-email-engine:ref:refs/heads/main"
}
```
This restricts to ONLY main branch (no PR plans from forks).

## SOC2 Compliance Best Practices

### Security Account Responsibilities

| Control | AWS Service | Implementation |
|---------|-------------|----------------|
| Audit Logging | CloudTrail | Organization trail → central S3 in security account |
| Config Compliance | AWS Config | Aggregator in security account, rules for all accounts |
| Threat Detection | GuardDuty | Delegated admin in security account |
| Security Findings | Security Hub | Aggregate findings across accounts |
| Log Archive | S3 | Central encrypted bucket for all logs |

### Account-Level Controls (enforced via SCPs)

| Control | Implementation |
|---------|----------------|
| **Encryption at Rest** | RDS: `storage_encrypted = true`, S3: SSE-S3, SQS: SSE enabled |
| **Encryption in Transit** | RDS: `require_ssl`, S3: bucket policy requiring HTTPS |
| **Least Privilege IAM** | Specific policies per service, no `*` resources in prod |
| **Network Isolation** | Private subnets for RDS, security groups restricted to app CIDR |
| **Logging** | VPC Flow Logs → security account, CloudTrail → security account |
| **Backup & Recovery** | RDS automated backups (7+ days prod) |
| **MFA** | Require MFA for console access (SCP enforced) |

### Service Control Policies (SCPs)

Add to `organization/scp.tf`:
- Deny disabling CloudTrail
- Deny disabling GuardDuty
- Deny public S3 buckets
- Require encryption for EBS/RDS
- Restrict regions (e.g., us-east-1, us-west-2 only)

### Example SCP - Require Encryption
```hcl
resource "aws_organizations_policy" "require_encryption" {
  name = "RequireEncryption"
  content = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyUnencryptedRDS"
        Effect    = "Deny"
        Action    = ["rds:CreateDBInstance"]
        Resource  = "*"
        Condition = {
          Bool = { "rds:StorageEncrypted" = "false" }
        }
      }
    ]
  })
}
```

### Compliance Monitoring Dashboard

Use AWS Config conformance packs:
- `Operational-Best-Practices-for-SOC2`
- `Operational-Best-Practices-for-CIS-AWS`

## Verification
1. Verify accounts created in AWS Organizations console
2. Verify VPC and subnets in network account
3. Verify RAM shares accepted in stage/prod accounts
4. Verify RDS, S3, SQS resources in each workload account
5. Verify CloudTrail logging to central S3
6. Verify GuardDuty enabled in all accounts
7. Run AWS Config compliance check

---

## SOC2 Audit Evidence Checklist

> **Purpose**: Evidence to collect for SOC2 auditors. Update quarterly.

### Access Control Evidence
- [ ] IAM user list with last login dates (no stale accounts)
- [ ] MFA enforcement proof (SCP + console screenshot)
- [ ] GitHub repo access list with roles
- [ ] Break-glass procedure documented and tested

### Change Management Evidence
- [ ] Sample PR with approval → merge → apply logs
- [ ] GitHub branch protection rules screenshot
- [ ] Manual approval gates for prod (GitHub environment protection)
- [ ] Terraform plan output retained in PR comments

### Backup & Recovery Evidence
- [ ] RDS automated backup configuration screenshot
- [ ] **Quarterly restore test** - restore snapshot to temp instance, verify data
- [ ] Documented RPO/RTO (see Risk Acceptances section)

### Encryption Evidence
- [ ] RDS encryption enabled (console screenshot)
- [ ] S3 default encryption enabled
- [ ] SQS encryption enabled
- [ ] SSL/TLS enforced (RDS parameter group, S3 bucket policy)

### Logging & Monitoring Evidence
- [ ] CloudTrail organization trail enabled
- [ ] GuardDuty findings dashboard (even if empty)
- [ ] VPC Flow Logs enabled
- [ ] CloudWatch alarms configured

### Secrets Management Evidence
- [ ] Secrets Manager automatic rotation enabled for RDS
- [ ] No hardcoded credentials in Terraform (use data sources)
- [ ] IAM instance profiles used (no access keys on EC2)

---

## Technical Implementation Notes

### Spot Instance Handling (Stage Only)
Stage EC2 uses Spot instances (~70% savings). Ensure application handles interruption:
```yaml
# In EC2 user_data or systemd service
# Handle SIGTERM gracefully - AWS gives 2-minute warning before termination
ExecStop=/usr/bin/pkill -TERM -f "java"
TimeoutStopSec=120
```

### VPC Flow Logs Lifecycle
Prevent cost explosion from high-traffic logging:
```hcl
# In security/log-archive.tf
lifecycle_rule {
  id      = "flow-logs-retention"
  enabled = true
  expiration {
    days = 90  # Keep 90 days for compliance, then delete
  }
  transition {
    days          = 30
    storage_class = "STANDARD_IA"  # Move to cheaper storage after 30 days
  }
}
```

### Secrets Rotation (RDS)
```hcl
# In database/secrets.tf
resource "aws_secretsmanager_secret_rotation" "db_password" {
  secret_id           = aws_secretsmanager_secret.db_credentials.id
  rotation_lambda_arn = aws_lambda_function.rotate_secret.arn
  rotation_rules {
    automatically_after_days = 30  # Rotate monthly
  }
}
```
**Alternative**: Use RDS-managed rotation if Lambda complexity is unwanted.

### SCP - Protect Route Tables (Network Account)
```hcl
# In organization/scp.tf - Prevents accidental VPC breakage
resource "aws_organizations_policy" "protect_network" {
  name = "ProtectNetworkResources"
  content = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyRouteTableModification"
        Effect    = "Deny"
        Action    = [
          "ec2:DeleteRouteTable",
          "ec2:DeleteRoute",
          "ec2:ReplaceRoute"
        ]
        Resource  = "*"
        Condition = {
          StringNotLike = {
            "aws:PrincipalArn" = "arn:aws:iam::*:role/OrganizationAccountAccessRole"
          }
        }
      }
    ]
  })
}
```

### Region Restriction (Global Services Exception)
```hcl
# Some AWS services ONLY work in us-east-1 (IAM, Route53, CloudFront, etc.)
# SCP must allow us-east-1 even if you want to restrict to other regions
resource "aws_organizations_policy" "region_restriction" {
  name = "RegionRestriction"
  content = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyOtherRegions"
        Effect    = "Deny"
        NotAction = [
          "iam:*",
          "organizations:*",
          "route53:*",
          "cloudfront:*",
          "waf:*",
          "wafv2:*",
          "support:*",
          "budgets:*"
        ]
        Resource  = "*"
        Condition = {
          StringNotEquals = {
            "aws:RequestedRegion" = ["us-east-1", "us-west-2"]
          }
        }
      }
    ]
  })
}
```

---

## State File Strategy Clarification

> **Important**: This project uses **separate state files**, NOT Terraform workspaces.

| Approach | What We Use | Why |
|----------|-------------|-----|
| Separate state files | ✅ Yes | Clearer blast radius, easier permissions, auditor-friendly |
| Terraform workspaces | ❌ No | Confusing state management, harder to reason about |

**How environments work:**
- `database-stage.tfstate` and `database-prod.tfstate` are separate S3 keys
- Same Terraform code, different `-var-file` or `-var environment=stage`
- GitHub Actions workflow determines which state file to use

```bash
# Stage deployment
terraform init -backend-config="key=database-stage.tfstate"
terraform apply -var="environment=stage"

# Prod deployment
terraform init -backend-config="key=database-prod.tfstate"
terraform apply -var="environment=prod"
```
