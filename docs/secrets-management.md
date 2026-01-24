# Secrets Management with SOPS

This project uses [SOPS](https://github.com/getsops/sops) to encrypt secrets before committing to git.
Secrets are encrypted with AGE keys and decrypted at Terraform apply time.

## Prerequisites

1. Install SOPS and AGE:
   ```bash
   brew install sops age
   ```

2. Get the AGE private key for your environment (stored in GitHub environment secrets as `SOPS_AGE_KEY`)

## File Structure

```
secrets/
├── .sops.yaml                    # SOPS config (which AGE key for which file)
├── local-secrets.json            # Local dev (plaintext, gitignored)
├── stage-secrets.json            # Stage (plaintext, gitignored)
├── stage-secrets.enc.json        # Stage (encrypted, committed)
├── prod-secrets.json             # Prod (plaintext, gitignored)
└── prod-secrets.enc.json         # Prod (encrypted, committed)
```

## Adding/Updating Secrets

### Stage

```bash
# 1. Edit plaintext secrets
vim secrets/stage-secrets.json

# 2. Encrypt (uses .sops.yaml config automatically)
cd secrets
sops --encrypt stage-secrets.json > stage-secrets.enc.json

# 3. Commit & Apply
git add stage-secrets.enc.json
git commit -m "Update stage secrets"
cd ../terraform/parameters && terraform apply -var-file="stage.tfvars"
```

### Prod

```bash
# 1. Edit plaintext secrets
vim secrets/prod-secrets.json

# 2. Encrypt
cd secrets
sops --encrypt prod-secrets.json > prod-secrets.enc.json

# 3. Commit & Apply
git add prod-secrets.enc.json
git commit -m "Update prod secrets"
cd ../terraform/parameters && terraform apply -var-file="prod.tfvars"
```

## Viewing Encrypted Secrets

To decrypt and view (requires AGE private key):

```bash
# Set the private key
export SOPS_AGE_KEY="AGE-SECRET-KEY-..."

# Decrypt to stdout
sops --decrypt secrets/stage-secrets.enc.json

# Edit in-place (decrypts, opens editor, re-encrypts on save)
sops secrets/stage-secrets.enc.json
```

## CI/CD Setup

GitHub Actions workflows need the `SOPS_AGE_KEY` environment variable set:

```yaml
env:
  SOPS_AGE_KEY: ${{ secrets.SOPS_AGE_KEY }}
```

Each environment (stage, prod) has its own AGE key stored in GitHub environment secrets.

## Removing a Secret

1. Edit the plaintext file and remove the key
2. Re-encrypt
3. Commit
4. Run `terraform apply` - Terraform will destroy the removed SSM parameter

## AGE Keys

| Environment | Public Key (for encryption) |
|-------------|----------------------------|
| Stage | `age1le4qhcx28330qr0cklezgtdpfz7azflyscp55kps7e5mt28699ds53ufge` |
| Prod | `age177rcz99jakrh0rcmvmn7l93l2z02refk8q9f35r07pd8rrjj5ydsz34t48` |

Private keys are stored in GitHub environment secrets as `SOPS_AGE_KEY`.

## Troubleshooting

### "no matching creation rule found"
- Ensure you're running `sops` from the `secrets/` directory (where `.sops.yaml` is located)
- Check the `path_regex` matches your filename

### "failed to decrypt"
- Ensure `SOPS_AGE_KEY` environment variable is set with the correct private key
- Verify you're using the right key for the environment (stage vs prod)

### "could not decrypt data key"
- The file was encrypted with a different AGE key than the one you're using
- Re-encrypt the file with the correct public key
