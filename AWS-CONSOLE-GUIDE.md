# E-commerce on AWS: Console Build Guide (Sale-Ready)

This is the order I would build it in. Every step uses the **AWS Management Console**. Do the steps in order, because later steps use resources from earlier ones.

Conventions used below:
- Region: **ap-south-1 (Mumbai)**. Any region with 3 Availability Zones works; just stay in one region.
- Name prefix: `ecom-`.
- Domain: `shop.example.com` for the site, `api-origin.example.com` for the ALB. Replace with your own domain.
- `<ACCOUNT_ID>` = your 12-digit AWS account ID.

---

## 0. Target architecture

```
                         Users (millions)
                               │
                     Route 53 (shop.example.com)
                               │
             AWS WAF  ──►  CloudFront (CDN, TLS, Shield Standard)
                       ┌───────┴─────────────────┐
                 default /*                   /api/*
                       │                         │  (HTTPS + secret header)
            S3 bucket: frontend         Application Load Balancer  (public subnets, 3 AZs)
            (private, OAC)                       │  path-based rules
                         ┌───────────────┬───────┴────────┐
                 /api/products*     /api/cart*       /api/orders*
                         │               │                │
                ┌────────▼───┐   ┌───────▼────┐   ┌───────▼─────┐   ECS Fargate
                │ product-svc│   │  cart-svc  │   │  order-svc  │   private app subnets
                │   :8081    │   │   :8082    │   │    :8083    │   min 3 tasks each (1/AZ)
                └─┬────┬───┬─┘   └─────┬──────┘   └──┬───────┬──┘   auto-scaling
                  │    │   │           │             │       │
                  │    │   └──► S3     │             │   Service Connect
                  │    │   (images)    │             │   (order ➜ product, cart)
                  │    ▼               ▼             │
                  │  ElastiCache    DynamoDB         │
                  │  (Valkey/Redis) (carts, on-      │
                  │                  demand)         │
                  ▼                                  ▼
               RDS Proxy  ─────────►  Aurora MySQL (writer + reader, Multi-AZ)
                                      private data subnets

  Cross-cutting: CloudWatch Logs + Container Insights + Alarms + Dashboard, CloudTrail,
                 GuardDuty, Security Hub, Secrets Manager, KMS, IAM task roles, VPC endpoints
```

| Requirement | AWS service used |
|---|---|
| Microservices runtime | ECS on **Fargate** (no servers to patch) |
| Web server / website | **CloudFront** + **S3** for the static site, **ALB** for APIs |
| SQL database | **Aurora MySQL** behind **RDS Proxy** |
| NoSQL database | **DynamoDB** (on-demand) |
| In-memory cache | **ElastiCache** (Valkey or Redis OSS) |
| File storage | **S3** (product images, logs, frontend) |
| Logging / monitoring | **CloudWatch** Logs, Container Insights, Alarms, Dashboards, Synthetics |
| Security | WAF, Shield, IAM roles, Secrets Manager, KMS, private subnets, Security Groups, GuardDuty, Security Hub, CloudTrail |

The three services:

| Service | Port | Data store | Endpoints |
|---|---|---|---|
| `product-service` | 8081 | Aurora `productdb`, Redis cache, S3 images | `GET/POST /api/products`, `GET/PUT/DELETE /api/products/{id}`, `POST /api/products/{id}/image`, `GET /api/products/{id}/image-url` |
| `cart-service` | 8082 | DynamoDB `ecom-carts` | `GET /api/cart/{userId}`, `POST /api/cart/{userId}/items`, `DELETE /api/cart/{userId}/items/{productId}`, `DELETE /api/cart/{userId}` |
| `order-service` | 8083 | Aurora `orderdb`; calls product & cart | `POST /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/user/{userId}` |

All three expose `/actuator/health/liveness` for health checks.

---

## Phase 1: AWS account and baseline security

### 1.1 Create the account
1. Go to https://aws.amazon.com → **Create an AWS Account**.
2. Use a **company distribution-list email** (e.g. `aws-root@yourco.com`), not a personal one.
3. Enter billing details and verify the phone number. Pick the **Business** support plan. You want AWS Support on call during a sale, and Business also gives you **Infrastructure Event Management (IEM)** guidance for planned peak events.

### 1.2 Lock down the root user
1. Sign in as root → top-right account menu → **Security credentials**.
2. **Assign MFA device**. Use a hardware key or an authenticator app.
3. Make sure **no root access keys** exist.
4. From now on, only use root for billing and account-level tasks.

### 1.3 Create admin users with IAM Identity Center (not IAM users)
1. Console → **IAM Identity Center** → **Enable**.
2. **Users** → Add user (yourself and other engineers). Require MFA: **Settings → Authentication → MFA: Every time they sign in**.
3. **Groups**: create `Admins` and `Developers`.
4. **Permission sets**: create `AdministratorAccess` (Admins) and `PowerUserAccess` (Developers).
5. **AWS accounts** → pick your account → assign the groups and permission sets.
6. Sign out of root. Sign in through the **AWS access portal URL** that Identity Center shows you.

> For a large company, put **AWS Organizations** + **Control Tower** in place to get separate `prod`, `staging` and `security` accounts. For this guide, one account is fine.

### 1.4 Cost guardrails
1. **Billing and Cost Management → Budgets → Create budget**: a monthly cost budget with 80% and 100% email alerts.
2. **Cost Anomaly Detection → Create monitor** (AWS services).

### 1.5 Audit and threat detection (turn on before you build anything)
1. **CloudTrail → Create trail** `ecom-trail`: all regions, new S3 bucket, log file validation **on**, SSE-KMS encryption.
2. **GuardDuty → Get started → Enable**. Also enable the S3, RDS, ECS Runtime Monitoring and Malware Protection features.
3. **Security Hub → Enable**. Turn on the *AWS Foundational Security Best Practices* and *CIS* standards. (This also prompts you to enable **AWS Config**: accept it and record all resources.)
4. **IAM → Access analyzer → Create analyzer** (account zone of trust).

### 1.6 Raise service quotas NOW (they take time to approve)
**Service Quotas → AWS services**, then request increases for:
- **Amazon ECS (Fargate)** → *Fargate On-Demand vCPU resource count*. Sizing math: 3 services × up to 50 tasks × 1 vCPU = 150 vCPU, plus headroom, so ask for **≥ 400**.
- **Amazon VPC** → *Elastic IPs* (≥ 5, one per NAT gateway).
- **Elastic Load Balancing** → defaults are usually fine. Check *Targets per ALB*.
- **Amazon RDS** → check *DB instances* and *Proxies* (defaults are fine).

---

## Phase 2: Domain and TLS certificates

### 2.1 Route 53 hosted zone
1. **Route 53 → Registered domains → Register domain**, or, if you own the domain elsewhere, **Hosted zones → Create hosted zone** `example.com` and point your registrar's NS records to the 4 Route 53 name servers.

### 2.2 ACM certificates (you need two)
1. **Certificate Manager in `us-east-1` (N. Virginia)**. CloudFront only accepts certificates from this region.
   Request public cert → `shop.example.com` (and `example.com` if needed) → DNS validation → **Create records in Route 53**.
2. **Certificate Manager in `ap-south-1`** (your region), for the ALB:
   Request public cert → `api-origin.example.com` → DNS validation → Create records in Route 53.
3. Wait until both show **Issued**.

---

## Phase 3: Network (VPC)

### 3.1 Create the VPC with the wizard
**VPC → Create VPC → "VPC and more"**:

| Setting | Value |
|---|---|
| Name tag auto-generation | `ecom` |
| IPv4 CIDR | `10.0.0.0/16` |
| Number of AZs | **3** |
| Number of public subnets | **3** |
| Number of private subnets | **6** (the wizard gives 2 per AZ) |
| NAT gateways | **1 per AZ** (high availability; never use a single NAT for production) |
| VPC endpoints | **S3 Gateway** |
| DNS hostnames / resolution | both **enabled** |

Click **Create VPC**.

### 3.2 Rename the private subnets into two tiers
**VPC → Subnets**: rename the six private subnets so the tiers are easy to tell apart:
- `ecom-app-a`, `ecom-app-b`, `ecom-app-c` (the first private subnet in each AZ): ECS tasks
- `ecom-data-a`, `ecom-data-b`, `ecom-data-c` (the second in each AZ): Aurora, ElastiCache

The public subnets hold **only** the ALB and the NAT gateways. Nothing else is ever public.

### 3.3 Add a DynamoDB gateway endpoint (free; keeps traffic off the NAT)
**VPC → Endpoints → Create endpoint** → AWS services → `com.amazonaws.ap-south-1.dynamodb` (type **Gateway**) → VPC `ecom-vpc` → tick **all private route tables** → Create.

### 3.4 Create security groups (least privilege, chained by SG ID)
**VPC → Security groups → Create security group** (VPC = `ecom-vpc`). Create them in this order:

| SG name | Inbound rules | Purpose |
|---|---|---|
| `ecom-alb-sg` | HTTPS 443 from **managed prefix list** `com.amazonaws.global.cloudfront.origin-facing` | Only CloudFront can reach the ALB |
| `ecom-app-sg` | TCP 8081-8083 from `ecom-alb-sg`; TCP 8081-8083 from `ecom-app-sg` (itself, for Service Connect) | ECS tasks |
| `ecom-db-sg` | MySQL 3306 from `ecom-app-sg`; MySQL 3306 from `ecom-dbproxy-sg` | Aurora |
| `ecom-dbproxy-sg` | MySQL 3306 from `ecom-app-sg` | RDS Proxy |
| `ecom-redis-sg` | TCP 6379 from `ecom-app-sg` | ElastiCache |
| `ecom-vpce-sg` | HTTPS 443 from `10.0.0.0/16` | Interface VPC endpoints |

Leave outbound as the default (all). Create `ecom-dbproxy-sg` before you add the second rule to `ecom-db-sg`.

### 3.5 Interface VPC endpoints (private access to AWS APIs, fewer NAT bytes)
**VPC → Endpoints → Create endpoint**, type **Interface**. For each endpoint: VPC `ecom-vpc`, subnets `ecom-app-a/b/c`, SG `ecom-vpce-sg`, Private DNS **enabled**:
- `com.amazonaws.ap-south-1.ecr.api`
- `com.amazonaws.ap-south-1.ecr.dkr`
- `com.amazonaws.ap-south-1.logs`
- `com.amazonaws.ap-south-1.secretsmanager`

(ECR image layers are pulled from S3, which the S3 gateway endpoint from 3.1 already covers.)

---

## Phase 4: Encryption key and secrets

### 4.1 KMS key
**KMS → Customer managed keys → Create key** → Symmetric → alias `ecom-key` → key administrators: your Admins role. Use this key for Aurora, ElastiCache, S3, CloudWatch Logs and secrets. (AWS-managed keys also work, but a customer-managed key gives you control and audit.)

---

## Phase 5: Data stores

### 5.1 Aurora MySQL (SQL)
1. **RDS → Subnet groups → Create DB subnet group** `ecom-db-subnets` → VPC `ecom-vpc` → subnets `ecom-data-a/b/c`.
2. **RDS → Create database**:

| Setting | Value |
|---|---|
| Method | Standard create |
| Engine | **Aurora (MySQL Compatible)**, latest 3.x (MySQL 8.0) |
| Template | **Production** |
| DB cluster identifier | `ecom-aurora` |
| Master username | `admin` |
| Credentials management | **Managed in AWS Secrets Manager**, encrypted with `ecom-key` |
| Cluster storage configuration | Aurora I/O-Optimized (predictable cost under heavy I/O) |
| Instance class | Memory optimized `db.r7g.large` or bigger (size up for the sale) |
| Availability & durability | **Create an Aurora Replica in a different AZ** |
| VPC / subnet group | `ecom-vpc` / `ecom-db-subnets` |
| Public access | **No** |
| Security group | `ecom-db-sg` only (remove `default`) |
| Encryption | Enabled, `ecom-key` |
| Monitoring | Database Insights / Performance Insights **on**, Enhanced Monitoring 60s |
| Log exports | error, slowquery |
| Backup retention | 7–35 days |
| Deletion protection | **Enabled** |

3. When it is ready, open the secret it created (**Secrets Manager → `rds!cluster-...`**) and copy the **secret ARN**.

The apps create their databases (`productdb`, `orderdb`) on first start (`createDatabaseIfNotExist=true`).

### 5.2 RDS Proxy (strongly recommended for a sale)
At peak, hundreds of tasks × 10 pooled connections each can exhaust Aurora's connection limit. RDS Proxy pools and multiplexes connections, and it **cuts failover time** too. It also reads the secret itself, so password rotation no longer breaks running tasks.

**RDS → Proxies → Create proxy**:
- Engine family **MariaDB and MySQL**, identifier `ecom-proxy`, **Require TLS** on
- Target: cluster `ecom-aurora`
- Secrets Manager secret: the `rds!cluster-...` secret
- IAM role: create new
- Subnets: `ecom-data-a/b/c`, security group `ecom-dbproxy-sg`

Copy the **proxy endpoint**. This becomes `DB_HOST` for the services.

> ⚠️ If you skip RDS Proxy: the RDS-managed secret rotates every 7 days by default, and ECS injects secrets only when a task starts. Either use the proxy, or turn off rotation during the sale window and force a new deployment after any rotation.

### 5.3 DynamoDB (NoSQL)
**DynamoDB → Tables → Create table**:
- Name `ecom-carts`, Partition key `userId` (String), Sort key `productId` (String)
- **Customize settings** → Capacity mode **On-demand**
- Encryption: AWS managed key or `ecom-key`
- Deletion protection **on**

After creation:
- **Backups → Point-in-time recovery → Enable**
- **Additional settings → Time to Live → Enable**, attribute `expiresAt` (the app sets it; abandoned carts are deleted for free)
- **Before the sale:** *Additional settings → Warm throughput* lets you pre-warm the table to your expected peak read/write units, so on-demand does not have to ramp up from cold.

### 5.4 ElastiCache (in-memory cache)
1. **ElastiCache → Subnet groups → Create** `ecom-cache-subnets` → `ecom-data-a/b/c`.
2. **ElastiCache → Valkey caches** (or Redis OSS caches) → **Create** → **Design your own cache** → **Cluster cache**:

| Setting | Value |
|---|---|
| Cluster mode | **Disabled** (one shard; simplest for Spring Cache) |
| Name | `ecom-cache` |
| Node type | `cache.r7g.large` |
| Number of replicas | **2** |
| Multi-AZ | **Enabled** (auto-failover) |
| Subnet group | `ecom-cache-subnets` |
| Encryption at rest | **On** (`ecom-key`) |
| Encryption in transit | **On** (the app uses TLS: `REDIS_SSL=true`) |
| Access control | *AUTH default user access*, with a strong token stored as a secret `ecom/redis-auth` (key `password`) in Secrets Manager. Or "No access control" if you rely on SGs only. |
| Security group | `ecom-redis-sg` |
| Backups | enabled, 1–7 days |

Copy the **Primary endpoint**. This becomes `REDIS_HOST`.

### 5.5 S3 buckets (file storage)
Create three buckets (**S3 → Create bucket**). For **each** bucket: Block *all* public access ✔, ACLs disabled, versioning on, default encryption SSE-KMS with `ecom-key` and **Bucket Key enabled**.

| Bucket | Purpose |
|---|---|
| `ecom-frontend-<ACCOUNT_ID>` | Static website files (served only through CloudFront) |
| `ecom-product-images-<ACCOUNT_ID>` | Product images uploaded by product-service |
| `ecom-logs-<ACCOUNT_ID>` | ALB / CloudFront access logs (use **SSE-S3** for this bucket: ALB log delivery does not support SSE-KMS) |

On the images bucket, add a **Bucket policy** that denies non-TLS access:
```json
{ "Version": "2012-10-17", "Statement": [{
  "Sid": "DenyInsecureTransport", "Effect": "Deny", "Principal": "*", "Action": "s3:*",
  "Resource": ["arn:aws:s3:::ecom-product-images-<ACCOUNT_ID>", "arn:aws:s3:::ecom-product-images-<ACCOUNT_ID>/*"],
  "Condition": { "Bool": { "aws:SecureTransport": "false" } } }] }
```

### 5.6 Remaining secret
**Secrets Manager → Store a new secret** → *Other type* → key `value`, value = a long random string → name `ecom/origin-verify`. CloudFront sends this header value to the ALB, and the ALB rejects requests without it (Phase 8).

---

## Phase 6: IAM roles for the tasks

**IAM → Roles → Create role** → Trusted entity *AWS service* → **Elastic Container Service → Elastic Container Service Task**.

### 6.1 Task execution role (used by ECS itself to pull images, write logs and read secrets)
- Name `ecomTaskExecutionRole`
- Attach managed policy **AmazonECSTaskExecutionRolePolicy**
- Add an inline policy:
```json
{ "Version": "2012-10-17", "Statement": [
  { "Effect": "Allow", "Action": "secretsmanager:GetSecretValue",
    "Resource": ["<rds!cluster secret ARN>", "<ecom/redis-auth secret ARN>"] },
  { "Effect": "Allow", "Action": "kms:Decrypt", "Resource": "<ecom-key ARN>" } ] }
```

### 6.2 Task roles (used by your Java code: the AWS SDK picks them up automatically, so there are no access keys)
`ecom-product-task-role`, inline policy:
```json
{ "Version": "2012-10-17", "Statement": [
  { "Effect": "Allow", "Action": ["s3:PutObject", "s3:GetObject"],
    "Resource": "arn:aws:s3:::ecom-product-images-<ACCOUNT_ID>/*" },
  { "Effect": "Allow", "Action": ["kms:GenerateDataKey", "kms:Decrypt"], "Resource": "<ecom-key ARN>" } ] }
```
`ecom-cart-task-role`, inline policy:
```json
{ "Version": "2012-10-17", "Statement": [
  { "Effect": "Allow",
    "Action": ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem", "dynamodb:Query", "dynamodb:UpdateItem"],
    "Resource": "arn:aws:dynamodb:ap-south-1:<ACCOUNT_ID>:table/ecom-carts" } ] }
```
`ecom-order-task-role`: no AWS permissions needed. Create it with no policies, so the service never runs with someone else's role.

---

## Phase 7: Build and push the container images (ECR)

### 7.1 Create repositories
**ECR → Private registry → Repositories → Create repository**, three times: `product-service`, `cart-service`, `order-service`.
- Image tag mutability: **Immutable**
- Encryption: KMS (`ecom-key`)
- After creation: **Image scanning → Enhanced scanning (Inspector)** under *Private registry → Settings*
- Add a **lifecycle policy**: keep the last 20 images

### 7.2 Build and push (one-time local step)
You need Docker Desktop and the AWS CLI v2 on your laptop. Sign in with `aws configure sso` using your Identity Center user. Open each repository in the console and click **View push commands**; it shows these commands with your account filled in:

```bash
aws ecr get-login-password --region ap-south-1 | docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.ap-south-1.amazonaws.com
```
```bash
cd product-service && docker build --platform linux/amd64 -t product-service:1.0.0 . && docker tag product-service:1.0.0 <ACCOUNT_ID>.dkr.ecr.ap-south-1.amazonaws.com/product-service:1.0.0 && docker push <ACCOUNT_ID>.dkr.ecr.ap-south-1.amazonaws.com/product-service:1.0.0
```
Repeat for `cart-service` and `order-service`. (The Dockerfiles run the Maven build inside Docker, so you don't need Java or Maven installed locally.)

> Later, automate this with **CodePipeline + CodeBuild** (source: GitHub or CodeCommit), so every merge builds, scans and deploys with a rolling or blue/green update.

---

## Phase 8: Load balancer

### 8.1 Target groups (one per service)
**EC2 → Target groups → Create target group**. Do this three times:

| Setting | product | cart | order |
|---|---|---|---|
| Target type | **IP addresses** | IP | IP |
| Name | `ecom-product-tg` | `ecom-cart-tg` | `ecom-order-tg` |
| Protocol : Port | HTTP : 8081 | HTTP : 8082 | HTTP : 8083 |
| VPC | `ecom-vpc` | same | same |
| Health check path | `/actuator/health/liveness` | same | same |
| Healthy / Unhealthy threshold | 2 / 3 | | |
| Interval / timeout | 10s / 5s | | |

Do not register targets; ECS registers them. After creation → **Attributes**: deregistration delay **30s**.

### 8.2 Application Load Balancer
**EC2 → Load balancers → Create → Application Load Balancer**:
- Name `ecom-alb`, **Internet-facing**, IPv4
- VPC `ecom-vpc`, mappings: the **3 public subnets**
- Security group: `ecom-alb-sg` only
- Listener **HTTPS : 443**, default action → **Return fixed response 403** (this blocks anything that didn't come through CloudFront)
- Certificate: the ACM cert for `api-origin.example.com`
- Security policy: `ELBSecurityPolicy-TLS13-1-2-2021-06`

After creation:
- **Attributes**: enable **access logs** → `s3://ecom-logs-<ACCOUNT_ID>/alb`; **Drop invalid header fields** on; **Deletion protection** on.
- **Listeners → HTTPS:443 → Manage rules → Add rule**, three rules. Each rule has **two conditions**: the path, plus an HTTP header `X-Origin-Verify` equal to the value of the `ecom/origin-verify` secret.

| Priority | Path condition | Header condition | Forward to |
|---|---|---|---|
| 10 | `/api/products*` | `X-Origin-Verify` = `<secret>` | `ecom-product-tg` |
| 20 | `/api/cart*` | same | `ecom-cart-tg` |
| 30 | `/api/orders*` | same | `ecom-order-tg` |

- **Route 53 → hosted zone → Create record** `api-origin.example.com`, **A – Alias** → Application Load Balancer → `ecom-alb`.

---

## Phase 9: ECS cluster, task definitions, services

### 9.1 Cluster
**ECS → Clusters → Create cluster**:
- Name `ecom-cluster`, infrastructure **AWS Fargate**
- **Monitoring → Container Insights with enhanced observability** ✔
- **Service Connect defaults → namespace** `ecom.local` (creates a Cloud Map namespace)
- Encryption: managed storage / ephemeral storage with `ecom-key` (optional)

### 9.2 Log groups
**CloudWatch → Log groups → Create**: `/ecs/product-service`, `/ecs/cart-service`, `/ecs/order-service`. Retention **30 days**, KMS key `ecom-key`. (To use the KMS key, the key policy must allow the `logs.ap-south-1.amazonaws.com` service principal. The console tells you if it doesn't.)

### 9.3 Task definitions
**ECS → Task definitions → Create new task definition** (form view). Common settings for all three:
- Launch type **AWS Fargate**, OS **Linux/X86_64**, CPU **1 vCPU**, Memory **2 GB**
- Task execution role `ecomTaskExecutionRole`
- Container: **Essential = Yes**, **read-only root filesystem** (add a 64 MiB volume mounted at `/tmp`; Spring needs a writable temp directory)
- **Log collection**: Amazon CloudWatch → log group `/ecs/<service>`, stream prefix `ecs`
- Container health check (optional): `CMD-SHELL, wget -qO- http://localhost:<port>/actuator/health/liveness || exit 1`

**product-service** (`ecom-product-td`), task role `ecom-product-task-role`, image `<ACCOUNT_ID>.dkr.ecr.ap-south-1.amazonaws.com/product-service:1.0.0`, port mapping **8081 TCP**, port name `product-http`, app protocol HTTP.

| Env var | Type | Value |
|---|---|---|
| `DB_HOST` | Value | RDS Proxy endpoint |
| `DB_USERNAME` | **ValueFrom** | `<rds secret ARN>:username::` |
| `DB_PASSWORD` | **ValueFrom** | `<rds secret ARN>:password::` |
| `REDIS_HOST` | Value | ElastiCache primary endpoint |
| `REDIS_SSL` | Value | `true` |
| `REDIS_PASSWORD` | ValueFrom | `<redis-auth secret ARN>:password::` (omit if no AUTH) |
| `S3_BUCKET` | Value | `ecom-product-images-<ACCOUNT_ID>` |
| `AWS_REGION` | Value | `ap-south-1` |

**cart-service** (`ecom-cart-td`), task role `ecom-cart-task-role`, port **8082**, port name `cart-http`.

| Env var | Value |
|---|---|
| `CART_TABLE` | `ecom-carts` |
| `AWS_REGION` | `ap-south-1` |

**order-service** (`ecom-order-td`), task role `ecom-order-task-role`, port **8083**, port name `order-http`.

| Env var | Type | Value |
|---|---|---|
| `DB_HOST` | Value | RDS Proxy endpoint |
| `DB_USERNAME` / `DB_PASSWORD` | ValueFrom | same as product-service |
| `PRODUCT_SERVICE_URL` | Value | `http://product-service:8081` |
| `CART_SERVICE_URL` | Value | `http://cart-service:8082` |

Secrets never appear in plain text in the task definition: ECS fetches them from Secrets Manager when the task starts.

### 9.4 Services
**ECS → `ecom-cluster` → Services → Create**. Create product and cart first, because order depends on them. For each service:

| Setting | Value |
|---|---|
| Compute | Launch type **FARGATE**, platform LATEST |
| Task definition | the matching family, latest revision |
| Service name | `product-service` / `cart-service` / `order-service` |
| Desired tasks | **3** (one per AZ, minimum for HA) |
| Deployment | Rolling; min healthy **100%**, max **200%**; **Deployment circuit breaker + rollback** ✔ |
| Availability Zone rebalancing | on |
| Networking | VPC `ecom-vpc`, subnets **`ecom-app-a/b/c`**, SG `ecom-app-sg`, **Public IP: OFF** |
| Service Connect | **On** → *Client and server* → namespace `ecom.local` → port `product-http` (or `cart-http`), **Discovery name & DNS** `product-service` (or `cart-service`), port 8081 (or 8082). For **order-service** choose *Client side only*. |
| Load balancing | Application Load Balancer → existing `ecom-alb` → container `<svc> <port>` → existing listener 443 → existing target group `ecom-<svc>-tg`, health-check grace period **60s** |
| Service auto scaling | **On** → min **3**, max **50** |
| Scaling policy 1 | Target tracking `ECSServiceAverageCPUUtilization` = **50%**, scale-out cooldown 60s, scale-in cooldown 300s |
| Scaling policy 2 | Target tracking `ALBRequestCountPerTarget` = ~**1000** (tune after load tests) |

Wait until every service shows 3/3 tasks running and the target groups show **healthy** targets.

---

## Phase 10: Website (CloudFront + S3 + WAF)

### 10.1 Upload the frontend
**S3 → `ecom-frontend-<ACCOUNT_ID>` → Upload** → `frontend/index.html`.

### 10.2 WAF web ACL
**WAF & Shield → Web ACLs → Create web ACL** → Resource type **Amazon CloudFront distributions** (Global) → name `ecom-waf`. Add rules:
1. AWS managed: **Core rule set**, **Known bad inputs**, **SQL database**, **Amazon IP reputation list**, **Anonymous IP list**
2. **Rate-based rule** `per-ip-limit`: 2000 requests per 5 minutes per IP → **Block** (start in **Count** mode, check the numbers, then switch to Block)
3. Optional: **Bot Control** (Common) to protect checkout from scalper bots (paid feature)
4. Default action: Allow. Logging: send to CloudWatch Logs log group `aws-waf-logs-ecom`.

### 10.3 CloudFront distribution
**CloudFront → Create distribution**:

**Origin 1 (S3, default)**
- Origin domain: `ecom-frontend-<ACCOUNT_ID>.s3.ap-south-1.amazonaws.com`
- Origin access: **Origin access control (OAC)** → create new → after creation, click **Copy policy** and paste it into the bucket's **Bucket policy**. If the bucket uses SSE-KMS, also add CloudFront to the `ecom-key` key policy (the console shows the statement).
- Default behavior: Redirect HTTP to HTTPS, GET/HEAD, cache policy **CachingOptimized**, Compress ✔

**Origin 2 (ALB)**: add after creation under **Origins → Create origin**
- Origin domain: `api-origin.example.com` (the DNS name that matches the ALB certificate, *not* the raw ALB hostname)
- Protocol **HTTPS only**, minimum origin SSL **TLSv1.2**
- **Add custom header**: `X-Origin-Verify` = `<value of ecom/origin-verify>`
- Enable **Origin Shield** in ap-south-1 (optional; extra protection for the origin)

**Behavior `/api/*`** (Behaviors → Create behavior)
- Origin: the ALB origin, viewer protocol: **HTTPS only**
- Allowed methods: **GET, HEAD, OPTIONS, PUT, POST, PATCH, DELETE**
- Cache policy **CachingDisabled**, origin request policy **AllViewerExceptHostHeader**
- (Tip: create a second behavior `/api/products*` with a 30–60 s cache policy. During the sale this absorbs most catalogue traffic at the edge.)

**Settings**
- Alternate domain name `shop.example.com`, the **us-east-1** ACM cert, security policy TLSv1.2_2021
- Default root object `index.html`
- **WAF**: attach `ecom-waf`
- Standard logging → `ecom-logs-<ACCOUNT_ID>`
- Price class: include the regions where your customers are

**Route 53**: create record `shop.example.com` → **A – Alias** → CloudFront distribution (add an AAAA alias too if you enable IPv6).

### 10.4 Shield
**Shield Standard** is on automatically. For a big public sale, consider **Shield Advanced**: DDoS cost protection, 24×7 Shield Response Team, and automatic L7 mitigation. Subscribe and protect the CloudFront distribution, the ALB and the Route 53 zone.

---

## Phase 11: Logging and monitoring

### 11.1 Logs
- App logs: `/ecs/<service>` (stdout → awslogs driver, already configured)
- Access logs: ALB and CloudFront → S3 `ecom-logs-...`
- WAF logs: `aws-waf-logs-ecom`
- Aurora: error and slow-query logs in CloudWatch
- Audit: CloudTrail

**CloudWatch → Logs Insights** queries to save:
```
fields @timestamp, @message | filter @message like /ERROR/ | sort @timestamp desc | limit 100
```
```
fields @message | filter @message like /Order .* placed/ | stats count() as orders by bin(1m)
```
```
fields @message | filter @message like /Cache miss/ | stats count() by bin(1m)
```

**Metric filter**: on `/ecs/order-service`, filter `"Order" "placed"` → metric `ecom/OrdersPlaced`. This gives you a business KPI on the dashboard.

### 11.2 Alarms (→ SNS topic `ecom-alerts`, email/SMS/PagerDuty)
Create **SNS → Topics → `ecom-alerts`** and subscribe the on-call team. Then **CloudWatch → Alarms → Create alarm**:

| Metric | Threshold |
|---|---|
| ALB `HTTPCode_Target_5XX_Count` | > 50 in 1 min |
| ALB `TargetResponseTime` p99 | > 1.5 s for 3 min |
| ALB `UnHealthyHostCount` (per TG) | ≥ 1 for 3 min |
| ECS service `CPUUtilization` | > 80% for 5 min (scaling isn't keeping up) |
| ECS `RunningTaskCount` | < 3 |
| Aurora `CPUUtilization` (writer) | > 75% |
| Aurora `DatabaseConnections` / RDS Proxy `ClientConnections` | > 80% of max |
| ElastiCache `EngineCPUUtilization` | > 70% |
| ElastiCache `Evictions` / `CurrConnections` | sudden spikes |
| DynamoDB `ThrottledRequests` | > 0 |
| CloudFront `5xxErrorRate` | > 1% |
| WAF `BlockedRequests` | anomaly band |
| `ecom/OrdersPlaced` | **drops below** normal (catches silent checkout failures) |

### 11.3 Dashboard
**CloudWatch → Dashboards → Create** `ecom-sale`: add widgets for CloudFront requests and error rate, ALB requests/latency/5xx, ECS CPU/memory/task count per service, Aurora CPU/connections, Redis hit rate (`CacheHits` vs `CacheMisses`), DynamoDB consumed capacity and throttles, `OrdersPlaced`. Also look at **Container Insights** (ECS → cluster → *Observability*) for per-task detail.

### 11.4 Synthetic monitoring
**CloudWatch → Synthetics → Create canary** → *Heartbeat monitoring* for `https://shop.example.com`, plus an *API canary* for `GET /api/products` every 1 minute. Alarm on failure.

### 11.5 Tracing (optional, recommended)
Add the **AWS Distro for OpenTelemetry** collector as a sidecar, or enable **CloudWatch Application Signals**, to get service maps and X-Ray traces across product → cart → order.

---

## Phase 12: Smoke test

```bash
curl -X POST https://shop.example.com/api/products -H "Content-Type: application/json" -d "{\"name\":\"Sale T-Shirt\",\"description\":\"Cotton\",\"price\":499.00,\"stock\":1000}"
```
```bash
curl https://shop.example.com/api/products
```
```bash
curl -X POST https://shop.example.com/api/cart/u-123/items -H "Content-Type: application/json" -d "{\"productId\":\"1\",\"quantity\":2,\"price\":499.00}"
```
```bash
curl -X POST https://shop.example.com/api/orders -H "Content-Type: application/json" -d "{\"userId\":\"u-123\",\"items\":[{\"productId\":1,\"quantity\":2}]}"
```
```bash
curl -F "file=@tshirt.jpg" https://shop.example.com/api/products/1/image
```
Then open `https://shop.example.com` in a browser. Verify that the second `GET /api/products` does **not** log "Cache miss" (it was served from Redis), and that hitting the ALB DNS name directly returns **403**.

> ⚠️ In this demo the write endpoints (`POST /api/products`, image upload) are open. Before going live, put authentication in front of them: **Amazon Cognito** user pools for customers, plus a separate admin path. Spring Security can validate the Cognito JWT, or use ALB's built-in Cognito authentication on an `/admin` listener rule.

---

## Phase 13: Sale-readiness checklist (2–4 weeks before)

1. **Load test** at 2–3× expected peak from outside AWS or with *Distributed Load Testing on AWS*. Find the first bottleneck (usually the DB) and fix it.
2. **Pre-scale the day before**: raise ECS service **minimum** tasks (e.g. 3 → 20), bump the Aurora instance class and add readers, set DynamoDB **warm throughput**, scale ElastiCache node size. Lower them again after the sale.
3. **Quotas** approved (Phase 1.6).
4. **Game day**: reboot the Aurora writer with failover, kill tasks in one AZ, and confirm the alarms fire and the site stays up.
5. **Freeze** deployments during the sale window. Keep the last-known-good image tag noted for rollback.
6. **Runbook + on-call** rota; open an **AWS Support case / IEM** describing the event dates and expected traffic.
7. Check the **Security Hub** score and fix every critical/high finding.
8. Back up: take a manual Aurora snapshot before the sale, and make sure PITR is enabled on DynamoDB.

## Next steps beyond this guide
- Infrastructure as Code (CloudFormation/CDK or Terraform) so prod and staging are identical and reproducible
- Async checkout: order-service publishes to **SQS/EventBridge**, so payment, inventory and email are processed by consumers and spikes queue up instead of failing
- Read traffic to the Aurora **reader endpoint** for reports and order history
- Multi-region disaster recovery (Aurora Global Database, DynamoDB global tables) if the business needs a regional RTO

## Cost note
The production-grade choices (3 NAT gateways, Aurora r7g with a replica, ElastiCache with 2 replicas, 9+ Fargate tasks, interface endpoints, WAF) cost real money even at idle: roughly several hundred to over a thousand USD per month before sale traffic. Check with the **AWS Pricing Calculator**. For a dev/test copy, use 1 NAT, Aurora Serverless v2, a single cache node and 1 task per service, and delete it when you're not using it.
