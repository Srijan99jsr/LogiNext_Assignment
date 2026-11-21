# LogiNext_Assignment

Highly available, scalable e-commerce backend on AWS: three Spring Boot microservices with SQL, NoSQL, caching, file storage, logging and monitoring.

| Folder | What it is |
|---|---|
| `product-service/` | Spring Boot 3.3 / Java 21. Catalogue on Aurora MySQL, Redis (ElastiCache) cache, product images on S3 |
| `cart-service/` | Spring Boot. Shopping cart on DynamoDB (with TTL for abandoned carts) |
| `order-service/` | Spring Boot. Orders on Aurora MySQL; re-reads prices from product-service and clears the cart via ECS Service Connect |
| `frontend/index.html` | Static storefront served from S3 through CloudFront |
| [`AWS-CONSOLE-GUIDE.md`](AWS-CONSOLE-GUIDE.md) | Step-by-step console guide: account → network → security → data → ECS → CloudFront/WAF → monitoring |

## Architecture

Users → Route 53 → AWS WAF + CloudFront → S3 (website) and, for `/api/*`, an Application Load Balancer → ECS Fargate services in private subnets across 3 AZs → Aurora MySQL (via RDS Proxy), DynamoDB, ElastiCache and S3. CloudWatch, CloudTrail, GuardDuty and Security Hub cover logging, monitoring and security. The full diagram is in the guide.

## Build

Each service has a multi-stage `Dockerfile` (the Maven build runs inside Docker), so you only need Docker:

```bash
docker build --platform linux/amd64 -t product-service:1.0.0 product-service
```

All configuration comes from environment variables (see the guide, Phase 9.3). AWS credentials come from the ECS task IAM role, never from keys.

## Run locally (product + order)

```bash
docker network create ecom-test
```
```bash
docker run -d --name ecom-mysql --network ecom-test -e MYSQL_ROOT_PASSWORD=testpass mysql:8.4
```
```bash
docker run -d --name ecom-redis --network ecom-test redis:7-alpine
```
```bash
docker run -d --name product-service --network ecom-test -p 8081:8081 -e DB_HOST=ecom-mysql -e DB_USERNAME=root -e DB_PASSWORD=testpass -e REDIS_HOST=ecom-redis -e REDIS_SSL=false -e S3_BUCKET=dummy -e AWS_REGION=ap-south-1 product-service:1.0.0
```
```bash
docker run -d --name order-service --network ecom-test -p 8083:8083 -e DB_HOST=ecom-mysql -e DB_USERNAME=root -e DB_PASSWORD=testpass -e PRODUCT_SERVICE_URL=http://product-service:8081 order-service:1.0.0
```

cart-service and the S3 image endpoints need a real AWS account (DynamoDB and S3).
