# Changelog

## 1.0.0

First release.

- Account and entitlement aggregation for nodes, teams, roles, folders and
  records, with folder and record permission levels as separate entitlements.
- Single account refresh.
- Provisioning: create (invite), update (node, teams, roles, folder shares,
  record shares, name, job title, email), enable, disable, delete.
- Protection of the Commander service account against delete, disable and update.
- Encrypted API key.
- "Keeper Security" application type, with a settings page on IdentityIQ 8.x
  (KeeperAttributesForm.xhtml) and 9.0 (built from the connector's settings).
- `config/Keeper-Application.xml` creates a ready-to-configure
  "Keeper Security" application with an email correlation, and
  `config/Keeper-Tasks.xml` its aggregation tasks; both can be imported from
  Global Settings → Import from File.
- `install.sh` installs the connector, application type, application and
  tasks in one run; an existing application keeps its settings.
- Records, folders, teams, roles and nodes renamed in Keeper show their new
  names in IdentityIQ after the next Entitlement Aggregation (Keeper
  Entitlement Refresh rule).
- `uninstall.sh` removes the connector, its applications, tasks, identity
  mapping sources, correlation and application type.
- Create provisioning policy with a node picker that defaults to the root node.
- Aggregation task templates, including a one-click full aggregation. They
  remove accounts and entitlements that no longer exist in Keeper.
- Offline test suite with an anonymized mock of Commander Service Mode.
