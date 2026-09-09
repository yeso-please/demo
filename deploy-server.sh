#!/usr/bin/env bash
set -Eeuo pipefail

MODE="${1:-update}"
APP_ROOT="/opt/travel-page"
STAGE_DIR="/home/ubuntu/travel-upload"
BACKUP_ROOT="/var/backups/travel-page"

if [[ "$MODE" != "first" && "$MODE" != "update" ]]; then
  echo "Usage: $0 first|update" >&2
  exit 2
fi

timestamp() {
  date +%Y%m%d-%H%M%S
}

echo "[1/8] Installing required packages"
sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jre-headless apache2 rsync openssl curl

echo "[2/8] Preparing application user and directories"
getent passwd travelpage >/dev/null || sudo useradd --system --home-dir "$APP_ROOT" --shell /usr/sbin/nologin travelpage
sudo install -d -o travelpage -g travelpage -m 750 "$APP_ROOT"
sudo install -d -o travelpage -g travelpage -m 750 "$APP_ROOT/data"
sudo install -d -o travelpage -g travelpage -m 750 "$APP_ROOT/uploads"
sudo install -d -o travelpage -g travelpage -m 700 "$APP_ROOT/config"
sudo install -d -m 700 "$BACKUP_ROOT"

echo "[3/8] Stopping previous Travel Page service and backing up mutable files"
sudo systemctl stop travel-page 2>/dev/null || true
backup_stamp="$(timestamp)"
if sudo test -f "$APP_ROOT/app.jar"; then
  sudo cp -a "$APP_ROOT/app.jar" "$BACKUP_ROOT/app.jar.$backup_stamp"
fi
if sudo test -f "$APP_ROOT/data/sumeun.mv.db"; then
  sudo cp -a "$APP_ROOT/data/sumeun.mv.db" "$BACKUP_ROOT/sumeun.mv.db.$backup_stamp"
fi

echo "[4/8] Installing application artifact"
sudo install -o travelpage -g travelpage -m 640 "$STAGE_DIR/app.jar" "$APP_ROOT/app.jar"

if [[ "$MODE" == "first" ]]; then
  echo "[5/8] Installing H2 database, uploads, and local secrets"
  sudo install -o travelpage -g travelpage -m 600 "$STAGE_DIR/sumeun.mv.db" "$APP_ROOT/data/sumeun.mv.db"
  if [[ -d "$STAGE_DIR/uploads" ]]; then
    sudo rsync -a --chown=travelpage:travelpage "$STAGE_DIR/uploads/" "$APP_ROOT/uploads/"
  fi
  sudo install -o travelpage -g travelpage -m 600 "$STAGE_DIR/application-secret.yaml" "$APP_ROOT/config/application-secret.yaml"

  if [[ ! -f /etc/travel-page.env ]]; then
    admin_token="$(openssl rand -hex 32)"
    sudo tee /etc/travel-page.env >/dev/null <<EOF
SPRING_DATASOURCE_URL=jdbc:h2:file:/opt/travel-page/data/sumeun;IFEXISTS=TRUE
SPRING_DATASOURCE_USERNAME=sa
SPRING_DATASOURCE_PASSWORD=
APP_UPLOAD_DIR=/opt/travel-page/uploads
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080
SERVER_FORWARD_HEADERS_STRATEGY=framework
H2_CONSOLE_ENABLED=false
SPRING_JPA_SHOW_SQL=false
SPRING_THYMELEAF_CACHE=true
SPRING_DEVTOOLS_LIVERELOAD_ENABLED=false
ADMIN_TOKEN=$admin_token
EOF
    sudo chown root:root /etc/travel-page.env
    sudo chmod 600 /etc/travel-page.env
  else
    echo "Keeping existing /etc/travel-page.env"
  fi
else
  echo "[5/8] Update mode: keeping remote H2 database, uploads, and secrets"
fi

echo "[6/8] Registering systemd service"
sudo tee /etc/systemd/system/travel-page.service >/dev/null <<'EOF'
[Unit]
Description=Travel Page Spring Boot Application
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=travelpage
Group=travelpage
WorkingDirectory=/opt/travel-page
EnvironmentFile=/etc/travel-page.env
ExecStart=/usr/bin/java -jar /opt/travel-page/app.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
TimeoutStopSec=30
UMask=0077
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

sudo chown -R travelpage:travelpage "$APP_ROOT"
sudo chmod 750 "$APP_ROOT" "$APP_ROOT/data" "$APP_ROOT/uploads"
sudo chmod 600 "$APP_ROOT/data/sumeun.mv.db" 2>/dev/null || true

echo "[7/8] Configuring Apache reverse proxy"
apache_backup="$BACKUP_ROOT/apache-$backup_stamp"
sudo mkdir -p "$apache_backup"
sudo cp -a /etc/apache2/sites-enabled "$apache_backup/sites-enabled"
sudo cp -a /etc/apache2/sites-available "$apache_backup/sites-available"

sudo a2enmod proxy proxy_http headers ssl >/dev/null
sudo tee /etc/apache2/sites-available/travel-page.conf >/dev/null <<'EOF'
<VirtualHost *:80>
    ServerName _
    ProxyPreserveHost On
    ProxyPass / http://127.0.0.1:8080/
    ProxyPassReverse / http://127.0.0.1:8080/
    RequestHeader set X-Forwarded-Proto "http"
    ErrorLog ${APACHE_LOG_DIR}/travel-page-error.log
    CustomLog ${APACHE_LOG_DIR}/travel-page-access.log combined
</VirtualHost>

<IfModule mod_ssl.c>
<VirtualHost *:443>
    ServerName _
    SSLEngine on
    SSLCertificateFile /etc/ssl/certs/ssl-cert-snakeoil.pem
    SSLCertificateKeyFile /etc/ssl/private/ssl-cert-snakeoil.key
    ProxyPreserveHost On
    ProxyPass / http://127.0.0.1:8080/
    ProxyPassReverse / http://127.0.0.1:8080/
    RequestHeader set X-Forwarded-Proto "https"
    ErrorLog ${APACHE_LOG_DIR}/travel-page-ssl-error.log
    CustomLog ${APACHE_LOG_DIR}/travel-page-ssl-access.log combined
</VirtualHost>
</IfModule>
EOF

sudo a2dissite 000-default default-ssl >/dev/null 2>&1 || true
sudo a2ensite travel-page.conf >/dev/null
sudo apache2ctl configtest

echo "[8/8] Starting services and checking health"
sudo systemctl daemon-reload
sudo systemctl enable travel-page >/dev/null
sudo systemctl restart travel-page

for attempt in $(seq 1 60); do
  if curl -fsSI http://127.0.0.1:8080/ >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" == "60" ]]; then
    sudo journalctl -u travel-page -n 100 --no-pager
    exit 1
  fi
  sleep 1
done

sudo systemctl restart apache2

if ! sudo systemctl is-active --quiet travel-page; then
  sudo journalctl -u travel-page -n 100 --no-pager
  exit 1
fi
if ! sudo systemctl is-active --quiet apache2; then
  sudo systemctl status apache2 --no-pager -l
  exit 1
fi
curl -fsSI http://127.0.0.1:8080/ >/dev/null
curl -fsSI http://127.0.0.1/ >/dev/null

echo "DEPLOY_OK"
sudo systemctl --no-pager --full status travel-page | sed -n '1,12p'
sudo systemctl --no-pager --full status apache2 | sed -n '1,10p'
