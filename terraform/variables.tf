variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-west-2"
}

variable "project" {
  description = "Prefix for every resource name"
  type        = string
  default     = "online-store"
}

variable "good_product_count" {
  description = "Number of normal Product tasks"
  type        = number
  default     = 2
}

variable "bad_product_count" {
  description = "Number of Product tasks that fail 50% of requests"
  type        = number
  default     = 1
}

variable "shopping_cart_count" {
  description = "Number of Shopping Cart tasks"
  type        = number
  default     = 1
}

variable "credit_card_count" {
  description = "Number of Credit Card Authorizer tasks"
  type        = number
  default     = 1
}

variable "warehouse_threads" {
  description = "Consumer threads in the Warehouse"
  type        = number
  default     = 16
}

variable "cart_channel_pool_size" {
  description = "RabbitMQ channels pooled in each Shopping Cart task"
  type        = number
  default     = 32
}
