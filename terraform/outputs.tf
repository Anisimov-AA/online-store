output "alb_url" {
  description = "The one address the client talks to"
  value       = "http://${aws_lb.main.dns_name}"
}

output "ecr_repositories" {
  description = "Where push-images.sh pushes each image"
  value       = { for name, repo in aws_ecr_repository.app : name => repo.repository_url }
}

output "cluster_name" {
  value = aws_ecs_cluster.main.name
}
