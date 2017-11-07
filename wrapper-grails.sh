#!/bin/bash
set -e

cmd="$@"

mkdir -p /data/images/config/
cat << EOF > /data/images/config/images-config.properties
casServerUrlPrefix=cas:8443/
casServerLoginUrl=cas:8443/login
serverName=someserver
dataSource.url=jdbc:postgresql://pg/ala-images
EOF

>&2 echo "Just assuming Postgres is up - executing command '$cmd'"
exec $cmd
