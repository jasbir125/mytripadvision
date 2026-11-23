# Security groups: direct internet -> app -> database (no ALB).

# ---------------------------------------------------------------
# App tasks: accept HTTP on container_port from allowed_cidrs only
# ---------------------------------------------------------------

resource "aws_security_group" "app" {
  name        = "${local.name}-app-sg"
  description = "App tasks: direct HTTP from allowed_cidrs"
  vpc_id      = data.aws_vpc.this.id

  tags = { Name = "${local.name}-app-sg" }
}

resource "aws_vpc_security_group_ingress_rule" "app_ingress_http" {
  count = length(var.allowed_cidrs)

  security_group_id = aws_security_group.app.id
  description       = "HTTP from allowed CIDR"
  ip_protocol       = "tcp"
  from_port         = var.container_port
  to_port           = var.container_port
  cidr_ipv4         = var.allowed_cidrs[count.index]
}

# The app calls Bedrock, Tavily, OpenWeather, AviationStack, Hugging Face (first start) and the database.
resource "aws_vpc_security_group_egress_rule" "app_egress_all" {
  security_group_id = aws_security_group.app.id
  description       = "Outbound to the internet and the database"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# ---------------------------------------------------------------
# Database: PostgreSQL from the app only
# ---------------------------------------------------------------

resource "aws_security_group" "db" {
  name        = "${local.name}-db-sg"
  description = "Database: PostgreSQL from the app only"
  vpc_id      = data.aws_vpc.this.id

  tags = { Name = "${local.name}-db-sg" }
}

resource "aws_vpc_security_group_ingress_rule" "db_ingress_app" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL from the app"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.app.id
}
