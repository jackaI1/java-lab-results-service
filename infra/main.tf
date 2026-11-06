terraform {
  required_version = ">= 1.9, < 2.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "= 5.100.0" }
  }
}
provider "aws" {
  region                      = var.region
  skip_credentials_validation = var.sandbox
  skip_metadata_api_check     = var.sandbox
  skip_requesting_account_id  = var.sandbox
  endpoints {
    ec2            = var.sandbox ? "http://localhost:5000" : null
    ecs            = var.sandbox ? "http://localhost:5000" : null
    rds            = var.sandbox ? "http://localhost:5000" : null
    elbv2          = var.sandbox ? "http://localhost:5000" : null
    iam            = var.sandbox ? "http://localhost:4566" : null
    secretsmanager = var.sandbox ? "http://localhost:4566" : null
    cloudwatchlogs = var.sandbox ? "http://localhost:4566" : null
    sts            = var.sandbox ? "http://localhost:4566" : null
  }
  default_tags { tags = { Project = "lab-results", Environment = var.sandbox ? "sandbox" : "aws" } }
}
variable "sandbox" { default = false }
variable "region" { default = "us-east-1" }
variable "image" { type = string }
variable "db_password" {
  type      = string
  sensitive = true
}
variable "reader_password" {
  type      = string
  sensitive = true
}
variable "technician_password" {
  type      = string
  sensitive = true
}
variable "certificate_arn" { default = "" }

resource "aws_vpc" "lab" {
  cidr_block           = "10.42.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true
}
resource "aws_subnet" "public" {
  count             = 2
  vpc_id            = aws_vpc.lab.id
  cidr_block        = "10.42.${count.index}.0/24"
  availability_zone = "${var.region}${count.index == 0 ? "a" : "b"}"
}
resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.lab.id
  cidr_block        = "10.42.${count.index + 10}.0/24"
  availability_zone = "${var.region}${count.index == 0 ? "a" : "b"}"
}
resource "aws_internet_gateway" "lab" { vpc_id = aws_vpc.lab.id }
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.lab.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.lab.id
  }
}
resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}
resource "aws_security_group" "alb" {
  name   = "lab-alb"
  vpc_id = aws_vpc.lab.id
  ingress {
    from_port   = var.sandbox ? 80 : 443
    to_port     = var.sandbox ? 80 : 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [aws_vpc.lab.cidr_block]
  }
}
resource "aws_security_group" "app" {
  name   = "lab-app"
  vpc_id = aws_vpc.lab.id
  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
resource "aws_security_group" "db" {
  name   = "lab-db"
  vpc_id = aws_vpc.lab.id
  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }
}
resource "aws_db_subnet_group" "lab" { subnet_ids = aws_subnet.private[*].id }
resource "aws_db_instance" "lab" {
  identifier                = "lab-results"
  engine                    = "postgres"
  engine_version            = "17"
  instance_class            = "db.t3.micro"
  allocated_storage         = 20
  storage_encrypted         = true
  db_name                   = "labresults"
  username                  = "lab"
  password                  = var.db_password
  db_subnet_group_name      = aws_db_subnet_group.lab.name
  vpc_security_group_ids    = [aws_security_group.db.id]
  publicly_accessible       = false
  backup_retention_period   = 7
  deletion_protection       = !var.sandbox
  skip_final_snapshot       = var.sandbox
  final_snapshot_identifier = "lab-results-final"
}
resource "aws_secretsmanager_secret" "lab" {
  name                    = "lab-results-credentials"
  recovery_window_in_days = var.sandbox ? 0 : 7
}
resource "aws_secretsmanager_secret_version" "lab" {
  secret_id = aws_secretsmanager_secret.lab.id
  secret_string = jsonencode({
    DB_PASSWORD         = var.db_password, READER_PASSWORD = var.reader_password,
    TECHNICIAN_PASSWORD = var.technician_password
  })
}
resource "aws_cloudwatch_log_group" "lab" {
  name              = "/ecs/lab-results"
  retention_in_days = 14
}
resource "aws_iam_role" "execution" {
  name = "lab-results-execution"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" }, Action = "sts:AssumeRole"
  }] })
}
resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}
resource "aws_iam_role_policy" "secrets" {
  role = aws_iam_role.execution.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = aws_secretsmanager_secret.lab.arn
  }] })
}
resource "aws_ecs_cluster" "lab" { name = "lab-results" }
resource "aws_ecs_task_definition" "lab" {
  family                   = "lab-results"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  container_definitions = jsonencode([{
    name         = "app", image = var.image, essential = true,
    portMappings = [{ containerPort = 8080, protocol = "tcp" }],
    environment = [
      { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.lab.address}:5432/labresults${var.sandbox ? "" : "?sslmode=require"}" },
      { name = "DB_USER", value = aws_db_instance.lab.username }
    ],
    secrets = [for key in ["DB_PASSWORD", "READER_PASSWORD", "TECHNICIAN_PASSWORD"] : {
      name = key, valueFrom = "${aws_secretsmanager_secret.lab.arn}:${key}::"
    }],
    healthCheck = { command = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:8080/health || exit 1"], interval = 15, timeout = 5, retries = 3, startPeriod = 60 },
    logConfiguration = { logDriver = "awslogs", options = {
      "awslogs-group" = aws_cloudwatch_log_group.lab.name, "awslogs-region" = var.region, "awslogs-stream-prefix" = "app"
    } }
  }])
  depends_on = [aws_secretsmanager_secret_version.lab]
}
resource "aws_lb" "lab" {
  name               = "lab-results"
  load_balancer_type = "application"
  subnets            = aws_subnet.public[*].id
  security_groups    = [aws_security_group.alb.id]
}
resource "aws_lb_target_group" "lab" {
  name        = "lab-results"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.lab.id
  health_check {
    path                = "/health"
    matcher             = "200"
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}
resource "aws_lb_listener" "lab" {
  load_balancer_arn = aws_lb.lab.arn
  port              = var.sandbox ? 80 : 443
  protocol          = var.sandbox ? "HTTP" : "HTTPS"
  certificate_arn   = var.sandbox ? null : var.certificate_arn
  ssl_policy        = var.sandbox ? null : "ELBSecurityPolicy-TLS13-1-2-2021-06"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.lab.arn
  }
  lifecycle {
    precondition {
      condition     = var.sandbox || var.certificate_arn != ""
      error_message = "A regional ACM certificate ARN is required for AWS HTTPS."
    }
  }
}
resource "aws_ecs_service" "lab" {
  name                              = "lab-results"
  cluster                           = aws_ecs_cluster.lab.id
  task_definition                   = aws_ecs_task_definition.lab.arn
  desired_count                     = 1
  launch_type                       = "FARGATE"
  platform_version                  = "1.4.0"
  health_check_grace_period_seconds = 90
  wait_for_steady_state             = !var.sandbox
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.lab.arn
    container_name   = "app"
    container_port   = 8080
  }
  depends_on = [aws_lb_listener.lab, aws_iam_role_policy.secrets, aws_iam_role_policy_attachment.execution, aws_route_table_association.public]
}
output "url" { value = "${var.sandbox ? "http" : "https"}://${aws_lb.lab.dns_name}" }
output "task_definition" { value = aws_ecs_task_definition.lab.arn }
output "secret_arn" { value = aws_secretsmanager_secret.lab.arn }
