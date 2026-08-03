# Provider and Terraform version constraints.
#
# State is deliberately LOCAL for now. See ADR-012: there is exactly one
# operator applying from one laptop, CI does not run Terraform yet, and this
# state file holds no high-value secret (the database is not reachable from
# outside the Compose network). Remote state in S3 becomes worth its bootstrap
# cost the moment a second operator or a CI job needs to apply — at which point
# `terraform init -migrate-state` moves it without recreating anything.

terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "aws" {
  region = var.aws_region

  # Left null so that AWS_PROFILE / AWS_REGION from the environment win when
  # the variable is unset. Never hardcode an account-specific profile name.
  profile = var.aws_profile

  # Applied to every taggable resource. If the state file is ever lost, these
  # tags are how you find and destroy what was created.
  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "Terraform"
      Repository  = "maglez/eop-threat-medeling"
    }
  }
}
