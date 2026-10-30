#!/bin/sh
# Builds, tests and packages the Keeper Security connector for IdentityIQ.
#
# Usage: IIQ_HOME=/path/to/identityiq ./build.sh
#
#   IIQ_HOME  An IdentityIQ 8.5 web application directory (containing WEB-INF).
#             Its libraries are used to run the offline tests.
#   lib/      Must contain identityiq.jar and connector-bundle-identityiq.jar
#             from IdentityIQ 8.5p2 (compile only; never packaged).
#
# Output: dist/keeper-iiq-connector-<version>.zip and its .sha256 file.

set -eu

VERSION=1.0.0
ROOT=$(cd "$(dirname "$0")" && pwd)
IIQ_HOME=${IIQ_HOME:?Set IIQ_HOME to an IdentityIQ web application directory}
NAME=keeper-iiq-connector-$VERSION
BUILD=$ROOT/build
PKG=$ROOT/dist/$NAME
LOGGING_JAR=$(ls "$IIQ_HOME"/WEB-INF/lib/commons-logging-*.jar | head -n 1)
COMPILE_CP="$ROOT/lib/identityiq.jar:$ROOT/lib/connector-bundle-identityiq.jar:$LOGGING_JAR"
RUN_CP="$IIQ_HOME/WEB-INF/classes:$IIQ_HOME/WEB-INF/lib/*"

rm -rf "$BUILD" "$PKG" "$ROOT/dist/$NAME.zip" "$ROOT/dist/$NAME.zip.sha256"
mkdir -p "$BUILD/classes" "$BUILD/test-classes" "$BUILD/config" "$PKG"

echo "== Compiling"
javac -proc:none -Xlint:all -Xlint:-serial -Xlint:-rawtypes -Xlint:-unchecked -Xlint:-path -Werror --release 11 \
    -cp "$COMPILE_CP" -d "$BUILD/classes" \
    "$ROOT"/src/main/java/com/keeper/identityiq/*.java
cat > "$BUILD/MANIFEST.MF" <<EOF
Implementation-Title: Keeper Security Connector for SailPoint IdentityIQ
Implementation-Version: $VERSION
Implementation-Vendor: Keeper Security
Built-For: SailPoint IdentityIQ 8.5p2
EOF
jar cfm "$BUILD/$NAME.jar" "$BUILD/MANIFEST.MF" -C "$BUILD/classes" .

echo "== Generating IdentityIQ XML"
python3 -I "$ROOT/config/build_app_xml.py" "$ROOT/config/keeper-schemas.xml" \
    "$ROOT/config/Keeper-Tasks-Template.xml" "$BUILD/config"
for f in "$BUILD"/config/*.xml "$ROOT/config/Keeper-Tasks-Template.xml" "$ROOT/config/Keeper-Uninstall.xml" \
         "$ROOT/config/Keeper-Entitlement-Refresh.xml" \
         "$ROOT/web/define/applications/KeeperAttributesForm.xhtml"; do
    xmllint --noout --nonet "$f" 2>&1 | grep -v "sailpoint.dtd" | grep . && { echo "Invalid XML: $f" >&2; exit 1; }
done

echo "== Running offline tests"
javac -proc:none -nowarn -Xlint:-path -cp "$BUILD/classes:$RUN_CP" -d "$BUILD/test-classes" "$ROOT/test/KeeperConnectorTest.java"
run_suite() {
    # $1: mock mode, $2: port
    log="$BUILD/commands-$1.log"
    : > "$log"
    python3 -I "$ROOT/test/mock_service_mode.py" "$2" "$ROOT/test/fixtures" "$log" "$1" &
    mock=$!
    sleep 1
    status=0
    java -Dlog4j2.configurationFile="$ROOT/test/log4j2-test.properties" \
        -cp "$BUILD/test-classes:$BUILD/classes:$RUN_CP" \
        KeeperConnectorTest "http://127.0.0.1:$2" "$log" "$1" >> "$BUILD/test-output.txt" 2>&1 || status=$?
    kill "$mock"
    wait "$mock" 2>/dev/null || true
    return $status
}
: > "$BUILD/test-output.txt"
run_suite full 18977 && run_suite whoami-403 18978 || { cat "$BUILD/test-output.txt"; echo "Tests failed" >&2; exit 1; }
PASSED=$(grep -c "^  PASS " "$BUILD/test-output.txt")
SUMMARY="$PASSED checks passed, 0 failed"
echo "$SUMMARY"

echo "== Assembling $NAME"
# Only what the connector needs at runtime, plus the install and uninstall scripts.
mkdir -p "$PKG/lib" "$PKG/config" "$PKG/web/define/applications"
cp "$BUILD/$NAME.jar" "$PKG/lib/"
cp "$BUILD/config/Keeper.xml" "$BUILD/config/Keeper-Application.xml" "$BUILD/config/Keeper-Tasks.xml" \
   "$ROOT/config/Keeper-Entitlement-Refresh.xml" "$ROOT/config/Keeper-Uninstall.xml" "$PKG/config/"
cp "$ROOT/web/define/applications/KeeperAttributesForm.xhtml" "$PKG/web/define/applications/"
cp "$ROOT/scripts/install.sh" "$ROOT/scripts/uninstall.sh" "$PKG/"
chmod +x "$PKG/install.sh" "$PKG/uninstall.sh"

# Nothing proprietary or environment-specific may ship.
if find "$PKG" -name "identityiq*.jar" -o -name "connector-bundle*.jar" | grep -q .; then
    echo "SailPoint jars found in the package" >&2; exit 1
fi
if grep -rIl -E "ngrok-free|api-key\" value=|apiKey\" value=\"[^\"]" "$PKG" | grep -q .; then
    echo "Environment-specific values found in the package" >&2; exit 1
fi

(cd "$ROOT/dist" && zip -qr "$NAME.zip" "$NAME" && shasum -a 256 "$NAME.zip" > "$NAME.zip.sha256")
echo "== Built dist/$NAME.zip"
cat "$ROOT/dist/$NAME.zip.sha256"
