param([string]$Model = "qwen3-4b-instruct-2507")
lms unload $Model
lms server stop
