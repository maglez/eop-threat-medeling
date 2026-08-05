# A dedicated VPC rather than the default one. It costs nothing extra (an
# internet gateway is free; only NAT gateways are billed, and a public instance
# needs none) and it removes a dependency on the default VPC still existing —
# enterprises routinely delete theirs, which would break the eventual move to
# the company account.

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${local.name_prefix}-vpc"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-igw"
  }
}

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = var.public_subnet_cidr
  availability_zone = local.availability_zone
  # True on purpose. There is no NAT gateway, so without an address at launch
  # the instance would have no route out and user-data would fail before the
  # Elastic IP is associated. Associating the EIP afterwards releases this
  # auto-assigned address.
  map_public_ip_on_launch = true

  tags = {
    Name = "${local.name_prefix}-public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "${local.name_prefix}-public"
  }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# ---------------------------------------------------------------------------
# Security group
# ---------------------------------------------------------------------------
# Rules are declared as separate resources rather than inline blocks so that a
# rule can be added or removed without Terraform rewriting the whole group.

resource "aws_security_group" "instance" {
  name        = "${local.name_prefix}-instance"
  description = "Public HTTP on the application port, SSH from a narrow CIDR"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-instance"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "app" {
  for_each = toset(var.app_ingress_cidrs)

  security_group_id = aws_security_group.instance.id
  description       = "Reverse proxy HTTP (plain text — see ADR-012)"
  cidr_ipv4         = each.value
  from_port         = var.http_port
  to_port           = var.http_port
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  for_each = toset(var.ssh_ingress_cidrs)

  security_group_id = aws_security_group.instance.id
  description       = "SSH administration"
  cidr_ipv4         = each.value
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"
}

# Egress must stay open: the instance pulls the application image from GHCR,
# installs packages from the Amazon Linux repositories and downloads the
# pinned Docker Compose plugin from GitHub releases.
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.instance.id
  description       = "Outbound for image pulls and package installation"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# There is deliberately no ingress rule for PostgreSQL, and none for the
# application's own port either. Both are reachable only over the Docker
# Compose network inside the instance. PostgreSQL publishes no host port at
# all; the application publishes none since ADR-017, so every request that
# reaches it has passed through the reverse proxy on the same origin. Opening
# var.app_port here would create a second, unproxied way in and quietly make
# that property untrue.
