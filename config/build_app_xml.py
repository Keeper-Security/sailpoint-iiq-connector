"""Builds the Keeper Security application XML files from keeper-schemas.xml.

Usage: python3 -I build_app_xml.py <keeper-schemas.xml> <Keeper-Tasks-Template.xml> <out-dir>

Writes (each can be imported with "iiq console" or Global Settings > Import from File):
  Keeper.xml               connector registry update: the "Keeper Security"
                           application type
  Keeper-Application.xml   creates the "Keeper Security" application and its
                           email correlation
  Keeper-Tasks.xml         aggregation tasks for that application
"""
import re
import sys

schemas_path, tasks_template_path, out_dir = sys.argv[1:4]
block = open(schemas_path).read()
for name in ("account", "node", "team", "role", "folder", "record"):
    block = block.replace(f"__ID_{name}__", "")

APP_TYPE = "Keeper Security"
APP_NAME = "Keeper Security"
CORRELATION = "Keeper Security Email Correlation"
FEATURES = "PROVISIONING, SYNC_PROVISIONING, ENABLE, UNLOCK"
CONFIG_ENTRIES = """      <entry key="encrypted" value="apiKey"/>
      <entry key="formPath" value="KeeperAttributesForm.xhtml"/>
      <entry key="pollTimeoutSeconds" value="60"/>
      <entry key="syncCacheTtlSeconds" value="30"/>
"""


def application(name, extra_entries="", correlation="", owner=""):
    return (
        f'<Application connector="com.keeper.identityiq.KeeperConnector" featuresString="{FEATURES}" '
        f'name="{name}" type="{APP_TYPE}">\n'
        + correlation
        + f"  <Attributes>\n    <Map>\n{CONFIG_ENTRIES}{extra_entries}    </Map>\n  </Attributes>\n"
        + owner
        + f"{block}</Application>\n")


# Matches Keeper accounts to identities on email.
open(f"{out_dir}/Keeper-Application.xml", "w").write(
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<!DOCTYPE sailpoint PUBLIC "sailpoint.dtd" "sailpoint.dtd">\n'
    '<sailpoint>\n'
    f'<CorrelationConfig name="{CORRELATION}">\n'
    '  <AttributeAssignments>\n'
    '    <Filter operation="EQ" property="email" value="email"/>\n'
    '  </AttributeAssignments>\n'
    '</CorrelationConfig>\n'
    + application(
        APP_NAME,
        extra_entries=f'      <entry key="templateApplication" value="{APP_TYPE}"/>\n',
        correlation=(
            '  <AccountCorrelationConfig>\n'
            f'    <Reference class="sailpoint.object.CorrelationConfig" name="{CORRELATION}"/>\n'
            '  </AccountCorrelationConfig>\n'),
        owner=(
            '  <Owner>\n'
            '    <Reference class="sailpoint.object.Identity" name="spadmin"/>\n'
            '  </Owner>\n'))
    + '</sailpoint>\n')

# The task template with the application name filled in.
tasks = open(tasks_template_path).read()
tasks = re.sub(r"<!--.*?-->", (
    f'<!--\n  Aggregation tasks for the "{APP_NAME}" application created by\n'
    '  Keeper-Application.xml. For an application with another name, run\n'
    '  install.sh -a "<application name>".\n-->'), tasks, count=1, flags=re.S)
open(f"{out_dir}/Keeper-Tasks.xml", "w").write(tasks.replace("__APP_NAME__", APP_NAME))

# Merging into ConnectorRegistry replaces the existing template with the same type.
open(f"{out_dir}/Keeper.xml", "w").write(
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<!DOCTYPE sailpoint PUBLIC "sailpoint.dtd" "sailpoint.dtd">\n'
    '<sailpoint>\n<ImportAction name="merge">\n<Configuration name="ConnectorRegistry">\n'
    '  <Attributes>\n    <Map>\n      <entry key="applicationTemplates">\n        <value>\n          <List>\n'
    + application(APP_TYPE) +
    '          </List>\n        </value>\n      </entry>\n    </Map>\n  </Attributes>\n'
    '</Configuration>\n</ImportAction>\n</sailpoint>\n')
