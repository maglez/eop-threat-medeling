# All account-specific and environment-specific values are variables.
# Nothing here names an account, an ARN, a CIDR or an AMI id, so the same
# configuration applies unchanged to a different AWS account.

variable "aws_region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "eu-west-2"
}

variable "aws_profile" {
  description = <<-EOT
    Named profile from ~/.aws/credentials to authenticate with. Leave null to
    fall back to the AWS_PROFILE environment variable or the default chain.
    Never put deploy credentials in the repository .env file — the Bedrock
    bearer token that powers the agent session lives there and the SDK would
    start preferring SigV4 over it.
  EOT
  type        = string
  default     = null
}

variable "project_name" {
  description = "Short project identifier used as a name prefix and tag value."
  type        = string
  default     = "eop"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.project_name))
    error_message = "project_name must be lowercase alphanumeric with hyphens, 2-21 characters."
  }
}

variable "environment" {
  description = "Environment name. This proof of concept has exactly one."
  type        = string
  default     = "poc"
}

# ---------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR block for the dedicated VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "public_subnet_cidr" {
  description = "CIDR block for the single public subnet."
  type        = string
  default     = "10.20.1.0/24"
}

variable "availability_zone" {
  description = <<-EOT
    Availability zone for the subnet, instance and data volume. All three must
    agree — an EBS volume can only attach to an instance in its own zone.
    Leave empty to use the first zone the region reports as available.
  EOT
  type        = string
  default     = ""
}

# ---------------------------------------------------------------------------
# Access
# ---------------------------------------------------------------------------

variable "app_ingress_cidrs" {
  description = <<-EOT
    Who may reach the application on port 8080. The whole point of this
    deployment is that anyone can be sent the URL, so the default is open.
    Traffic is plain HTTP — see ADR-012 for why there is no TLS.
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "ssh_ingress_cidrs" {
  description = <<-EOT
    Who may reach SSH on port 22. Deliberately has NO default: an accidentally
    world-open SSH port on a public IP is the single most likely way this
    deployment gets compromised. Set it to your own address, e.g.
    ["203.0.113.4/32"]. Find yours with: curl -s https://checkip.amazonaws.com
  EOT
  type        = list(string)

  validation {
    condition     = length(var.ssh_ingress_cidrs) > 0
    error_message = "ssh_ingress_cidrs must list at least one CIDR."
  }

  validation {
    condition     = !contains(var.ssh_ingress_cidrs, "0.0.0.0/0")
    error_message = "Refusing to open SSH to the entire internet. Narrow ssh_ingress_cidrs to your own address."
  }
}

variable "ssh_public_key" {
  description = <<-EOT
    OpenSSH-format public key authorised for the ec2-user account. A public key
    is not a secret, so it is safe in terraform.tfvars. Generate a pair with:
      ssh-keygen -t ed25519 -f ~/.ssh/eop-poc -C eop-poc
    then pass the contents of ~/.ssh/eop-poc.pub here.
  EOT
  type        = string

  validation {
    condition     = can(regex("^(ssh-ed25519|ssh-rsa|ecdsa-sha2-) ", var.ssh_public_key))
    error_message = "ssh_public_key must be an OpenSSH public key, not a file path or a private key."
  }
}

# ---------------------------------------------------------------------------
# Compute and storage
# ---------------------------------------------------------------------------

variable "instance_type" {
  description = <<-EOT
    EC2 instance type. t3.small (2 GiB) was chosen over t3.micro (1 GiB) so
    that a JVM and a PostgreSQL container coexist without heap tuning games.
    Must be an x86_64 type — the image published by CI is linux/amd64.
  EOT
  type        = string
  default     = "t3.small"
}

variable "root_volume_size_gb" {
  description = "Size of the root volume. Holds the OS, Docker and the pulled image only."
  type        = number
  default     = 20
}

variable "data_volume_size_gb" {
  description = <<-EOT
    Size of the separate EBS volume holding the PostgreSQL data directory.
    Kept off the root volume on purpose: the instance is disposable, the data
    is not, and a snapshot of this volume is the whole migration path to
    another AWS account.
  EOT
  type        = number
  default     = 10
}

# ---------------------------------------------------------------------------
# Application
# ---------------------------------------------------------------------------

variable "app_image" {
  description = <<-EOT
    Fully qualified container image the instance runs. Lives in GHCR rather
    than ECR precisely because the registry then does not belong to the AWS
    account, so moving accounts does not move the artefact. Prefer an
    immutable digest or commit-SHA tag over :latest for anything you care
    about being able to reproduce.
  EOT
  type        = string
  default     = "ghcr.io/maglez/eop-threat-modeling:latest"
}

variable "ui_image" {
  description = <<-EOT
    Container image holding the built front end and the reverse proxy
    configuration. Published by the same pipeline run as app_image and tagged
    with the same commit SHA, because the two deploy together and a mismatched
    pair is a class of bug worth making impossible to express. Keep this tag
    and app_image on the same SHA.
  EOT
  type        = string
  default     = "ghcr.io/maglez/eop-threat-modeling-ui:latest"
}

variable "app_port" {
  description = <<-EOT
    Container port the application listens on. Since ADR-017 this is an
    internal port only: the application container publishes nothing to the
    host and is reachable solely through the reverse proxy on the Compose
    network. It is not open in the security group.
  EOT
  type        = number
  default     = 8080
}

variable "http_port" {
  description = <<-EOT
    Host port the reverse proxy publishes, and the only port open to the
    internet. The proxy serves the front end and forwards /api and /health to
    the application on the same origin (ADR-017), so a browser never makes a
    cross-origin request and no CORS configuration exists anywhere.
    Changed from 80 to 443 by EOP-21 (ADR-035): TLS is now live at Caddy.
  EOT
  type        = number
  default     = 443
}

variable "postgres_image" {
  description = "PostgreSQL container image, pinned to a major version."
  type        = string
  default     = "postgres:17-alpine"
}

variable "postgres_db" {
  description = "PostgreSQL database name."
  type        = string
  default     = "eop"
}

variable "postgres_user" {
  description = "PostgreSQL role name the application connects as."
  type        = string
  default     = "eop"
}

variable "docker_compose_version" {
  description = <<-EOT
    Pinned Docker Compose v2 plugin release installed on the instance. Amazon
    Linux 2023 ships the Docker engine but not the Compose plugin, so it is
    fetched from GitHub releases. Pinned rather than 'latest' so a rebuilt
    instance behaves identically to the one it replaced.
  EOT
  type        = string
  default     = "v2.32.4"
}
