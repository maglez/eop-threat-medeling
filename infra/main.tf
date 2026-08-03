# Data lookups and derived values. Nothing account-specific is hardcoded:
# the AMI is resolved from a public SSM parameter that AWS keeps current in
# every region, so this configuration works in a region it has never seen.

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_ssm_parameter" "amazon_linux_2023" {
  # Public, AWS-maintained parameter. Resolving it here rather than pinning an
  # AMI id is what makes the configuration region-portable — AMI ids are
  # region-scoped and would silently fail to exist elsewhere.
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# The application connects to PostgreSQL over the Compose network only; the
# database publishes no port to the host, let alone the internet. Generating
# the password here removes a manual step and keeps the value stable across
# instance replacement, which matters because the data volume survives and
# would otherwise stop authenticating.
resource "random_password" "postgres" {
  length  = 32
  special = false # avoids quoting hazards in the env file and JDBC URL
}

locals {
  name_prefix = "${var.project_name}-${var.environment}"

  availability_zone = coalesce(
    var.availability_zone != "" ? var.availability_zone : null,
    data.aws_availability_zones.available.names[0],
  )

  # AWS sets the NVMe serial of an EBS volume to its id with hyphens removed,
  # which gives a deterministic /dev/disk/by-id path. This matters because on
  # Nitro instances the device name requested at attach time (/dev/sdf) is not
  # the name the kernel uses (/dev/nvme1n1), and guessing wrong would format
  # the wrong disk.
  data_volume_serial = replace(aws_ebs_volume.data.id, "-", "")

  user_data = templatefile("${path.module}/templates/user-data.sh.tftpl", {
    app_image              = var.app_image
    app_port               = var.app_port
    postgres_image         = var.postgres_image
    postgres_db            = var.postgres_db
    postgres_user          = var.postgres_user
    postgres_password      = random_password.postgres.result
    docker_compose_version = var.docker_compose_version
    data_volume_serial     = local.data_volume_serial
    data_mount_point       = "/var/lib/eop"
    postgres_data_dir      = "/var/lib/eop/postgres"
    app_dir                = "/opt/eop"

    # The committed Compose file is embedded verbatim rather than fetched at
    # boot: no network failure mode, and the running configuration is versioned
    # with the apply that produced it. file() does no interpolation, so the
    # ${VAR} references inside survive and are resolved by Compose from the
    # .env file written alongside it — exactly as they are locally.
    compose_file = file("${path.module}/../compose.app.yml")
  })
}
