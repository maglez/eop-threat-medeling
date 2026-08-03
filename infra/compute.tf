# The instance is disposable; the data volume is not. Everything stateful lives
# on a separate EBS volume so that replacing the instance — which happens on any
# user-data change — loses nothing, and so that a single volume snapshot is a
# complete migration path to another AWS account.

resource "aws_key_pair" "admin" {
  key_name   = "${local.name_prefix}-admin"
  public_key = var.ssh_public_key

  tags = {
    Name = "${local.name_prefix}-admin"
  }
}

resource "aws_ebs_volume" "data" {
  availability_zone = local.availability_zone
  size              = var.data_volume_size_gb
  type              = "gp3"
  encrypted         = true

  tags = {
    Name = "${local.name_prefix}-data"
  }

  # Terraform must never destroy the database volume as a side effect of an
  # unrelated change. Removing this guard is a deliberate act.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_instance" "app" {
  ami           = data.aws_ssm_parameter.amazon_linux_2023.value
  instance_type = var.instance_type
  subnet_id     = aws_subnet.public.id
  key_name      = aws_key_pair.admin.key_name

  vpc_security_group_ids = [aws_security_group.instance.id]

  user_data = local.user_data

  # Terraform's default is false, which silently ignores bootstrap edits and
  # leaves the instance running stale configuration. Set true so that changing
  # the bootstrap actually takes effect, by replacing the instance. Safe here:
  # state lives on the data volume and the Elastic IP re-associates.
  user_data_replace_on_change = true

  # No IAM instance profile is attached. The instance makes no AWS API calls —
  # the container registry is GHCR, which is not AWS. Least privilege by having
  # no permissions at all. Attaching AmazonSSMManagedInstanceCore is the
  # one-line change that would enable Session Manager later.

  # IMDSv2 only, closing the server-side-request-forgery path to instance
  # credentials.
  metadata_options {
    http_tokens                 = "required"
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 1
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size_gb
    encrypted             = true
    delete_on_termination = true

    tags = {
      Name = "${local.name_prefix}-root"
    }
  }

  tags = {
    Name = "${local.name_prefix}-app"
  }
}

resource "aws_volume_attachment" "data" {
  # The name requested here is not the name the kernel uses. On Nitro instances
  # this appears as /dev/nvme1n1; user-data locates it deterministically by its
  # NVMe serial instead of guessing.
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.app.id
}

# A stable address, so the URL handed to people keeps working across instance
# replacement. Note that AWS bills every public IPv4 address, attached or not.
resource "aws_eip" "app" {
  domain = "vpc"

  tags = {
    Name = "${local.name_prefix}-app"
  }
}

resource "aws_eip_association" "app" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.app.id
}
