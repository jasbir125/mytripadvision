# ALB removed to save cost (~$16-20/month).
# The app is reachable directly on the Fargate task's public IP, port 8000.
# To add an ALB back later, restore this file and re-add the load_balancer block in ecs.tf.
