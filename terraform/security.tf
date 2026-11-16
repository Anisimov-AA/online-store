# The load balancer is open to the internet on port 80.
resource "aws_security_group" "alb" {
  name   = "${var.project}-alb-sg"
  vpc_id = data.aws_vpc.default.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# The services: reachable from inside the VPC (ALB, each other),
# plus the RabbitMQ web console from outside for the queue screenshots.
resource "aws_security_group" "services" {
  name   = "${var.project}-services-sg"
  vpc_id = data.aws_vpc.default.id

  ingress {
    description = "all traffic inside the VPC"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [data.aws_vpc.default.cidr_block]
  }

  ingress {
    description = "RabbitMQ management console"
    from_port   = 15672
    to_port     = 15672
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
