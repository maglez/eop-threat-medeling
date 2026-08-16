output "demo_url" {
  description = "NOTE: with tls internal this URL is not reachable from a browser — the Caddy certificate is issued for 'localhost' only (no IP SAN), so connecting to the EC2 public IP returns an empty 200 with no security headers (see ADR-035). The demo is localhost-only; a public deployment requires a real certificate."
  value       = "https://${aws_eip.app.public_ip}${local.url_port_suffix}"
}

output "health_url" {
  description = "The health endpoint. NOTE: with tls internal the EC2 URL returns an empty 200 (not 'OK') because the HTTP routing is gated on host: localhost — see ADR-035. Returns 'OK' only when accessed as https://localhost/health on the host running the stack."
  value       = "https://${aws_eip.app.public_ip}${local.url_port_suffix}/health"
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
