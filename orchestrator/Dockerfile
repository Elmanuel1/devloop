FROM python:3.12-slim

# Install gh CLI
RUN apt-get update && apt-get install -y --no-install-recommends \
      curl git && \
    curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
      -o /usr/share/keyrings/githubcli-archive-keyring.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
      > /etc/apt/sources.list.d/github-cli.list && \
    apt-get update && apt-get install -y --no-install-recommends gh && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy orchestrator scripts
COPY poll.py state.py confluence.py jira.py ./

# State directory — mount as a volume so data persists across restarts
RUN mkdir -p /data/handler_state

ENV PYTHONUNBUFFERED=1
ENV STATE_DIR=/data

ENTRYPOINT ["python3", "poll.py"]
