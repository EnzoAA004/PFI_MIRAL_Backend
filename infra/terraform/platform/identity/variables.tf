variable "project_id" {
  type    = string
  default = "pfi-asplanatti-fabrello-v1"
}

variable "region" {
  type    = string
  default = "us-central1"
}

variable "artifact_repository_id" {
  type        = string
  description = "Existing Artifact Registry repository the push identity is granted writer on. Referenced as data -- never created, imported, or replaced here."
  default     = "pfi-containers"
}

variable "github_repositories" {
  type = list(string)
  default = [
    "EnzoAA004/PFI_MVPTest_Enzo_Backend",
    "EnzoAA004/PFI_MVPTest_Enzo_Frontend",
    "EnzoAA004/PFI_MVPTest_Enzo_AImodule",
  ]
}
