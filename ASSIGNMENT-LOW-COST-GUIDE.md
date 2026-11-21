# Low-Cost Assignment Deployment (build → demo → delete)

This is the same architecture as [AWS-CONSOLE-GUIDE.md](AWS-CONSOLE-GUIDE.md), sized for an **assignment review**: every component exists and works, but at its smallest size, with no redundancy you'd pay for. Follow this file. Use the main guide only when you want more detail on a console screen.

**Expected cost:** roughly **USD 3–5 per day** while everything runs (ap-south-1, approximate). New AWS accounts get free-tier credits, which will likely cover the whole assignment: check **Billing → Free Tier / Credits**. If the console says a service isn't available on the Free plan, upgrade the account to the Paid plan; your credits still apply.

> Build it, get it reviewed, then run the **Teardown** section the same day. Most of the cost is per hour, not per request.

---

## What changes vs. the production guide

| Component | Production guide | **Assignment version** | Why |
|---|---|---|---|
| Availability Zones | 3 | **2** (ALB minimum) | Fewer subnets and IPs |
| NAT gateways | 3 | **None**: tasks run in public subnets with a public IP; the security group only allows the ALB | NAT costs ~$1+/day each |
| Interface VPC endpoints | 4 | **None** (free S3 and DynamoDB *gateway* endpoints only) | ~$0.25/day each per AZ |
| SQL database | Aurora r7g + replica + RDS Proxy | **RDS MySQL `db.t4g.micro`, Single-AZ, 20 GB**, no proxy | Free-tier class |
| Cache | ElastiCache r7g, 2 replicas | **Valkey `cache.t4g.micro`, 0 replicas** | Smallest node |
| DynamoDB | On-demand + warm throughput | **On-demand** (costs cents) | Pay per request |
| ECS tasks | 1 vCPU / 2 GB, min 3, max 50 | **0.5 vCPU / 1 GB, 1 task each, max 2**, on **Fargate Spot** | ~70% cheaper |
| Domain / certificates | Route 53 domain + 2 ACM certs | **None**: use the free `xxxx.cloudfront.net` HTTPS address | No domain purchase |
| CloudFront → ALB | HTTPS | **HTTP** (users still get HTTPS to CloudFront) | No certificate needed |
| KMS | Customer-managed key | **AWS-managed keys** (free) | |
| WAF | 5 managed groups + Bot Control | **Optional**: Core rule set + rate limit only | ~$7/month, prorated |
| Shield Advanced | Suggested | **No** (Shield Standard is free and automatic) | $3,000/month |
| Monitoring | Container Insights enhanced, many alarms, Synthetics | **Default ECS metrics + 3 alarms + 1 dashboard** (all within the free tier) | |
| Log retention | 30 days | **1 day** | |
| Security services | GuardDuty, Security Hub, Config, Inspector | **CloudTrail + GuardDuty (30-day free trial)** | |

Security posture is still correct for a review:
- Databases and cache are in private subnets, encrypted at rest.
- Passwords live in Secrets Manager.
- Each service has its own IAM role, and there are no access keys.
- The ALB only accepts CloudFront traffic that carries a secret header.
- Users always connect over HTTPS.

---

## Step 1: Account (about 20 min)
1. Create the AWS account. Turn on **MFA on root**, then create an admin user in **IAM Identity Center** and use that from now on (main guide 1.2–1.3).
2. **Billing → Budgets → Create budget → "Zero spend"** or a monthly cost budget of **$20** with email alerts. This is your safety net if you forget something.
3. **CloudTrail**: the default event history is free. Optionally create one trail (the first management-events trail is free; S3 storage costs cents).
4. **GuardDuty → Enable** (30-day free trial). Remember to suspend it at teardown.
5. Region: **ap-south-1 (Mumbai)** for everything below.

No quota increases are needed at this size.

## Step 2: Network (about 15 min)
**VPC → Create VPC → "VPC and more"**:

| Setting | Value |
|---|---|
| Name | `ecom` |
| IPv4 CIDR | `10.0.0.0/16` |
| AZs | **2** |
| Public subnets | **2** |
| Private subnets | **2** |
| NAT gateways | **None** |
| VPC endpoints | **S3 Gateway** (free) |

Then **VPC → Endpoints → Create endpoint** → `com.amazonaws.ap-south-1.dynamodb`, type **Gateway** → tick all route tables (free).

**Security groups** (VPC → Security groups):

| Name | Inbound | Purpose |
|---|---|---|
| `ecom-alb-sg` | HTTP 80 from prefix list `com.amazonaws.global.cloudfront.origin-facing` | Only CloudFront can reach the ALB |
| `ecom-app-sg` | TCP 8081-8083 from `ecom-alb-sg`; TCP 8081-8083 from `ecom-app-sg` (itself) | Tasks: no direct internet access in |
| `ecom-db-sg` | MySQL 3306 from `ecom-app-sg` | RDS |
| `ecom-redis-sg` | TCP 6379 from `ecom-app-sg` | ElastiCache |

## Step 3: Data stores (about 30 min, mostly waiting)

**RDS MySQL**
1. **RDS → Subnet groups → Create** `ecom-db-subnets` with the **2 private subnets**.
2. **RDS → Create database** → Standard create → **MySQL 8.0/8.4** → Template **Free tier** (or Sandbox):
   - Identifier `ecom-mysql`, master username `admin`, **Credentials: Managed in AWS Secrets Manager**
   - Instance `db.t4g.micro`, storage **20 GB gp3**, storage autoscaling **off**
   - Single-AZ, VPC `ecom-vpc`, subnet group `ecom-db-subnets`, **Public access: No**, SG `ecom-db-sg` only
   - Encryption on (default AWS-managed key), backups 1 day, **Deletion protection off** (so teardown is easy)
3. When it's *Available*, copy the **endpoint**. From **Secrets Manager**, copy the ARN of the `rds!db-...` secret.

> The RDS secret rotates every 7 days. If your review takes longer than that, open the secret and **disable rotation**. Otherwise, run *Force new deployment* on the services after a rotation.

**DynamoDB**: create table `ecom-carts`, partition key `userId` (String), sort key `productId` (String), capacity **On-demand**. Then **Additional settings → TTL** on attribute `expiresAt`.

**ElastiCache (Valkey)**
1. **ElastiCache → Subnet groups → Create** `ecom-cache-subnets` with the **2 private subnets**.
2. **Valkey caches → Create → Design your own cache → Cluster cache**:
   - Cluster mode **disabled**, name `ecom-cache`, node type **`cache.t4g.micro`**, **replicas 0**, Multi-AZ off
   - Subnet group `ecom-cache-subnets`, SG `ecom-redis-sg`
   - Encryption at rest **on**, in transit **on**, access control **none** (the security group restricts access), automatic backups **off**
3. Copy the **Primary endpoint** (without `:6379`).

**S3**: create two buckets with all public access blocked and default encryption (SSE-S3):
- `ecom-frontend-<ACCOUNT_ID>`
- `ecom-product-images-<ACCOUNT_ID>`

**Secret for the origin header**: **Secrets Manager → Store a new secret → Other type**, key `value` = any long random string, name `ecom/origin-verify`. You only paste this value into CloudFront and the ALB, so you can also just keep it in a note and skip the secret.

## Step 4: IAM roles (about 10 min)
Create these exactly as in **main guide Phase 6**, with these differences:
- `ecomTaskExecutionRole`: `AmazonECSTaskExecutionRolePolicy` plus inline `secretsmanager:GetSecretValue` on the `rds!db-...` secret ARN. No KMS statement is needed with AWS-managed keys.
- `ecom-product-task-role`: `s3:PutObject` and `s3:GetObject` on `arn:aws:s3:::ecom-product-images-<ACCOUNT_ID>/*`
- `ecom-cart-task-role`: `dynamodb:GetItem`, `PutItem`, `DeleteItem`, `Query` and `UpdateItem` on the `ecom-carts` table ARN
- `ecom-order-task-role`: no policies

## Step 5: Push images to ECR (about 15 min)
**ECR → Create repository** three times: `product-service`, `cart-service`, `order-service` (basic scan on push; defaults are fine). Then on your laptop, with Docker running and the AWS CLI signed in, run these, using your account ID:

```bash
aws ecr get-login-password --region ap-south-1 | docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.ap-south-1.amazonaws.com
```
```bash
docker build --platform linux/amd64 -t <ACCOUNT_ID>.dkr.ecr.ap-south-1.amazonaws.com/product-service:1.0.0 product-service && docker push <ACCOUNT_ID>.dkr.ecr.ap-south-1.amazonaws.com/product-service:1.0.0
```
```bash
docker build --platform linux/amd64 -t <ACCOUNT_ID>.dkr.ecr.ap-south-1.amazonaws.com/cart-service:1.0.0 cart-service && docker push <ACCOUNT_ID>.dkr.ecr.ap-south-1.amazonaws.com/cart-service:1.0.0
```
```bash
docker build --platform linux/amd64 -t <ACCOUNT_ID>.dkr.ecr.ap-south-1.amazonaws.com/order-service:1.0.0 order-service && docker push <ACCOUNT_ID>.dkr.ecr.ap-south-1.amazonaws.com/order-service:1.0.0
```

## Step 6: Load balancer (about 15 min)
1. **EC2 → Target groups → Create**, three times: type **IP**, HTTP, port 8081 / 8082 / 8083, names `ecom-product-tg` / `ecom-cart-tg` / `ecom-order-tg`, VPC `ecom-vpc`, health check path **`/actuator/health/liveness`**. Don't register targets.
2. **EC2 → Load balancers → Create ALB** `ecom-alb`: internet-facing, the **2 public subnets**, SG `ecom-alb-sg`. Listener **HTTP:80**, default action **fixed response 403**.
3. **Listener rules** (HTTP:80 → Add rule), each with **two conditions**: the path **and** HTTP header `X-Origin-Verify` = your secret value.
   - Priority 10: `/api/products*` → `ecom-product-tg`
   - Priority 20: `/api/cart*` → `ecom-cart-tg`
   - Priority 30: `/api/orders*` → `ecom-order-tg`

## Step 7: ECS (about 30 min)
1. **ECS → Create cluster** `ecom-cluster`, **AWS Fargate**. Leave Container Insights **off**. Set the Service Connect default namespace to `ecom.local`.
2. **CloudWatch → Log groups**: create `/ecs/product-service`, `/ecs/cart-service`, `/ecs/order-service` with retention **1 day**.
3. **Task definitions**: same as **main guide 9.3**, but with **CPU 0.5 vCPU, memory 1 GB**, and these environment variables:
   - **product**: `DB_HOST` = RDS endpoint; `DB_USERNAME` / `DB_PASSWORD` as **ValueFrom** `<rds secret ARN>:username::` and `:password::`; `REDIS_HOST` = cache endpoint; `REDIS_SSL` = `true`; `S3_BUCKET` = `ecom-product-images-<ACCOUNT_ID>`; `AWS_REGION` = `ap-south-1`; `DB_POOL_SIZE` = `5`
   - **cart**: `CART_TABLE` = `ecom-carts`, `AWS_REGION` = `ap-south-1`
   - **order**: `DB_HOST`, `DB_USERNAME`, `DB_PASSWORD` (same as product); `PRODUCT_SERVICE_URL` = `http://product-service:8081`; `CART_SERVICE_URL` = `http://cart-service:8082`; `DB_POOL_SIZE` = `5`
4. **Services**: create product, then cart, then order. Each one:
   - **Capacity provider strategy → FARGATE_SPOT**, weight 1. (On review day you can switch to FARGATE so a Spot interruption can't happen mid-demo.)
   - **Desired tasks 1**
   - Deployment circuit breaker with rollback **on**
   - Networking: the **2 public subnets**, SG `ecom-app-sg`, **Public IP ON** (needed to pull images and reach AWS APIs, because there's no NAT)
   - **Service Connect**: product and cart as *Client and server*, with discovery/DNS names `product-service:8081` and `cart-service:8082`; order as *Client side only*
   - Load balancer: `ecom-alb`, the existing HTTP:80 listener and the matching target group, health-check grace period 90s
   - **Auto scaling: min 1, max 2**, target tracking on CPU 70%. This demonstrates scaling without running extra tasks.

Wait until each target group shows **1 healthy** target.

## Step 8: Website (about 15 min)
1. Upload `frontend/index.html` to `ecom-frontend-<ACCOUNT_ID>`.
2. **CloudFront → Create distribution**:
   - Origin: the frontend bucket with **Origin access control** (create new), then paste the generated policy into the bucket policy.
   - Default behavior: Redirect HTTP→HTTPS, cache policy **CachingOptimized**.
   - Default root object `index.html`. Price class **Use only North America and Europe** or **…and Asia**, the cheapest that covers your reviewers.
   - WAF: **Do not enable security protections** (or enable it if you want to show WAF; it costs about $7/month, prorated).
3. After it's created, **Origins → Create origin**: domain = the ALB DNS name, protocol **HTTP only**, port 80, custom header `X-Origin-Verify` = your secret value.
4. **Behaviors → Create behavior** `/api/*` → the ALB origin, viewer **Redirect HTTP to HTTPS**, all HTTP methods, cache policy **CachingDisabled**, origin request policy **AllViewer**.
5. Wait for *Deployed* (about 5 min). Your site is `https://dxxxxxxxx.cloudfront.net`.

## Step 9: Minimal monitoring (about 10 min, free tier)
- **SNS** topic `ecom-alerts` with your email subscribed.
- **3 CloudWatch alarms** → `ecom-alerts`:
  - ALB `HTTPCode_Target_5XX_Count` > 10 in 5 min
  - ALB `UnHealthyHostCount` ≥ 1 (any target group)
  - RDS `CPUUtilization` > 80%
- **1 dashboard** `ecom-demo` with these widgets:
  - ALB requests and 5xx
  - ECS CPU and memory per service
  - RDS CPU and connections
  - ElastiCache `CacheHits`/`CacheMisses`
  - DynamoDB consumed capacity
- **Logs Insights** on the three log groups. For the demo, show `Cache miss` disappearing on the second request.

## Step 10: Demo for the reviewer
Run these, replacing `<CF>` with your CloudFront domain:
```bash
curl -X POST https://<CF>/api/products -H "Content-Type: application/json" -d "{\"name\":\"Sale T-Shirt\",\"description\":\"Cotton\",\"price\":499.00,\"stock\":1000}"
```
```bash
curl https://<CF>/api/products
```
```bash
curl -X POST https://<CF>/api/orders -H "Content-Type: application/json" -d "{\"userId\":\"u-123\",\"items\":[{\"productId\":1,\"quantity\":2}]}"
```
```bash
curl -F "file=@tshirt.jpg" https://<CF>/api/products/1/image
```
Then open `https://<CF>/`: add to cart (DynamoDB), place an order (RDS), and look at the cache hits and logs in CloudWatch. Calling the ALB DNS name directly returns **403**, which shows the origin is protected.

Things worth pointing out to the reviewer, and what you'd change at sale scale (the production guide):
- Multi-AZ Aurora with RDS Proxy
- 3 AZs with NAT gateways and private tasks
- Larger tasks with min 3 / max 50 auto scaling
- WAF + Shield Advanced
- Custom domain with end-to-end TLS

---

## Teardown (do this right after the review)

Delete in this order. Later items depend on earlier ones being gone.

1. **ECS**: for each service, *Update* to desired **0**, then **Delete service**. Then delete the cluster `ecom-cluster` and deregister the three task definitions.
2. **CloudFront**: **Disable** the distribution, wait for *Deployed*, then **Delete**. If you created a WAF web ACL, delete it after the distribution is gone.
3. **EC2**: delete the load balancer `ecom-alb`, then the three target groups.
4. **ElastiCache**: delete `ecom-cache` (*Create final backup: No*), then the subnet group.
5. **RDS**: delete `ecom-mysql`. Untick *Create final snapshot* and tick *I acknowledge*. Then delete the subnet group and any manual snapshots.
6. **DynamoDB**: delete table `ecom-carts`.
7. **S3**: **Empty** each bucket, then **Delete** it (frontend, product images, and the CloudTrail bucket if you created one).
8. **ECR**: delete the three repositories (this deletes the images too).
9. **Secrets Manager**: delete `ecom/origin-verify`. The `rds!db-...` secret is removed with the database.
10. **CloudWatch**: delete the log groups, alarms and dashboard. Also delete the SNS topic.
11. **Cloud Map**: delete namespace `ecom.local` if it's still there after the cluster is deleted.
12. **VPC**: delete VPC `ecom-vpc`. This removes the subnets, route tables, gateway endpoints, internet gateway and security groups. If it complains, a network interface is still attached; wait a few minutes for the ECS/ALB ENIs to disappear.
13. **IAM**: delete the four `ecom*` roles.
14. **GuardDuty → Settings → Suspend/Disable**. Delete the CloudTrail trail if you created one.
15. Check **Billing → Bills** (current month, by service) the next day, and **Resource Explorer / Tag Editor** for anything left over in ap-south-1. Look especially for **Elastic IPs** and **EBS snapshots**.
