# Infrastructure

Terraform for a single-instance deployment of the walking skeleton to a
personal AWS account. The design rationale — and the things it deliberately
does not do — is recorded in [ADR-012](../docs/adr/ADR-012-deployment-target.md).

## What this creates

| Resource | Why |
| --- | --- |
| VPC, public subnet, internet gateway, route table | Self-contained network, so nothing depends on a default VPC that a company account may have deleted |
| Security group | Application port open per `app_ingress_cidrs`, SSH restricted per `ssh_ingress_cidrs`, egress open for image pulls |
| EC2 instance (`t3.small`, Amazon Linux 2023) | Runs Docker and the Compose stack |
| Separate encrypted EBS volume | PostgreSQL data, deliberately off the root volume |
| Elastic IP | A stable address, so the URL you hand out keeps working |
| Key pair | SSH access from a key you generate locally |

No RDS, no load balancer, no TLS, no autoscaling, no ECR. The container image
is pulled from GHCR, which is not part of the AWS account and therefore does
not move when the account does.

## Prerequisites

1. An AWS account **on a non-corporate email address**, with MFA on the root
   user and root then left alone.
2. A bootstrap IAM identity whose keys live in a **named profile** in
   `~/.aws/credentials` — never in the repository `.env` file, which holds the
   Bedrock bearer token the agent session authenticates with.
3. A budget alarm (a few pounds a month, email notification) created *before*
   the first apply.
4. `terraform` installed: `brew install terraform`.
5. The GHCR package made public, once, in the GitHub UI — otherwise the
   instance cannot pull the image without credentials.

## Applying

```sh
cd infra
cp terraform.tfvars.example terraform.tfvars   # then edit it
ssh-keygen -t ed25519 -f ~/.ssh/eop-poc -C eop-poc

export AWS_PROFILE=eop-personal                # or set aws_profile in tfvars
terraform init
terraform plan
terraform apply
```

`terraform output demo_url` prints the address to share. Give the instance a
couple of minutes after apply returns: Terraform finishes when the instance
exists, not when the application has finished starting.

## Deploying a new version

Terraform sets the *initial* image and is not the deployment tool:

```sh
terraform output -raw deploy_command | sh
```

That pulls and restarts over SSH. Changing `app_image` and re-applying also
works, but replaces the instance to do it.

## Costs worth knowing

AWS bills every public IPv4 address, including an Elastic IP, whether or not it
is attached. The instance, the two EBS volumes and the address all cost money
continuously. `terraform destroy` is the off switch — though it will refuse to
remove the data volume, which carries `prevent_destroy`. Removing that guard is
meant to be a conscious act.

## Troubleshooting

The bootstrap script logs to the instance:

```sh
terraform output -raw ssh_command | sh
sudo tail -n 200 /var/log/cloud-init-output.log
cd /opt/eop && sudo docker compose -f compose.app.yml ps
sudo docker compose -f compose.app.yml logs app
```

If the application starts but the database looks empty, check the volume
actually mounted — `mountpoint /var/lib/eop`. The `fstab` entry uses `nofail`,
so a failed mount lets the instance boot and PostgreSQL then initialises a
fresh database on the root volume. The real data is still on the EBS volume;
mount it and restart the stack.

## Moving to another AWS account

1. Snapshot the data volume (`terraform output data_volume_id`).
2. Point a new profile at the new account, run `terraform init` and `apply` in a
   fresh state directory.
3. Restore the snapshot over the new data volume, or restore a `pg_dump`.

Nothing in this configuration names an account, an ARN, a CIDR or an AMI id, so
step 2 is genuinely just a different profile and a different `terraform.tfvars`.
