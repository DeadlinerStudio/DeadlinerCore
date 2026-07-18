#!/bin/zsh
set -euo pipefail

if [[ -z "${JAVA_HOME:-}" && -d /Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ]]; then
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
fi

if [[ -x ./gradlew ]]; then
  ./gradlew :shared:macosArm64Test --no-daemon --console=plain
else
  gradle :shared:macosArm64Test --no-daemon --console=plain
fi
