# The load tester runs inside AWS as a one-off ECS task (no service): see run-load-test.sh.
resource "aws_ecs_task_definition" "load_client" {
  family                   = "${var.project}-load-client"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = 2048
  memory                   = 4096
  execution_role_arn       = data.aws_iam_role.lab_role.arn
  task_role_arn            = data.aws_iam_role.lab_role.arn

  container_definitions = jsonencode([{
    name             = "load-client"
    image            = "${aws_ecr_repository.app["load-client"].repository_url}:latest"
    logConfiguration = { logDriver = "awslogs", options = local.log_options }
  }])
}

output "load_client_network" {
  description = "Network settings for aws ecs run-task"
  value       = "awsvpcConfiguration={subnets=[${join(",", data.aws_subnets.default.ids)}],securityGroups=[${aws_security_group.services.id}],assignPublicIp=ENABLED}"
}
