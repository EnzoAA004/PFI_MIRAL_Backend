output "bucket_name" {
  value = var.bucket_name
}

output "bucket_url" {
  value = var.create_bucket ? google_storage_bucket.this[0].url : data.google_storage_bucket.existing[0].url
}
