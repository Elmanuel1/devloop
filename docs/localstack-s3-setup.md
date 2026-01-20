# LocalStack S3 Setup

## Overview

LocalStack provides local AWS S3 emulation for development. Presigned URLs are proxied through nginx so they work from external browsers.

## Why LocalStack?

| Concern | Without LocalStack | With LocalStack |
|---------|-------------------|-----------------|
| **Cost** | S3 charges for storage + requests | Free |
| **Data isolation** | Dev data mixed with prod | Completely isolated |
| **Network dependency** | Requires internet | Works offline |
| **Speed** | Network latency to AWS | Local, instant |
| **Credentials** | Each dev needs AWS access | No credentials needed |
| **Cleanup** | Manual bucket cleanup | `docker compose down -v` |

## Architecture

```
Browser/Client
      │
      ▼
https://dev-api.tosspaper.com/s3/bucket/key?signature=...
      │
      ▼
   [nginx]
      │ location /s3/ → proxy_pass http://localstack:4566/
      │ (strips /s3/ prefix)
      ▼
 [LocalStack:4566]
   S3 service
      │
      ▼
   bucket/key
```

**Dev flow:**
1. App generates presigned URL: `https://dev-api.tosspaper.com/s3/bucket/key?X-Amz-Signature=...`
2. Browser requests this URL
3. nginx strips `/s3/` and proxies to `http://localstack:4566/bucket/key?...`
4. LocalStack validates signature and serves file

**Prod flow:**
1. App generates presigned URL: `https://s3.amazonaws.com/bucket/key?X-Amz-Signature=...`
2. Browser requests AWS S3 directly

## Configuration

### Docker Compose

```yaml
# docker-compose.yml
localstack:
  image: localstack/localstack:3.0
  ports:
    - "4566:4566"
  environment:
    - SERVICES=sqs,s3
    - PERSISTENCE=1
  volumes:
    - localstack-data:/var/lib/localstack
    - ./localstack/init-aws.sh:/etc/localstack/init/ready.d/init-aws.sh
```

### nginx

```nginx
# nginx/nginx.conf
location /s3/ {
    proxy_pass http://localstack:4566/;  # trailing slash strips /s3/ prefix
    proxy_set_header Host $host;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
}
```

### Application

```yaml
# application.yml
aws:
  bucket:
    region: ${AWS_REGION:us-east-1}
    name: ${AWS_S3_BUCKET_NAME:tosspaper-email-attachments}
    endpoint: ${AWS_S3_ENDPOINT:}  # Dev: https://dev-api.tosspaper.com/s3, Prod: empty
    path-style-access: ${AWS_S3_PATH_STYLE:false}
```

### Environment Variables

| Environment | `AWS_S3_ENDPOINT` | `AWS_S3_PATH_STYLE` |
|-------------|-------------------|---------------------|
| Local | `http://localhost:4566` | `true` |
| Dev (remote) | `https://dev-api.tosspaper.com/s3` | `true` |
| Production | (empty) | `false` |

## Init Script

The `localstack/init-aws.sh` script runs on LocalStack startup:

```bash
# Creates S3 bucket
awslocal s3 mb s3://tosspaper-email-attachments

# Creates SQS queues with DLQs
# - tosspaper-email-local-uploads
# - tosspaper-ai-process
# - tosspaper-vector-store-ingestion
# - tosspaper-document-approved-events
# - tosspaper-quickbooks-events
# - tosspaper-integration-push-events
```

## Testing Locally

```bash
# Start LocalStack
docker compose up localstack -d

# Verify S3 bucket exists
aws --endpoint-url=http://localhost:4566 s3 ls

# Upload test file
aws --endpoint-url=http://localhost:4566 s3 cp test.txt s3://tosspaper-email-attachments/

# Generate presigned URL (via app or AWS CLI)
aws --endpoint-url=http://localhost:4566 s3 presign s3://tosspaper-email-attachments/test.txt
```

## Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| Presigned URL returns 403 | Wrong endpoint in signature | Ensure `AWS_S3_ENDPOINT` matches the URL base |
| Connection refused | LocalStack not running | `docker compose up localstack` |
| Bucket not found | Init script failed | Check `docker logs tosspaper-localstack` |
