# One Application Load Balancer in front of Product, Shopping Cart and Credit Card Authorizer.
# The Warehouse is not here: it only talks to RabbitMQ.
resource "aws_lb" "main" {
  name               = "${var.project}-alb"
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = data.aws_subnets.default.ids
}

locals {
  # target group → container port and the URL paths routed to it
  routes = {
    product       = { port = 8080, paths = ["/product*"], priority = 10 }
    shopping-cart = { port = 8081, paths = ["/shopping-cart*"], priority = 20 }
    credit-card   = { port = 8082, paths = ["/credit-card*"], priority = 30 }
  }
}

resource "aws_lb_target_group" "app" {
  for_each    = local.routes
  name        = "${var.project}-${each.key}"
  port        = each.value.port
  protocol    = "HTTP"
  target_type = "ip" # Fargate tasks register by IP
  vpc_id      = data.aws_vpc.default.id

  # Automatic Target Weights: send less traffic to targets that return many 5xx errors
  load_balancing_algorithm_type     = "weighted_random"
  load_balancing_anomaly_mitigation = "on"

  # /health always answers 200, so the bad Product stays in service and ATW has something to act on
  health_check {
    path                = "/health"
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 10
  }

  deregistration_delay = 10
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  # any path that matches no rule
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "application/json"
      message_body = "{\"error\":\"NOT_FOUND\",\"message\":\"no service for this path\"}"
      status_code  = "404"
    }
  }
}

# Path-based routing: one listener rule per target group
resource "aws_lb_listener_rule" "route" {
  for_each     = local.routes
  listener_arn = aws_lb_listener.http.arn
  priority     = each.value.priority

  condition {
    path_pattern {
      values = each.value.paths
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app[each.key].arn
  }
}
