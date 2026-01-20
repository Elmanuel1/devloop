# Terraform Infrastructure Risk Acceptances

> **Purpose**: Explicit tradeoffs for cost optimization. Review before scaling.
> **Last Reviewed**: 2026-01-18
> **Next Review**: Quarterly or when revenue exceeds $10k/mo

---

## 1. Shared VPC via RAM

### What We're Doing
All workload accounts (stage, prod) deploy resources into a single VPC owned by the network account, shared via AWS Resource Access Manager (RAM).

### Risks

| Risk | Impact | Likelihood |
|------|--------|------------|
| Bad route table/NACL change breaks all accounts | Total outage | Low |
| Operational coupling between accounts | Requires discipline | Medium |
| Future scale pain when adding workload accounts | Increased complexity | Low (for now) |

### Mitigations
- SCP locks route tables (only `OrganizationAccountAccessRole` can modify)
- Network account = infra-only, no human interactive access
- All network changes require PR approval
- VPC Flow Logs enabled for forensics

### Upgrade Trigger
Migrate to per-account VPC + Transit Gateway when:
- Revenue > $10k/mo (can justify ~$100/mo TGW cost)
- Adding 3+ workload accounts
- Compliance requires network isolation between environments

### Decision
**Accepted**. Single shared VPC is appropriate for current scale and cost constraints.

---

## 2. Single NAT Gateway

### What We're Doing
Using one NAT Gateway in a single AZ instead of one per AZ (HA configuration).

### Risks

| Risk | Impact | Likelihood |
|------|--------|------------|
| NAT Gateway failure | Total outbound internet outage | Very Low |
| AZ-specific AWS issues | Outbound traffic blocked | Very Low |

### Mitigations
- AWS NAT Gateway has 99.9% SLA
- CloudWatch alarm on NAT Gateway errors
- Application can tolerate brief outbound failures (retry logic)

### Cost Savings
~$32/month saved (NAT Gateway hourly + data processing)

### Upgrade Trigger
Add second NAT Gateway when:
- Availability SLO requirement exceeds 99.9%
- Customer contracts require HA networking
- Revenue justifies additional $32/mo

### Decision
**Accepted**. Single NAT is SOC2-compliant with documented risk acceptance.

---

## 3. Single-AZ RDS (Production)

### What We're Doing
Running production PostgreSQL RDS in a single Availability Zone instead of Multi-AZ.

### Risks

| Risk | Impact | Likelihood |
|------|--------|------------|
| AZ failure | Database unavailable until restore | Very Low |
| Maintenance window | Brief downtime during patching | Medium |
| Hardware failure | Extended downtime | Very Low |

### Mitigations
- Automated daily snapshots (14-day retention for prod)
- Point-in-time recovery enabled (1-hour RPO)
- Tested restore procedure documented
- Maintenance window scheduled for low-traffic period

### Recovery Objectives

| Metric | Stage | Prod |
|--------|-------|------|
| **RPO** (Recovery Point Objective) | 24 hours | 1 hour |
| **RTO** (Recovery Time Objective) | 4 hours | 2 hours |
| Backup Retention | 7 days | 14 days |
| Point-in-Time Recovery | Disabled | Enabled |

### Cost Savings
~$25/month saved (Multi-AZ doubles RDS cost)

### Upgrade Trigger
Enable Multi-AZ when:
- Customer SLA requires < 1 hour RTO
- Revenue justifies additional ~$25/mo
- Compliance requires zero-downtime maintenance

### Decision
**Accepted**. Single-AZ with automated backups meets current requirements.

---

## 4. Spot Instances (Stage Only)

### What We're Doing
Using EC2 Spot instances for staging environment (~70% cost savings).

### Risks

| Risk | Impact | Likelihood |
|------|--------|------------|
| Spot interruption (2-min warning) | Stage environment briefly unavailable | Medium |
| Capacity unavailable | Instance may not launch | Low |

### Mitigations
- Application handles SIGTERM gracefully (2-minute shutdown)
- Systemd service configured with `TimeoutStopSec=120`
- On-Demand fallback configured in launch template
- Stage is non-production, brief outages acceptable

### Cost Savings
~$15-20/month saved (70% off t4g.small)

### Decision
**Accepted**. Spot is appropriate for non-production workloads.

---

## 5. GitHub Actions Concurrency Locking (No DynamoDB)

### What We're Doing
Using GitHub Actions `concurrency` groups instead of DynamoDB for Terraform state locking.

### Risks

| Risk | Impact | Likelihood |
|------|--------|------------|
| Two workflows modify same state | State corruption | Very Low |
| Concurrency group misconfiguration | Race condition | Low |

### Mitigations
- Per-environment concurrency groups (`terraform-workload-stage`, `terraform-workload-prod`)
- `cancel-in-progress: false` ensures jobs complete
- S3 state versioning allows rollback if corruption occurs
- Single repo = single source of Terraform runs

### Cost Savings
~$1/month saved (DynamoDB on-demand pricing)

### Upgrade Trigger
Add DynamoDB locking when:
- Multiple repos manage same infrastructure
- Team size exceeds 5 concurrent Terraform operators
- Compliance requires formal state locking audit trail

### Decision
**Accepted**. GitHub concurrency is sufficient for single-repo, small-team operations.

---

## 6. OrganizationAccountAccessRole Usage

### What We're Doing
Using the AWS-managed `OrganizationAccountAccessRole` for all cross-account access (Terraform deployments via GitHub Actions).

### Current Usage

| Actor | Uses This Role? | Purpose |
|-------|-----------------|---------|
| GitHub Actions (Terraform) | Yes | Deploy infrastructure to child accounts |
| Humans (console access) | **No** | Humans should use SSO/IAM Identity Center |
| Break-glass emergency | Yes (documented) | Emergency access only, requires justification |

### Risks

| Risk | Impact | Likelihood |
|------|--------|------------|
| Role has full admin access | Over-privileged for Terraform | Medium |
| Shared between automation and humans | Audit trail confusion | Low (if humans don't use it) |
| No fine-grained permissions | Blast radius on misconfiguration | Medium |

### Mitigations (Current)
- Humans do NOT use this role for routine access
- GitHub OIDC restricts which repos/branches can assume the role
- CloudTrail logs all role assumptions
- Break-glass usage requires documented justification

### Upgrade Path (Long-term)
When team/compliance requires stricter controls:

```
Current (acceptable for small team):
  GitHub Actions → OrganizationAccountAccessRole → Full admin

Future (recommended at scale):
  GitHub Actions → TerraformExecutionRole (per-account, scoped permissions)
  Humans → IAM Identity Center (SSO) with permission sets
  Break-glass → Separate emergency role with MFA + approval workflow
```

### Per-Account Terraform Role (Future)
```hcl
# Example: Scoped role for Terraform in prod account
resource "aws_iam_role" "terraform_execution" {
  name = "TerraformExecutionRole"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = "arn:aws:iam::${var.management_account_id}:oidc-provider/token.actions.githubusercontent.com"
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "repo:YOUR_ORG/tosspaper-email-engine:ref:refs/heads/main"
        }
      }
    }]
  })
}

# Attach only required policies (not full admin)
resource "aws_iam_role_policy_attachment" "terraform_permissions" {
  role       = aws_iam_role.terraform_execution.name
  policy_arn = aws_iam_policy.terraform_scoped.arn  # Custom policy with least privilege
}
```

### Upgrade Trigger
Create per-account Terraform roles when:
- SOC2 auditor flags over-privileged access
- Team size > 5 engineers
- Compliance requires least-privilege automation
- Adding non-infrastructure repos that need AWS access

### Decision
**Accepted**. `OrganizationAccountAccessRole` is appropriate for initial setup. Document that humans should NOT use this role for routine access.

---

## Review History

| Date | Reviewer | Changes |
|------|----------|---------|
| 2026-01-18 | Initial | Document created with initial risk acceptances |

---

## Approval

These risk acceptances have been reviewed and approved:

- [ ] Engineering Lead
- [ ] Security/Compliance (if applicable)

**Next Review Date**: _________________
