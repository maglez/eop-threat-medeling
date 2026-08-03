output "demo_url" {
  description = "Hand this to people. Plain HTTP — browsers will warn that it is not secure."
  value       = "http://${aws_eip.app.public_ip}:${var.app_port}"
}

output "health_url" {
  description = "The endpoint the walking skeleton exposes. Should return OK."
  value       = "http://${aws_eip.app.public_ip}:${var.app_port}/health"
}

output "ssh_command" {
  description = "Administration access. Substitute the private key you generated."
  value       = "ssh -i ~/.ssh/eop-poc ec2-user@${aws_eip.app.public_ip}"
}

output "deploy_command" {
  description = <<-EOT
    How a new image version is rolled out. Terraform sets the initial image and
    is not the deployment tool; run this after CI has published a new tag.
  EOT
  value       = "ssh -i ~/.ssh/eop-poc ec2-user@${aws_eip.app.public_ip} 'cd /opt/eop && sudo docker compose -f compose.app.yml pull && sudo docker compose -f compose.app.yml up -d'"
}

output "instance_id" {
  description = "EC2 instance id, for console lookups and troubleshooting."
  value       = aws_instance.app.id
}

output "data_volume_id" {
  description = <<-EOT
    The volume holding the database. A snapshot of this is the entire data
    migration path to another AWS account.
  EOT
  value       = aws_ebs_volume.data.id
}

output "bootstrap_log_hint" {
  description = "Where to look when the demo URL does not answer."
  value       = "sudo tail -n 200 /var/log/cloud-init-output.log"
}
