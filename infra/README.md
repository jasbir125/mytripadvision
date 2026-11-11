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
| `network.tf` | VPC, 2 public + 2 private subnets, internet gateway, security groups (internet → ALB → app → database) |
| `backing_services.tf` | ECR repository, RDS PostgreSQL 17 (encrypted, private), Secrets Manager secret, CloudWatch log group |
| `iam.tf` | ECS execution role (pull image, read secret) and task role (**only** `bedrock:InvokeModel*` on the configured models) |
| `alb.tf` | Load balancer, target group (health check `/actuator/health/readiness`), HTTP listener, optional HTTPS |
| `ecs.tf` | ECS cluster, task definition, Fargate service with automatic rollback |

The app reads no AWS keys. On ECS it uses the task role; Bedrock calls are signed with that role.

## Before you start

1. **AWS credentials that work.** Check with `aws sts get-caller-identity`. If it fails, run `aws configure`
   or `aws sso login`.
2. **Bedrock model access.** In the AWS console, open Bedrock in your region and check that the models are
   available. Amazon Nova works out of the box. Anthropic Claude needs a one-time use-case form first.
   List what you can use: `aws bedrock list-inference-profiles --region us-east-1`
3. **Tools:** Terraform 1.5+, Docker (with buildx), AWS CLI v2.
4. **Variables:** `cp terraform/terraform.tfvars.example terraform/terraform.tfvars` and fill it in.
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
| Change the models | edit `bedrock_model_id` / `bedrock_final_model_id` in `terraform.tfvars`, run `terraform apply` |
| Remove everything | `cd terraform && terraform destroy` |

`terraform destroy` deletes the database and its data. For anything you want to keep, set
`db_deletion_protection = true` and `db_skip_final_snapshot = false` in `terraform.tfvars`.

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
  or switch to an S3 backend (see the comment in `versions.tf`).
- **CPU architecture.** The image is built for `linux/amd64`. On an Apple Silicon Mac the build is emulated
  and takes longer. To use ARM (cheaper), set `cpu_architecture = "ARM64"` and change the platform in
  `deploy.sh`; check that the native embedding libraries support it first.
- **Bedrock errors** show up in the app log as `llm: AccessDeniedException` (no permission or model not
  enabled), `ValidationException` (wrong model id) or `ThrottlingException` (too many requests).
