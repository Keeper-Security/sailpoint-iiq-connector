#!/bin/sh
# Installs the Keeper Security connector into an IdentityIQ installation and
# creates its application and aggregation tasks.
#
# Usage: ./install.sh -d <identityiq-dir> [-a "<application name>"] [-n]
#
#   -d  IdentityIQ web application directory, the one containing WEB-INF
#       (e.g. /opt/tomcat/webapps/identityiq, or
#       /usr/local/tomcat/webapps/identityiq inside a Tomcat container)
#   -a  Optional. Application name. Default: "Keeper Security".
#   -n  Optional. Only install the connector and the application type; do not
#       create the application or its tasks (e.g. on other servers of a
#       cluster, or to create the application in the UI).
#
# An existing application of the same name is kept as it is, with its
# settings; its tasks are created or updated.
#
# Run as the user that owns the IdentityIQ files, with the IdentityIQ
# database reachable (the script uses "iiq console"). Restart the
# application server afterwards.

set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
JAR=$(ls "$HERE"/lib/keeper-iiq-connector-*.jar 2>/dev/null | head -n 1)
DEFAULT_APP_NAME="Keeper Security"
IIQ_DIR=""
APP_NAME=$DEFAULT_APP_NAME
SKIP_APP=""

usage() {
    echo "Usage: $0 -d <identityiq-dir> [-a \"<application name>\"] [-n]" >&2
    exit 2
}

while getopts "d:a:nh" opt; do
    case "$opt" in
        d) IIQ_DIR=$OPTARG ;;
        a) APP_NAME=$OPTARG ;;
        n) SKIP_APP=1 ;;
        *) usage ;;
    esac
done

[ -n "$IIQ_DIR" ] || usage
[ -n "$APP_NAME" ] || usage
[ -n "$JAR" ] || { echo "Connector jar not found in $HERE/lib" >&2; exit 1; }
[ -f "$IIQ_DIR/WEB-INF/bin/iiq" ] || { echo "Not an IdentityIQ directory: $IIQ_DIR" >&2; exit 1; }

STAMP=$(date +%Y%m%d%H%M%S)
BACKUP="$IIQ_DIR/WEB-INF/keeper-connector-backup-$STAMP"

echo "1/5 Installing $(basename "$JAR")"
mkdir -p "$BACKUP"
for old in "$IIQ_DIR"/WEB-INF/lib/keeper-iiq-connector*.jar; do
    [ -e "$old" ] && mv "$old" "$BACKUP/"
done
cp "$JAR" "$IIQ_DIR/WEB-INF/lib/"

echo "2/5 Installing the application settings page"
FORM="$IIQ_DIR/define/applications/KeeperAttributesForm.xhtml"
[ -e "$FORM" ] && cp "$FORM" "$BACKUP/"
cp "$HERE/web/define/applications/KeeperAttributesForm.xhtml" "$FORM"

iiq_console() {
    # stdin: console commands. Prints the console output.
    (cd "$IIQ_DIR/WEB-INF/bin" && sh ./iiq console 2>&1) || true
}

iiq_import() {
    # $1: XML file to import
    output=$(printf 'import "%s"\nquit\n' "$1" | iiq_console)
    if printf '%s' "$output" | grep -qiE "exception|error"; then
        printf '%s\n' "$output" >&2
        echo "Import of $1 failed." >&2
        exit 1
    fi
}

echo "3/5 Registering the Keeper Security application type (ConnectorRegistry) and entitlement refresh rule"
iiq_import "$HERE/config/Keeper.xml"
iiq_import "$HERE/config/Keeper-Entitlement-Refresh.xml"

if [ -n "$SKIP_APP" ]; then
    echo "4/5 Skipped the application (-n)"
    echo "5/5 Skipped the aggregation tasks (-n)"
else
    # Escape characters that are special to sed or XML in the application name.
    ESCAPED=$(printf '%s' "$APP_NAME" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/"/\&quot;/g' -e 's/[\/&|]/\\&/g')
    WORK=$(mktemp -d "${TMPDIR:-/tmp}/keeper-install.XXXXXX")
    trap 'rm -rf "$WORK"' EXIT

    if printf 'get Application "%s"\nquit\n' "$APP_NAME" | iiq_console | grep -q "<Application "; then
        echo "4/5 Application \"$APP_NAME\" already exists; kept with its settings"
    else
        echo "4/5 Creating the application \"$APP_NAME\""
        sed "s|name=\"$DEFAULT_APP_NAME\" type=\"Keeper Security\"|name=\"$ESCAPED\" type=\"Keeper Security\"|" \
            "$HERE/config/Keeper-Application.xml" > "$WORK/application.xml"
        iiq_import "$WORK/application.xml"
    fi

    echo "5/5 Creating the aggregation tasks for \"$APP_NAME\""
    # Keeper-Tasks.xml is for the "Keeper Security" application; rename it.
    sed "s|$DEFAULT_APP_NAME|$ESCAPED|g" "$HERE/config/Keeper-Tasks.xml" > "$WORK/tasks.xml"
    iiq_import "$WORK/tasks.xml"
fi

echo
echo "Done. Previous files (if any) were saved to $BACKUP"
if [ -n "$SKIP_APP" ]; then
    echo "Restart the application server."
else
    echo "Restart the application server. Then open Applications > Application Definition >"
    echo "$APP_NAME, enter the Service Mode URL and API Key on its settings page,"
    echo "click Test Connection, and Save."
fi
