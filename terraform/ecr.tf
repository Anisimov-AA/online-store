# One image repository per program. The bad Product uses the product image with FAILURE_RATE=0.5.
locals {
  images = ["product", "credit-card", "shopping-cart", "warehouse", "load-client"]
}

resource "aws_ecr_repository" "app" {
  for_each             = toset(local.images)
  name                 = "${var.project}/${each.key}"
  image_tag_mutability = "MUTABLE"
  force_delete         = true # allow terraform destroy even when images are inside
}
