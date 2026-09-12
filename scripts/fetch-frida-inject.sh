#!/usr/bin/env bash
# Local equivalent of the CI step, for building outside GitHub Actions.
set -euxo pipefail
FRIDA_VERSION="${FRIDA_VERSION:-16.4.8}"
URL="https://github.com/frida/frida/releases/download/${FRIDA_VERSION}/frida-inject-${FRIDA_VERSION}-android-arm64.xz"
cd "$(dirname "$0")/.."
curl -fL "$URL" -o /tmp/frida-inject.xz
xz -d -f /tmp/frida-inject.xz -c > app/src/main/assets/frida-inject
chmod 755 app/src/main/assets/frida-inject
echo "Wrote app/src/main/assets/frida-inject"
