# MyTripAdvision Agent on AWS (Terraform)

Runs the Spring Boot app completely on AWS and uses **Amazon Bedrock** foundation models as the LLM.
There is nothing to "deploy" for Bedrock itself: it is a managed service. The app calls it, and
Terraform only gives the app permission to call the two models you choose.

```
 Internet ──► Application Load Balancer ──► ECS Fargate task (Spring Boot, port 8000)
 (allowed_cidrs only)   public subnets          │        │        │
                                                │        │        └─► Bedrock (task role, no keys)
                                                │        └─► Tavily / OpenWeather / AviationStack
                                                └─► RDS PostgreSQL + pgvector (private subnets)

 ECR (image)   Secrets Manager (API keys, DB password)   CloudWatch Logs (/ecs/mytripadvision)
```

## What gets created

| File | Resources |
|---|---|
| `providers.tf`, `locals.tf`, `variables.tf`, `outputs.tf` | Terraform setup, shared names and tags, all inputs, outputs |
| `network.tf` | VPC, 2 public + 2 private subnets, internet gateway, route table |
| `security_groups.tf` | Security groups: internet → ALB → app → database |
| `ecr.tf` | ECR repository with a keep-last-10 lifecycle policy |
| `rds.tf` | RDS PostgreSQL 17 (encrypted, private), generated password |
| `secrets.tf` | Secrets Manager secret: API keys and database password |
| `logs.tf` | CloudWatch log group |
| `iam.tf` | ECS execution role (pull image, read secret) and task role (**only** `bedrock:InvokeModel*` on the configured models) |
| `alb.tf` | Load balancer, target group (health check `/actuator/health/readiness`), HTTP listener, optional HTTPS |
| `ecs.tf` | ECS cluster, task definition, Fargate service with automatic rollback |

Every resource is named `<project>-<environment>-<role>` (`mytripadvision-prod-alb`, `mytripadvision-prod-db`,
`/ecs/mytripadvision-prod`), from the `project` and `environment` inputs, and tagged with both. Two variable
files cover the two targets: `aws.tfvars.example` (real AWS) and `floci.tfvars.example` (local emulator).
Both list every input, including the API key placeholders; copy the one you need without the `.example`
(both copies are git-ignored).

The app reads no AWS keys. On ECS it uses the task role; Bedrock calls are signed with that role.

## Before you start

1. **AWS credentials that work.** Check with `aws sts get-caller-identity`. If it fails, run `aws configure`
   or `aws sso login`.
2. **Bedrock model access.** In the AWS console, open Bedrock in your region and check that the models are
   available. Amazon Nova works out of the box. Anthropic Claude needs a one-time use-case form first.
   List what you can use: `aws bedrock list-inference-profiles --region us-east-1`
3. **Tools:** Terraform 1.5+, Docker (with buildx), AWS CLI v2.
4. **Variables:** `cp terraform/aws.tfvars.example terraform/aws.tfvars` and fill it in (`deploy.sh` reads that file).
   `allowed_cidrs` has no default on purpose: the app has no login, and each trip costs LLM and API money.

## Deploy

```bash
cd infra
./deploy.sh
```

The script (1) checks your credentials, (2) creates the ECR repository, (3) builds the image for
`linux/amd64` and pushes it, (4) runs `terraform apply`, which shows the plan and **waits for you to type `yes`**.
When it finishes it prints the URL. The first start takes a few minutes (database creation takes about 10).

To look around first without changing anything:

```bash
cd infra/terraform
terraform init
terraform plan
```

## Day to day

| Task | Command |
|---|---|
| Follow the logs | `aws logs tail /ecs/mytripadvision --follow` |
| Deploy a new version | `./deploy.sh` (new image tag, rolling update, rolls back if it never gets healthy) |
| Change the models | edit `bedrock_model_id` / `bedrock_final_model_id` in `aws.tfvars`, run `terraform apply -var-file=aws.tfvars` |
| Remove everything | `cd terraform && terraform destroy` |

`terraform destroy` deletes the database and its data. For anything you want to keep, set
`db_deletion_protection = true` and `db_skip_final_snapshot = false` in `aws.tfvars`.

## Cost (rough, us-east-1, always on)

| Item | About per month |
|---|---|
| Application Load Balancer | 16-20 USD |
| Fargate, 1 vCPU / 2 GB, one task | 36 USD |
| RDS db.t4g.micro + 20 GB | 14 USD |
| Secrets Manager, logs, ECR | 1-3 USD |
| Bedrock | pay per token (Nova: cents per trip) |

That is roughly 70-75 USD a month before Bedrock. Check current prices on the AWS pricing pages.
`terraform destroy` stops all of it.

## Things to know

- **No login.** Restrict `allowed_cidrs` to your IP, or put a login (Cognito) in front before opening it up.
- **HTTP only by default.** Set `certificate_arn` (an ACM certificate for your domain) to serve HTTPS.
- **No NAT gateway.** The tasks have public IPs for outbound traffic to the internet, but their security
  group blocks all inbound traffic except from the load balancer. A NAT gateway (about 32 USD a month)
  would let you move them into private subnets.
- **State file.** `terraform.tfstate` is local and holds the generated database password. Keep it private,
  or switch to an S3 backend (see the comment in `providers.tf`).
- **CPU architecture.** The image is built for `linux/amd64`. On an Apple Silicon Mac the build is emulated
  and takes longer. To use ARM (cheaper), set `cpu_architecture = "ARM64"` and change the platform in
  `deploy.sh`; check that the native embedding libraries support it first.
- **Bedrock errors** show up in the app log as `llm: AccessDeniedException` (no permission or model not
  enabled), `ValidationException` (wrong model id) or `ThrottlingException` (too many requests).

## Try the whole stack locally on Floci (no AWS account)

[Floci](https://floci.io) is a local AWS emulator on `http://localhost:4566` (LocalStack-compatible, `tflocal` works).
Its ECS runs tasks as real Docker containers, its RDS is a real PostgreSQL container behind a proxy, and the ALB
listener really forwards, so the same Terraform brings the app up locally. Verified on 2026-11-12 (Floci 2.3.0):
35 resources, ECS task healthy behind the ALB, Secrets Manager values resolved into the task, real answers through
Floci's Bedrock proxy.

1. Start Floci with the settings below. The defaults would give a database without pgvector (the task then fails at
   `CREATE EXTENSION vector`) and a Bedrock that answers every prompt with the fixed text `Floci stub response for
   model=...`. The proxy backend forwards Bedrock Converse calls to any OpenAI-compatible server; this example uses
   Ollama with the small llama model (fast enough for the agents' 120 s call limit, modest answers). Map the two Nova
   ids to the models you want; for OpenAI use `https://api.openai.com/v1`, your key in
   `FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_API_KEY`, and `...=gpt-4o-mini,...=gpt-4o`.

   ```bash
   docker run -d --name floci --restart unless-stopped \
     -p 4566:4566 -p 127.0.0.1:80:80 \
     -v /var/run/docker.sock:/var/run/docker.sock -v floci-data:/app/data \
     -e FLOCI_SERVICES_RDS_DEFAULT_POSTGRES_IMAGE=pgvector/pgvector:pg17 \
     -e FLOCI_SERVICES_BEDROCK_RUNTIME_BACKEND=proxy \
     -e FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_URL=http://host.docker.internal:11434/v1 \
     -e FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_MODEL_MAPPING=us.amazon.nova-lite-v1:0=llama3.2:1b,us.amazon.nova-pro-v1:0=llama3.2:1b \
     floci/floci:latest
   ```

   Port 80 is the ALB listener (it binds inside the Floci container), so the app is at `http://localhost/` once the
   task is healthy. `floci-data` keeps the resources across restarts; a plain `floci start` loses them.
2. Build the image under its ECR name (a local image with that exact name is used as is; the push is optional):
   `docker build -t 000000000000.dkr.ecr.us-east-1.localhost:4566/mytripadvision-floci:floci .`
   (the repository is `<project>-<environment>`, so `mytripadvision-floci`)
3. `cd infra/terraform && cp floci.tfvars.example floci.tfvars && tflocal init && tflocal apply -var-file=floci.tfvars`
   (`AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test`). `floci.tfvars` sets `environment = "floci"`,
   `db_sslmode = "disable"`, `bedrock_endpoint = "http://host.docker.internal:4566"` (the task calls Floci's
   Bedrock) and dummy AWS credentials through `extra_environment`. Put your real Tavily, OpenWeather and
   AviationStack keys in it for hotels, weather and flights to work.
4. Open `http://localhost/`. Logs: `docker logs <floci-aws-ecs-...-app>` or `aws --endpoint-url http://localhost:4566 logs tail /ecs/mytripadvision`.

LocalStack's Bedrock (a paid feature) runs models through Ollama the same way; its free edition has no Bedrock.
