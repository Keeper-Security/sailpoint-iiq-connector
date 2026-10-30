#!/bin/sh
# Removes the Keeper Security connector from an IdentityIQ installation.
#
# Usage: ./uninstall.sh -d <identityiq-dir> [-y]
#
#   -d  IdentityIQ web application directory, the one containing WEB-INF
#       (e.g. /opt/tomcat/webapps/identityiq, or
#       /usr/local/tomcat/webapps/identityiq inside a Tomcat container)
#   -y  Do not ask for confirmation.
#
# Removes, from IdentityIQ only (nothing is changed in Keeper):
#   - every application of type "Keeper Security", with its accounts and
#     entitlements, its tasks (the aggregation tasks, and any other task
#     named "<application name> ..." or aggregating it) and their results,
#     and the identity mapping sources that read from it
#   - the "Keeper Security Email Correlation"
#   - the "Keeper Entitlement Refresh" rule
#   - the "Keeper Security" application type
#   - the connector jar, the settings page and earlier install backups
#
# Identities created by aggregation are kept; run "Prune Identity Cubes" to
# remove the ones left without accounts.
#
# Run as the user that owns the IdentityIQ files, with the IdentityIQ
# database reachable (the script uses "iiq console"). Restart the
# application server afterwards.

set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
IIQ_DIR=""
ASSUME_YES=""

usage() {
    echo "Usage: $0 -d <identityiq-dir> [-y]" >&2
    exit 2
}

while getopts "d:yh" opt; do
    case "$opt" in
        d) IIQ_DIR=$OPTARG ;;
        y) ASSUME_YES=1 ;;
        *) usage ;;
    esac
done

[ -n "$IIQ_DIR" ] || usage
[ -f "$IIQ_DIR/WEB-INF/bin/iiq" ] || { echo "Not an IdentityIQ directory: $IIQ_DIR" >&2; exit 1; }

if [ -z "$ASSUME_YES" ]; then
    echo "This removes from IdentityIQ every \"Keeper Security\" application with its"
    echo "accounts, entitlements, tasks and identity mapping sources, the Keeper Security"
    echo "application type, and the connector files. Nothing is changed in Keeper."
    printf "Continue? [y/N] "
    read -r answer
    case "$answer" in
        y|Y|yes|YES) ;;
        *) echo "Cancelled."; exit 1 ;;
    esac
fi

iiq_console() {
    # stdin: console commands. Prints the output; fails on errors.
    output=$(cd "$IIQ_DIR/WEB-INF/bin" && sh ./iiq console 2>&1) || true
    if printf '%s' "$output" | grep -qiE "exception|error"; then
        printf '%s\n' "$output" >&2
        echo "iiq console reported an error." >&2
        exit 1
    fi
    printf '%s\n' "$output" | grep -E "^> Removed:|Removed:" | sed 's/^> *//' || true
}

echo "1/2 Removing applications, tasks, correlation and application type"
printf 'import "%s"\nrule "Keeper Security Uninstall"\ndelete Rule "Keeper Security Uninstall"\nquit\n' \
    "$HERE/config/Keeper-Uninstall.xml" | iiq_console

echo "2/2 Removing connector files"
rm -f "$IIQ_DIR"/WEB-INF/lib/keeper-iiq-connector*.jar
rm -f "$IIQ_DIR/define/applications/KeeperAttributesForm.xhtml"
rm -rf "$IIQ_DIR"/WEB-INF/keeper-connector-backup-*

echo
echo "Done. Restart the application server."
echo "Optional: run Setup > Tasks > Prune Identity Cubes to remove identities left without accounts."
