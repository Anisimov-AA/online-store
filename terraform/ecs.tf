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

# Learner Lab does not allow Cloud Map (private DNS), so the broker gets a fixed address
# from an internal Network Load Balancer. The cart and the warehouse connect to its DNS name.
resource "aws_lb" "rabbitmq" {
  name               = "${var.project}-mq"
  load_balancer_type = "network"
  internal           = true
  subnets            = data.aws_subnets.default.ids
}

resource "aws_lb_target_group" "rabbitmq" {
  name        = "${var.project}-mq"
  port        = 5672
  protocol    = "TCP"
  target_type = "ip"
  vpc_id      = data.aws_vpc.default.id

  deregistration_delay = 10
}

resource "aws_lb_listener" "rabbitmq" {
  load_balancer_arn = aws_lb.rabbitmq.arn
  port              = 5672
  protocol          = "TCP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.rabbitmq.arn
  }
}

locals {
  rabbitmq_host = aws_lb.rabbitmq.dns_name
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

  load_balancer {
    target_group_arn = aws_lb_target_group.rabbitmq.arn
    container_name   = "rabbitmq"
    container_port   = 5672
  }

  depends_on = [aws_lb_listener.rabbitmq]
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
