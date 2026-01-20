# RBAC Migration Guide

## Overview

This guide explains how the RBAC (Role-Based Access Control) system works in Tosspaper Email Engine and how to migrate existing users.

---

## System Architecture

### No Local Users Table

The RBAC system **does not create a separate users table**. Instead, it:
- Uses Supabase Auth as the single source of truth for users
- References Supabase `auth.users.id` directly in `company_members.user_id`
- Denormalizes user email in `company_members.email` for query performance

### Database Tables

1. **company_roles** - Stores 4 predefined roles (owner, admin, operations, viewer)
2. **permissions** - Stores all available permissions (60+ across 13 resources)
3. **role_permissions** - Maps roles to their permissions
4. **company_members** - Maps users to companies with role assignments
5. **company_invitations** - Tracks email invitations to join companies

---

## Automatic Migration

### Existing Company Owners

Migration V83 automatically migrates existing company owners:

```sql
-- Creates company_members records for all existing companies
INSERT INTO company_members (id, company_id, user_id, email, role_id, role_name, status)
SELECT
    gen_random_uuid()::text,
    c.id,
    c.email,  -- Temporary: will be updated to Supabase user_id on first login
    c.email,
    'owner',
    'Owner',
    'enabled'
FROM companies c
WHERE c.email IS NOT NULL;
```

**Important**: The `user_id` is temporarily set to the company's email. It will be updated to the actual Supabase user ID when the user logs in and the JWT is processed.

---

## JWT Requirements

### Supabase JWT Format

For RBAC to work, Supabase JWTs must include a custom `roles` claim:

```json
{
  "sub": "user-uuid-from-supabase",
  "email": "user@example.com",
  "role": "authenticated",
  "roles": [
    "123:companies:view",
    "123:companies:edit",
    "123:projects:view",
    "123:projects:create",
    "123:documents:approve"
  ]
}
```

### Roles Claim Format

Each role in the `roles` array follows the pattern:
```
{companyId}:{resource}:{action}
```

**Examples**:
- `"123:companies:view"` - Permission to view company 123
- `"456:documents:approve"` - Permission to approve documents in company 456
- `"789:purchase_orders:create"` - Permission to create POs in company 789

### How to Add Roles Claim to Supabase JWT

You'll need to configure Supabase to include the custom `roles` claim. This is typically done using:

**Option 1: Supabase Database Function**

Create a function that queries `company_members` and `role_permissions` tables to build the roles array, then configure Supabase Auth to call this function.

**Option 2: Supabase Edge Function**

Create an Edge Function that:
1. Queries the user's company memberships
2. Fetches their role permissions
3. Builds the roles array
4. Returns custom JWT claims

**Example Pseudocode**:
```sql
-- Function to get user's roles for JWT
CREATE OR REPLACE FUNCTION get_user_roles(user_id UUID)
RETURNS JSONB AS $$
  SELECT jsonb_agg(
    cm.company_id || ':' || rp.resource || ':' || rp.action
  )
  FROM company_members cm
  JOIN role_permissions rp ON rp.role_id = cm.role_id
  WHERE cm.user_id = user_id::text
  AND cm.status = 'enabled';
$$ LANGUAGE SQL;
```

---

## Authorization Flow

### 1. User Authentication

```
User logs in → Supabase Auth → JWT with custom roles claim
```

### 2. JWT Processing

```java
// JwtAuthenticationConverter extracts roles from JWT
Set<GrantedAuthority> authorities = extractRoles(jwt);

// Format: ROLE_{companyId}:{resource}:{action}
// Example: ROLE_123:documents:approve
```

### 3. Authorization Check

```java
@PreAuthorize("hasAuthority('ROLE_' + #companyId + ':documents:approve')")
public void approveDocument(Long companyId, String documentId) {
    // User must have "companyId:documents:approve" in their JWT roles claim
}
```

### 4. Company Access Check

```java
// CompanyAccessEvaluator checks company_members table
@PreAuthorize("@authz.hasCompanyAccess(#companyId)")
```

---

## User Lifecycle

### Creating a Company

1. User signs up via Supabase Auth
2. User creates a company
3. `company_members` record created with role = 'owner'
4. User's next JWT will include permissions for that company

### Inviting Team Members

1. Owner creates invitation with email and role
2. Invitation email sent with unique invite code
3. Recipient clicks link, signs up/logs in
4. Invitation accepted → `company_members` record created
5. User's JWT includes permissions for the new company

### Changing Roles

1. Owner changes member's role in `company_members`
2. Member's next JWT refresh includes new permissions
3. Previous JWT still valid until expiry (consider token refresh)

### Removing Members

1. Owner deletes `company_members` record
2. Member's JWT still valid until expiry
3. Next JWT refresh excludes removed company
4. Authorization checks fail (no matching company_members record)

---

## Testing RBAC

### Verify Migration

```sql
-- Check that all existing companies have owner members
SELECT
    c.id,
    c.name,
    cm.email,
    cm.role_name
FROM companies c
LEFT JOIN company_members cm ON cm.company_id = c.id AND cm.role_id = 'owner'
ORDER BY c.id;
```

Expected: Every company should have at least one owner member.

### Verify Permissions

```sql
-- Check permission counts per role
SELECT
    r.role_name,
    COUNT(*) as permission_count
FROM company_roles r
LEFT JOIN role_permissions rp ON rp.role_id = r.id
GROUP BY r.role_name
ORDER BY permission_count DESC;
```

Expected counts:
- owner: 29 permissions
- admin: 24 permissions
- operations: 13 permissions
- viewer: 9 permissions

### Test Authorization

```bash
# Get JWT token with roles claim
TOKEN="your-jwt-token-here"

# Test endpoint with authorization
curl -H "Authorization: Bearer $TOKEN" \
     -H "X-Context-Id: 123" \
     https://api.example.com/v1/documents
```

---

## Troubleshooting

### Issue: User has no permissions

**Symptoms**: 403 Forbidden on all requests

**Checks**:
1. Verify JWT contains `roles` claim: Decode JWT at jwt.io
2. Verify `company_members` record exists: Query DB
3. Verify role has permissions: Query `role_permissions`
4. Verify JWT hasn't expired

### Issue: Permissions not updating

**Symptoms**: Role changed but user still has old permissions

**Solution**: JWT caching. Wait for JWT to expire or force token refresh.

### Issue: Migration didn't run

**Symptoms**: No `company_members` records

**Solution**:
```bash
# Check Flyway migration status
./gradlew flywayInfo

# Run migrations manually
./gradlew flywayMigrate
```

---

## Future Enhancements

### Phase 2: Team Management APIs

- Endpoints to invite/remove members
- Endpoints to change roles
- Invitation email sending
- Invitation acceptance workflow

### Phase 3: Authorization Enforcement

- Add `@PreAuthorize` to all 50+ existing endpoints
- Update `CompanyAccessEvaluator` to check `company_members`
- Implement permission-based checks

### Phase 4: Advanced Features

- Custom roles per company
- Company-specific permission overrides
- Audit logging of all actions
- Permission analytics

---

## Support

For questions or issues:
1. Check existing GitHub issues
2. Review RBAC_ROLES.md for permission details
3. Verify Flyway migrations ran successfully
4. Check Supabase JWT configuration
