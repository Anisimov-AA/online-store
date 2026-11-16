locals {
  # Services behind the load balancer. Both Product variants register in the same "product" target group.
  http_services = {
    product = {
      image = "product", port = 8080, target_group = "product", count = var.good_product_count
      cpu   = 512, memory = 1024
      env   = { FAILURE_RATE = "0" }
    }
    product-bad = {
      image = "product", port = 8080, target_group = "product", count = var.bad_product_count
      cpu   = 512, memory = 1024
      env   = { FAILURE_RATE = "0.5" }
    }
    credit-card = {
      image = "credit-card", port = 8082, target_group = "credit-card", count = var.credit_card_count
      cpu   = 512, memory = 1024
      env   = {}
    }
    shopping-cart = {
      image = "shopping-cart", port = 8081, target_group = "shopping-cart", count = var.shopping_cart_count
      cpu   = 1024, memory = 2048
      env = {
        CCA_URL           = "http://${aws_lb.main.dns_name}" # the cart calls the CCA through the load balancer too
        RABBITMQ_HOST     = local.rabbitmq_host
        CHANNEL_POOL_SIZE = tostring(var.cart_channel_pool_size)
      }
    }
  }

  log_options = {
    "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
    "awslogs-region"        = var.aws_region
    "awslogs-stream-prefix" = "ecs"
  }
}

# ---------- HTTP services ----------

resource "aws_ecs_task_definition" "http" {
  for_each                 = local.http_services
  family                   = "${var.project}-${each.key}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = data.aws_iam_role.lab_role.arn
  task_role_arn            = data.aws_iam_role.lab_role.arn

  container_definitions = jsonencode([{
    name             = each.key
    image            = "${aws_ecr_repository.app[each.value.image].repository_url}:latest"
    portMappings     = [{ containerPort = each.value.port, protocol = "tcp" }]
    environment      = [for k, v in each.value.env : { name = k, value = v }]
    logConfiguration = { logDriver = "awslogs", options = local.log_options }
  }])
}

resource "aws_ecs_service" "http" {
  for_each                          = local.http_services
  name                              = each.key
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.http[each.key].arn
  desired_count                     = each.value.count
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 60

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.services.id]
    assign_public_ip = true # needed to pull images from ECR in the default VPC
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app[each.value.target_group].arn
    container_name   = each.key
    container_port   = each.value.port
  }

  depends_on = [aws_lb_listener.http]
}

# ---------- RabbitMQ ----------

# Private DNS so the cart and the warehouse can find the broker as rabbitmq.store.local
resource "aws_service_discovery_private_dns_namespace" "internal" {
  name = "store.local"
  vpc  = data.aws_vpc.default.id
}

resource "aws_service_discovery_service" "rabbitmq" {
  name = "rabbitmq"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.internal.id
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}

locals {
  rabbitmq_host = "rabbitmq.${aws_service_discovery_private_dns_namespace.internal.name}"
}

resource "aws_ecs_task_definition" "rabbitmq" {
  family                   = "${var.project}-rabbitmq"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = data.aws_iam_role.lab_role.arn
  task_role_arn            = data.aws_iam_role.lab_role.arn

  container_definitions = jsonencode([{
    name  = "rabbitmq"
    image = "rabbitmq:4-management" # official image from Docker Hub, nothing to build
    portMappings = [
      { containerPort = 5672, protocol = "tcp" },  # messages
      { containerPort = 15672, protocol = "tcp" }, # web console
    ]
    logConfiguration = { logDriver = "awslogs", options = local.log_options }
  }])
}

resource "aws_ecs_service" "rabbitmq" {
  name            = "rabbitmq"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.rabbitmq.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.services.id]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.rabbitmq.arn
  }
}

# ---------- Warehouse ----------

resource "aws_ecs_task_definition" "warehouse" {
  family                   = "${var.project}-warehouse"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = data.aws_iam_role.lab_role.arn
  task_role_arn            = data.aws_iam_role.lab_role.arn

  container_definitions = jsonencode([{
    name  = "warehouse"
    image = "${aws_ecr_repository.app["warehouse"].repository_url}:latest"
    environment = [
      { name = "RABBITMQ_HOST", value = local.rabbitmq_host },
      { name = "WAREHOUSE_THREADS", value = tostring(var.warehouse_threads) },
    ]
    stopTimeout      = 30 # time for the shutdown hook to print the totals
    logConfiguration = { logDriver = "awslogs", options = local.log_options }
  }])
}

resource "aws_ecs_service" "warehouse" {
  name            = "warehouse"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.warehouse.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.services.id]
    assign_public_ip = true
  }

  depends_on = [aws_ecs_service.rabbitmq]
}
