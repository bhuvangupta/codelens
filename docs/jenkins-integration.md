# Jenkins CI/CD Integration Guide

CodeLens provides a dedicated API for CI/CD tools like Jenkins, GitHub Actions, GitLab CI, and others to trigger automated PR reviews.

## Quick Start

### 1. Generate an API Key

```bash
openssl rand -hex 32
```

### 2. Configure CodeLens

Add to your `.env` file:

```bash
CI_API_KEY_1=your-generated-api-key-here
```

Or set in `application.yaml`:

```yaml
codelens:
  ci:
    enabled: true
    require-api-key: true
    require-ip-whitelist: false
    api-keys:
      - ${CI_API_KEY_1:}
```

### 3. Test the Integration

```bash
curl -X POST https://your-codelens-server:9292/api/ci/review \
  -H "X-API-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{"prUrl": "https://github.com/owner/repo/pull/123"}'
```

---

## API Reference

### Trigger Review

**POST** `/api/ci/review`

Triggers an asynchronous PR review.

#### Headers

| Header | Required | Description |
|--------|----------|-------------|
| `X-API-Key` | Yes | Your CI API key |
| `Content-Type` | Yes | `application/json` |

#### Request Body

```json
{
  "prUrl": "https://github.com/owner/repo/pull/123",
  "llmProvider": "gemini",
  "callbackUrl": "https://your-server.com/webhook",
  "metadata": "jenkins-build-123",
  "priority": "normal"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `prUrl` | Yes | GitHub or GitLab PR/MR URL |
| `llmProvider` | No | Override default LLM (gemini, claude, openai, ollama, glm) |
| `callbackUrl` | No | URL to POST results when complete |
| `metadata` | No | Custom data returned in response (e.g., build ID) |
| `priority` | No | `normal` or `high` |

#### Response

```json
{
  "reviewId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING",
  "message": "Review triggered successfully",
  "prUrl": "https://github.com/owner/repo/pull/123"
}
```

---

### Get Review Status

**GET** `/api/ci/review/{reviewId}`

Poll for review completion status.

#### Response (In Progress)

```json
{
  "reviewId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "IN_PROGRESS",
  "message": "Review in_progress",
  "prUrl": "https://github.com/owner/repo/pull/123"
}
```

#### Response (Completed)

```json
{
  "reviewId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "COMPLETED",
  "message": "Review completed",
  "prUrl": "https://github.com/owner/repo/pull/123",
  "summary": "This PR adds user authentication...",
  "issuesFound": 5,
  "criticalIssues": 0,
  "highIssues": 2,
  "mediumIssues": 2,
  "lowIssues": 1
}
```

#### Status Values

| Status | Description |
|--------|-------------|
| `PENDING` | Review queued |
| `IN_PROGRESS` | Review running |
| `COMPLETED` | Review finished successfully |
| `FAILED` | Review failed (check error message) |

---

### Health Check

**GET** `/api/ci/health`

No authentication required. Returns `OK` if service is running.

---

## Jenkins Pipeline Examples

### Declarative Pipeline

```groovy
pipeline {
    agent any

    environment {
        CODELENS_URL = 'https://codelens.example.com'
        CODELENS_API_KEY = credentials('codelens-api-key')
    }

    stages {
        stage('AI Code Review') {
            when {
                changeRequest()
            }
            steps {
                script {
                    // Trigger review
                    def triggerResponse = httpRequest(
                        url: "${CODELENS_URL}/api/ci/review",
                        httpMode: 'POST',
                        customHeaders: [
                            [name: 'X-API-Key', value: CODELENS_API_KEY],
                            [name: 'Content-Type', value: 'application/json']
                        ],
                        requestBody: """{
                            "prUrl": "${env.CHANGE_URL}",
                            "metadata": "${env.BUILD_ID}"
                        }"""
                    )

                    def result = readJSON text: triggerResponse.content
                    def reviewId = result.reviewId

                    echo "Review triggered: ${reviewId}"

                    // Poll for completion (max 5 minutes)
                    def maxAttempts = 30
                    def attempt = 0
                    def reviewComplete = false

                    while (!reviewComplete && attempt < maxAttempts) {
                        sleep(time: 10, unit: 'SECONDS')
                        attempt++

                        def statusResponse = httpRequest(
                            url: "${CODELENS_URL}/api/ci/review/${reviewId}",
                            httpMode: 'GET',
                            customHeaders: [
                                [name: 'X-API-Key', value: CODELENS_API_KEY]
                            ]
                        )

                        def status = readJSON text: statusResponse.content

                        if (status.status == 'COMPLETED') {
                            reviewComplete = true
                            echo "Review completed!"
                            echo "Issues found: ${status.issuesFound}"
                            echo "Critical: ${status.criticalIssues}, High: ${status.highIssues}"

                            // Fail build if critical issues found
                            if (status.criticalIssues > 0) {
                                error "Code review found ${status.criticalIssues} critical issues!"
                            }
                        } else if (status.status == 'FAILED') {
                            error "Code review failed: ${status.message}"
                        }
                    }

                    if (!reviewComplete) {
                        echo "Review timed out, continuing..."
                    }
                }
            }
        }
    }
}
```

### Scripted Pipeline (Simple)

```groovy
node {
    stage('AI Code Review') {
        if (env.CHANGE_URL) {
            withCredentials([string(credentialsId: 'codelens-api-key', variable: 'API_KEY')]) {
                sh """
                    curl -X POST https://codelens.example.com/api/ci/review \
                      -H "X-API-Key: ${API_KEY}" \
                      -H "Content-Type: application/json" \
                      -d '{"prUrl": "${env.CHANGE_URL}"}'
                """
            }
        }
    }
}
```

### Shared Library Function

Create `vars/codeReview.groovy`:

```groovy
def call(Map config = [:]) {
    def codelensUrl = config.url ?: env.CODELENS_URL
    def prUrl = config.prUrl ?: env.CHANGE_URL
    def credentialsId = config.credentialsId ?: 'codelens-api-key'
    def failOnCritical = config.failOnCritical ?: true
    def timeout = config.timeout ?: 300  // seconds

    if (!prUrl) {
        echo "No PR URL found, skipping code review"
        return null
    }

    withCredentials([string(credentialsId: credentialsId, variable: 'API_KEY')]) {
        // Trigger review
        def response = httpRequest(
            url: "${codelensUrl}/api/ci/review",
            httpMode: 'POST',
            customHeaders: [
                [name: 'X-API-Key', value: API_KEY],
                [name: 'Content-Type', value: 'application/json']
            ],
            requestBody: """{"prUrl": "${prUrl}"}"""
        )

        def result = readJSON text: response.content
        def reviewId = result.reviewId

        // Poll for completion
        def startTime = System.currentTimeMillis()
        while (true) {
            if ((System.currentTimeMillis() - startTime) > timeout * 1000) {
                echo "Code review timed out"
                return null
            }

            sleep(time: 10, unit: 'SECONDS')

            def statusResponse = httpRequest(
                url: "${codelensUrl}/api/ci/review/${reviewId}",
                httpMode: 'GET',
                customHeaders: [[name: 'X-API-Key', value: API_KEY]]
            )

            def status = readJSON text: statusResponse.content

            if (status.status == 'COMPLETED') {
                if (failOnCritical && status.criticalIssues > 0) {
                    error "Code review found ${status.criticalIssues} critical issues"
                }
                return status
            } else if (status.status == 'FAILED') {
                error "Code review failed: ${status.message}"
            }
        }
    }
}
```

Usage in Jenkinsfile:

```groovy
pipeline {
    stages {
        stage('Code Review') {
            steps {
                script {
                    def review = codeReview(
                        url: 'https://codelens.example.com',
                        failOnCritical: true,
                        timeout: 300
                    )
                    if (review) {
                        echo "Found ${review.issuesFound} issues"
                    }
                }
            }
        }
    }
}
```

---

## Security Configuration

### API Key Authentication

Required by default. Configure keys in `application.yaml`:

```yaml
codelens:
  ci:
    require-api-key: true
    api-keys:
      - ${CI_API_KEY_1}  # Jenkins
      - ${CI_API_KEY_2}  # GitHub Actions
```

### IP Whitelisting

Optional additional security layer:

```yaml
codelens:
  ci:
    require-ip-whitelist: true
    allowed-ips:
      - 127.0.0.1           # Localhost
      - 10.0.0.0/8          # Private network
      - 192.168.1.100       # Specific Jenkins server
      - 52.23.45.67         # Jenkins cloud IP
```

CIDR notation is supported for network ranges.

### Both API Key + IP Whitelist

For maximum security, enable both:

```yaml
codelens:
  ci:
    require-api-key: true
    require-ip-whitelist: true
    api-keys:
      - ${CI_API_KEY_1}
    allowed-ips:
      - 10.0.0.0/8
```

---

## GitHub Actions Example

```yaml
name: AI Code Review

on:
  pull_request:
    types: [opened, synchronize]

jobs:
  code-review:
    runs-on: ubuntu-latest
    steps:
      - name: Trigger CodeLens Review
        id: review
        run: |
          RESPONSE=$(curl -s -X POST "${{ secrets.CODELENS_URL }}/api/ci/review" \
            -H "X-API-Key: ${{ secrets.CODELENS_API_KEY }}" \
            -H "Content-Type: application/json" \
            -d "{\"prUrl\": \"${{ github.event.pull_request.html_url }}\"}")

          REVIEW_ID=$(echo $RESPONSE | jq -r '.reviewId')
          echo "review_id=$REVIEW_ID" >> $GITHUB_OUTPUT

      - name: Wait for Review
        run: |
          for i in {1..30}; do
            RESPONSE=$(curl -s "${{ secrets.CODELENS_URL }}/api/ci/review/${{ steps.review.outputs.review_id }}" \
              -H "X-API-Key: ${{ secrets.CODELENS_API_KEY }}")

            STATUS=$(echo $RESPONSE | jq -r '.status')

            if [ "$STATUS" = "COMPLETED" ]; then
              CRITICAL=$(echo $RESPONSE | jq -r '.criticalIssues')
              echo "Review completed. Critical issues: $CRITICAL"
              if [ "$CRITICAL" -gt "0" ]; then
                exit 1
              fi
              exit 0
            elif [ "$STATUS" = "FAILED" ]; then
              echo "Review failed"
              exit 1
            fi

            sleep 10
          done
          echo "Review timed out"
```

---

## GitLab CI Example

```yaml
code-review:
  stage: test
  only:
    - merge_requests
  script:
    - |
      RESPONSE=$(curl -s -X POST "$CODELENS_URL/api/ci/review" \
        -H "X-API-Key: $CODELENS_API_KEY" \
        -H "Content-Type: application/json" \
        -d "{\"prUrl\": \"$CI_MERGE_REQUEST_PROJECT_URL/-/merge_requests/$CI_MERGE_REQUEST_IID\"}")

      REVIEW_ID=$(echo $RESPONSE | jq -r '.reviewId')

      for i in $(seq 1 30); do
        STATUS_RESPONSE=$(curl -s "$CODELENS_URL/api/ci/review/$REVIEW_ID" \
          -H "X-API-Key: $CODELENS_API_KEY")

        STATUS=$(echo $STATUS_RESPONSE | jq -r '.status')

        if [ "$STATUS" = "COMPLETED" ]; then
          CRITICAL=$(echo $STATUS_RESPONSE | jq -r '.criticalIssues')
          echo "Critical issues: $CRITICAL"
          [ "$CRITICAL" -gt "0" ] && exit 1
          exit 0
        fi

        sleep 10
      done
```

---

## Troubleshooting

### 401 Unauthorized

- Check `X-API-Key` header is present
- Verify API key matches one in `codelens.ci.api-keys`

### 403 Forbidden

- IP not in whitelist (if `require-ip-whitelist: true`)
- Check `X-Forwarded-For` header if behind proxy

### 503 Service Unavailable

- CI integration is disabled (`codelens.ci.enabled: false`)

### Review Stuck in PENDING

- Check LLM provider is configured and has valid API key
- Check application logs for errors

---

## Environment Variables

| Variable | Description |
|----------|-------------|
| `CI_API_KEY_1` | Primary CI API key |
| `CI_API_KEY_2` | Secondary CI API key (optional) |

Generate secure keys:

```bash
openssl rand -hex 32
```
