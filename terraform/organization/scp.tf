# =============================================================================
# Service Control Policies (SCPs)
# =============================================================================
# Security guardrails enforced at organization level:
# - Require encryption for RDS and EBS
# - Deny public S3 buckets
# - Protect CloudTrail and GuardDuty
# - Restrict regions
# - Protect network route tables
#
# ADMIN ACCESS: SCPs use tag-based exceptions (TosspaperAdmin = "true")
# Roles that need this tag:
#   - GitHubActionsRole (tagged in github-oidc.tf)
#   - OrganizationAccountAccessRole (tag manually in each account):
#       aws iam tag-role --role-name OrganizationAccountAccessRole \
#         --tags Key=TosspaperAdmin,Value=true
# =============================================================================

# -----------------------------------------------------------------------------
# SCP: Require Encryption (Backstop)
# -----------------------------------------------------------------------------
# Primary enforcement: aws_ebs_encryption_by_default in database/account-defaults.tf
# This SCP is a defense-in-depth backstop - ec2:Encrypted condition can be unreliable
resource "aws_organizations_policy" "require_encryption" {
  name        = "RequireEncryption"
  description = "Deny creation of unencrypted RDS instances and EBS volumes (backstop)"
  type        = "SERVICE_CONTROL_POLICY"

  content = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "DenyUnencryptedRDS"
        Effect = "Deny"
        Action = [
          "rds:CreateDBInstance",
          "rds:CreateDBCluster",
          "rds:RestoreDBInstanceFromDBSnapshot",
          "rds:RestoreDBClusterFromSnapshot"
        ]
        Resource = "*"
        Condition = {
          Bool = {
            "rds:StorageEncrypted" = "false"
          }
        }
      },
      {
        Sid      = "DenyUnencryptedEBS"
        Effect   = "Deny"
        Action   = ["ec2:CreateVolume"]
        Resource = "*"
        Condition = {
          Bool = {
            "ec2:Encrypted" = "false"
          }
        }
      },
      {
        Sid      = "DenyUnencryptedEC2Launch"
        Effect   = "Deny"
        Action   = ["ec2:RunInstances"]
        Resource = "arn:aws:ec2:*:*:volume/*"
        Condition = {
          Bool = {
            "ec2:Encrypted" = "false"
          }
        }
      }
    ]
  })

  tags = {
    Purpose = "SOC2 encryption compliance"
  }
}

# -----------------------------------------------------------------------------
# SCP: Deny Public S3 Buckets
# -----------------------------------------------------------------------------
resource "aws_organizations_policy" "deny_public_s3" {
  name        = "DenyPublicS3"
  description = "Prevent S3 buckets from being made public"
  type        = "SERVICE_CONTROL_POLICY"

  content = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "DenyRemovingPublicAccessBlock"
        Effect = "Deny"
        Action = [
          "s3:DeletePublicAccessBlock",
          "s3:DeleteAccountPublicAccessBlock"
        ]
        Resource = "*"
      },
      {
        Sid    = "DenyPublicACLs"
        Effect = "Deny"
        Action = [
          "s3:PutBucketAcl",
          "s3:PutObjectAcl"
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "s3:x-amz-acl" = ["public-read", "public-read-write", "authenticated-read"]
          }
        }
      }
    ]
  })

  tags = {
    Purpose = "Prevent data exposure"
  }
}

# -----------------------------------------------------------------------------
# SCP: Protect Security Services
# -----------------------------------------------------------------------------
resource "aws_organizations_policy" "protect_security" {
  name        = "ProtectSecurityServices"
  description = "Prevent disabling CloudTrail, GuardDuty, and Security Hub"
  type        = "SERVICE_CONTROL_POLICY"

  content = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ProtectCloudTrail"
        Effect = "Deny"
        Action = [
          "cloudtrail:DeleteTrail",
          "cloudtrail:StopLogging",
          "cloudtrail:UpdateTrail"
        ]
        Resource = "*"
        Condition = {
          StringNotEquals = {
            "aws:PrincipalTag/TosspaperAdmin" = "true"
          }
          StringNotLike = {
            "aws:PrincipalArn" = "arn:aws:iam::*:role/aws-service-role/*"
          }
        }
      },
      {
        Sid    = "ProtectGuardDuty"
        Effect = "Deny"
        Action = [
          "guardduty:DeleteDetector",
          "guardduty:DisassociateFromMasterAccount",
          "guardduty:StopMonitoringMembers"
        ]
        Resource = "*"
        Condition = {
          StringNotEquals = {
            "aws:PrincipalTag/TosspaperAdmin" = "true"
          }
          StringNotLike = {
            "aws:PrincipalArn" = "arn:aws:iam::*:role/aws-service-role/*"
          }
        }
      },
      {
        Sid    = "ProtectSecurityHub"
        Effect = "Deny"
        Action = [
          "securityhub:DisableSecurityHub",
          "securityhub:DeleteMembers"
        ]
        Resource = "*"
        Condition = {
          StringNotEquals = {
            "aws:PrincipalTag/TosspaperAdmin" = "true"
          }
          StringNotLike = {
            "aws:PrincipalArn" = "arn:aws:iam::*:role/aws-service-role/*"
          }
        }
      }
    ]
  })

  tags = {
    Purpose = "SOC2 audit trail protection"
  }
}

# -----------------------------------------------------------------------------
# SCP: Region Restriction
# -----------------------------------------------------------------------------
resource "aws_organizations_policy" "region_restriction" {
  name        = "RegionRestriction"
  description = "Restrict AWS resource creation to approved regions"
  type        = "SERVICE_CONTROL_POLICY"

  content = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "DenyOtherRegions"
        Effect = "Deny"
        # NotAction allows global services that only work in us-east-1
        NotAction = [
          "iam:*",
          "organizations:*",
          "route53:*",
          "route53domains:*",
          "cloudfront:*",
          "waf:*",
          "wafv2:*",
          "waf-regional:*",
          "globalaccelerator:*",
          "support:*",
          "budgets:*",
          "ce:*",
          "health:*",
          "trustedadvisor:*",
          "sts:*",
          "s3:GetBucketLocation",
          "s3:ListAllMyBuckets",
          # Additional services that may have cross-region operations
          "logs:*",
          "events:*",
          "kms:*",
          "ecr-public:*"
        ]
        Resource = "*"
        Condition = {
          StringNotEquals = {
            "aws:RequestedRegion" = var.allowed_regions
          }
        }
      }
    ]
  })

  tags = {
    Purpose = "Compliance region restriction"
  }
}

# -----------------------------------------------------------------------------
# SCP: Protect Network Route Tables
# -----------------------------------------------------------------------------
resource "aws_organizations_policy" "protect_network" {
  name        = "ProtectNetworkResources"
  description = "Prevent accidental modification of route tables (shared VPC protection)"
  type        = "SERVICE_CONTROL_POLICY"

  content = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "DenyRouteTableModification"
        Effect = "Deny"
        Action = [
          "ec2:DeleteRouteTable",
          "ec2:DeleteRoute",
          "ec2:ReplaceRoute",
          "ec2:DeleteSubnet",
          "ec2:DeleteVpc"
        ]
        Resource = "*"
        Condition = {
          StringNotEquals = {
            "aws:PrincipalTag/TosspaperAdmin" = "true"
          }
        }
      }
    ]
  })

  tags = {
    Purpose = "Shared VPC blast radius protection"
  }
}

# -----------------------------------------------------------------------------
# SCP: Security Hardening (Merged)
# -----------------------------------------------------------------------------
# Combines: IAM user denial, IMDSv2 enforcement, public SSH/RDP denial
# Merged to stay under 5 SCP limit per OU
resource "aws_organizations_policy" "security_hardening" {
  name        = "SecurityHardening"
  description = "IAM users denial + IMDSv2 enforcement + public SSH/RDP denial"
  type        = "SERVICE_CONTROL_POLICY"

  content = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # Deny IAM user creation - enforce role-based access
      {
        Sid    = "DenyIAMUserCreation"
        Effect = "Deny"
        Action = [
          "iam:CreateUser",
          "iam:CreateLoginProfile",
          "iam:UpdateLoginProfile",
          "iam:CreateAccessKey",
          "iam:AttachUserPolicy",
          "iam:PutUserPolicy",
          "iam:AddUserToGroup",
          "iam:CreateGroup",
          "iam:AttachGroupPolicy",
          "iam:PutGroupPolicy"
        ]
        Resource = "*"
        Condition = {
          StringNotLike = {
            "aws:PrincipalArn" = "arn:aws:iam::*:role/aws-service-role/*"
          }
        }
      },
      # Require IMDSv2 on EC2 launch
      {
        Sid      = "RequireIMDSv2"
        Effect   = "Deny"
        Action   = ["ec2:RunInstances"]
        Resource = "arn:aws:ec2:*:*:instance/*"
        Condition = {
          StringNotEquals = {
            "ec2:MetadataHttpTokens" = "required"
          }
        }
      },
      # Deny IMDSv1 modification
      {
        Sid      = "DenyIMDSv1Modification"
        Effect   = "Deny"
        Action   = ["ec2:ModifyInstanceMetadataOptions"]
        Resource = "*"
        Condition = {
          StringNotEquals = {
            "ec2:MetadataHttpTokens" = "required"
          }
        }
      },
      # Deny public SSH (port 22)
      {
        Sid      = "DenyPublicSSH"
        Effect   = "Deny"
        Action   = ["ec2:AuthorizeSecurityGroupIngress"]
        Resource = "*"
        Condition = {
          StringEquals = {
            "ec2:IpProtocol" = "tcp"
          }
          "ForAnyValue:StringEquals" = {
            "ec2:FromPort" = "22"
            "ec2:Cidr"     = ["0.0.0.0/0", "::/0"]
          }
          StringNotEquals = {
            "aws:PrincipalTag/TosspaperAdmin" = "true"
          }
        }
      },
      # Deny public RDP (port 3389)
      {
        Sid      = "DenyPublicRDP"
        Effect   = "Deny"
        Action   = ["ec2:AuthorizeSecurityGroupIngress"]
        Resource = "*"
        Condition = {
          StringEquals = {
            "ec2:IpProtocol" = "tcp"
          }
          "ForAnyValue:StringEquals" = {
            "ec2:FromPort" = "3389"
            "ec2:Cidr"     = ["0.0.0.0/0", "::/0"]
          }
          StringNotEquals = {
            "aws:PrincipalTag/TosspaperAdmin" = "true"
          }
        }
      }
    ]
  })

}


# -----------------------------------------------------------------------------
# SCP: Cloudflare-Fronted ALB Only
# -----------------------------------------------------------------------------
# Internet-facing ALBs are only allowed if tagged as Cloudflare-fronted
# This ensures all public traffic goes through Cloudflare for DDoS protection
# Security groups should further restrict ALB ingress to Cloudflare IP ranges
resource "aws_organizations_policy" "cloudflare_alb_only" {
  name        = "CloudflareFrontedALBOnly"
  description = "Allow internet-facing ALB only if tagged as Cloudflare-fronted"
  type        = "SERVICE_CONTROL_POLICY"

  content = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "DenyPublicALBWithoutCloudflareTag"
        Effect   = "Deny"
        Action   = ["elasticloadbalancing:CreateLoadBalancer"]
        Resource = "*"
        Condition = {
          StringEquals = {
            "elasticloadbalancing:Scheme" = "internet-facing"
          }
          "Null" = {
            "aws:RequestTag/CloudflareFronted" = "true"
          }
          StringNotEquals = {
            "aws:PrincipalTag/TosspaperAdmin" = "true"
          }
        }
      },
      {
        Sid      = "DenyRemovingCloudflareTag"
        Effect   = "Deny"
        Action   = ["elasticloadbalancing:RemoveTags"]
        Resource = "*"
        Condition = {
          "ForAnyValue:StringEquals" = {
            "aws:TagKeys" = "CloudflareFronted"
          }
          StringNotEquals = {
            "aws:PrincipalTag/TosspaperAdmin" = "true"
          }
        }
      }
    ]
  })

  tags = {
    Purpose = "Enforce Cloudflare-only public ALBs"
  }
}

# -----------------------------------------------------------------------------
# Attach SCPs to Tosspaper OU (applies to ALL accounts)
# -----------------------------------------------------------------------------
# These are universal controls that apply to infrastructure AND workload accounts
# All attachments depend on SCP policy type being enabled first
resource "aws_organizations_policy_attachment" "require_encryption" {
  policy_id  = aws_organizations_policy.require_encryption.id
  target_id  = aws_organizations_organizational_unit.tosspaper.id
  }

resource "aws_organizations_policy_attachment" "deny_public_s3" {
  policy_id  = aws_organizations_policy.deny_public_s3.id
  target_id  = aws_organizations_organizational_unit.tosspaper.id
  }

resource "aws_organizations_policy_attachment" "region_restriction" {
  policy_id  = aws_organizations_policy.region_restriction.id
  target_id  = aws_organizations_organizational_unit.tosspaper.id
  }

resource "aws_organizations_policy_attachment" "security_hardening" {
  policy_id = aws_organizations_policy.security_hardening.id
  target_id = aws_organizations_organizational_unit.tosspaper.id
}

# -----------------------------------------------------------------------------
# Attach SCPs to Workloads OU only (stage + prod)
# -----------------------------------------------------------------------------
# These protect infrastructure managed by dedicated accounts:
# - Network account needs to manage routes (exempt from protect_network)
# - Security account needs to manage CloudTrail/GuardDuty (exempt from protect_security)
resource "aws_organizations_policy_attachment" "protect_security" {
  policy_id = aws_organizations_policy.protect_security.id
  target_id = aws_organizations_organizational_unit.workloads.id
}

resource "aws_organizations_policy_attachment" "protect_network" {
  policy_id = aws_organizations_policy.protect_network.id
  target_id = aws_organizations_organizational_unit.workloads.id
}

resource "aws_organizations_policy_attachment" "cloudflare_alb_only" {
  policy_id = aws_organizations_policy.cloudflare_alb_only.id
  target_id = aws_organizations_organizational_unit.workloads.id
}
