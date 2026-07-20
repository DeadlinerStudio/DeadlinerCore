#!/bin/zsh
set -euo pipefail

if [[ -z "${DEVECO_HOME:-}" ]]; then
  export DEVECO_HOME=/Applications/DevEco-Studio.app
fi

if [[ ! -d "$DEVECO_HOME" ]]; then
  print -u2 "DEVECO_HOME does not point to DevEco Studio: $DEVECO_HOME"
  exit 1
fi

gradle_command=(./gradlew --offline --no-daemon --console=plain --warning-mode=none)

"${gradle_command[@]}" \
  :shared:compileDebugKotlinAndroid \
  :shared:compileKotlinIosSimulatorArm64 \
  :shared:linkDebugFrameworkIosSimulatorArm64 \
  :shared:macosArm64Test \
  :shared:verifySqlDelightMigration

"${gradle_command[@]}" :shared:linkDebugSharedOhosArm64

print "Phase 1/2 compilation acceptance passed."
print "Note: :shared:iosSimulatorArm64Test is excluded because CPF Kotlin/Native cannot link its test executable on this Xcode toolchain (_Kotlin_KonanStartStub)."
