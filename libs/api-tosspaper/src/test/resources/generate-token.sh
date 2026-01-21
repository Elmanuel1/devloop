#!/bin/bash
# generate-jwt.sh

set -euo pipefail

echo "🔑 Generating test JWT..."

# Ensure pyjwt is installed
if ! python3 -c "import jwt" &>/dev/null; then
  echo "📦 Installing required Python package: PyJWT"
  pip3 install --user pyjwt
fi

# Generate JWT and write to token.jwt
python3 << 'EOF'
import jwt
from datetime import datetime, timedelta

# Load private key
with open("private-key.pem", "rb") as f:
    private_key = f.read()

# Create payload
payload = {
    "sub": "aribooluwatoba@gmail.com",
    "company_id": "1",
    "iss": "localhost",
    "aud": "toss-paper",
    "exp": int((datetime.utcnow() + timedelta(days=365 * 100)).timestamp())
}

# Create token
token = jwt.encode(
    payload,
    private_key,
    algorithm="RS256",
    headers={"kid": "test-key-1"}
)

# Write token to file
with open("token.jwt", "w") as f:
    f.write(token)
EOF

echo "✅ JWT written to $(pwd)/token.jwt"
